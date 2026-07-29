package com.winlator.cmod.xserver.extensions;

import android.util.Log;
import android.util.SparseArray;
import com.winlator.cmod.renderer.GPUImage;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.cmod.core.Callback;
import com.winlator.cmod.sysvshm.SysVSharedMemory;
import com.winlator.cmod.xconnector.XConnectorEpoll;
import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Pixmap;
import com.winlator.cmod.xserver.PresentPathStats;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XResource;
import com.winlator.cmod.xserver.XResourceManager;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.errors.BadAlloc;
import com.winlator.cmod.xserver.errors.BadDrawable;
import com.winlator.cmod.xserver.errors.BadIdChoice;
import com.winlator.cmod.xserver.errors.BadImplementation;
import com.winlator.cmod.xserver.errors.BadWindow;
import com.winlator.cmod.xserver.errors.XRequestError;

import java.io.IOException;
import java.nio.ByteBuffer;

public class DRI3Extension implements Extension, XResourceManager.OnResourceLifecycleListener {
    public static final byte MAJOR_OPCODE = -102;
    private XServer xServer;
    private final SparseArray<DirectContent> directContents = new SparseArray<DirectContent>();
    
    private class DirectContent {
        Window window;
        Pixmap content;
        
        private DirectContent(Window window, Pixmap pixmap) {
            this.window = window;
            this.content = pixmap;
        }
    }

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte OPEN = 1;
        private static final byte PIXMAP_FROM_BUFFER = 2;
        private static final byte GET_SUPPORTED_MODIFIERS = 6;
        private static final byte PIXMAP_FROM_BUFFERS = 7;
    }
    
    public DRI3Extension(XServer xserver) {
        this.xServer = xserver;
        this.xServer.pixmapManager.addOnResourceLifecycleListener(this);
    }

    @Override
    public String getName() {
        return "DRI3";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return 0;
    }

    @Override
    public byte getFirstEventId() {
        return 0;
    }

    private void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(8);

        // Must be >= 1.2, together with Present >= 1.2: Mesa's x11_dri3_has_multibuffer() gates the
        // entire DRI3 path on both, and glxext.c turns a false there into an outright refusal to
        // use zink. We answer 1.0 historically even though PixmapFromBuffers -- the request that
        // *defines* DRI3 1.2 -- has been implemented here all along, so the version was the only
        // thing standing in the way.
        Log.i("Dri3", "QueryVersion -- replying 1.2");
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(1);
            outputStream.writeInt(2);
            outputStream.writePad(16);
        }
    }

    /**
     * DRI3 1.2. Reporting no modifiers is legal and keeps clients on the implicit-modifier path,
     * which is what {@link #pixmapFromHardwareBuffer} expects anyway -- an AHardwareBuffer carries
     * its own layout and there is nothing to negotiate.
     */
    private void getSupportedModifiers(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.readInt();  // window
        Log.d("Dri3", "GetSupportedModifiers -> none (implicit modifiers only)");

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(0);   // numWindowModifiers
            outputStream.writeInt(0);   // numScreenModifiers
            outputStream.writePad(16);
        }
    }

    private void open(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int drawableId = inputStream.readInt();
        inputStream.skip(4);

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);

        // The reply must carry nfd=1 plus a device fd as ancillary data. Replying nfd=0 makes
        // Mesa's x11_dri3_open() return -1, dri3_create_screen() log "screen N does not appear to
        // be DRI3 capable", and the GL client fall back to drisw -- software presentation through
        // MIT-SHM, i.e. reading every frame back from the GPU. Vulkan clients never noticed,
        // because the wrapper ICD allocates AHardwareBuffers itself and only calls
        // PixmapFromBuffers.
        //
        // There is no DRM render node to hand out here. That turns out not to matter: with
        // MESA_LOADER_DRIVER_OVERRIDE=zink, loader_get_driver_for_fd() returns the override
        // without ever inspecting the fd, and dri3_create_screen() then deliberately bails out
        // with return_zink so the caller builds the zink/kopper screen instead. The fd only has
        // to exist and be >= 0. A small memfd satisfies that and is safe to hand over.
        int fd = SysVSharedMemory.createMemoryFd("dri3-device", 4096);
        if (fd < 0) {
            Log.e("Dri3", "Open: could not create a device fd; the client will fall back to drisw");
        }

        try (XStreamLock lock = outputStream.lock()) {
            if (fd >= 0) outputStream.setAncillaryFd(fd);
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)(fd >= 0 ? 1 : 0));
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writePad(24);
        }

        // SCM_RIGHTS gave the client its own descriptor; drop ours.
        if (fd >= 0) {
            XConnectorEpoll.closeFd(fd);
            Log.i("Dri3", "Open(drawable=0x" + Integer.toHexString(drawableId) + ") -> nfd=1");
        }
    }

    private void pixmapFromBuffer(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int pixmapId = inputStream.readInt();
        int windowId = inputStream.readInt();
        int size = inputStream.readInt();
        short width = inputStream.readShort();
        short height = inputStream.readShort();
        short stride = inputStream.readShort();
        byte depth = inputStream.readByte();
        inputStream.skip(1);

        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        Pixmap pixmap = client.xServer.pixmapManager.getPixmap(pixmapId);
        if (pixmap != null) throw new BadIdChoice(pixmapId);

        int fd = inputStream.getAncillaryFd();
        pixmapFromHardwareBuffer(client, pixmapId, width, height, depth, fd, window);
    }

    private void pixmapFromBuffers(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        Log.d("Dri3", "Received pixmap from buffers");
        int pixmapId = inputStream.readInt();
        Log.d("Dri3", "Read pixmap id " + pixmapId);
        int windowId = inputStream.readInt();
        Log.d("Dri3", "Read window id " + windowId);
        inputStream.skip(4);
        short width = inputStream.readShort();
        Log.d("Dri3", "Read width " + width);
        short height = inputStream.readShort();
        Log.d("Dri3", "Read height " + height);
        int stride = inputStream.readInt();
        Log.d("Dri3", "Read stride " + stride);
        int offset = inputStream.readInt();
        Log.d("Dri3", "Read offset " + offset);
        inputStream.skip(24);
        byte depth = inputStream.readByte();
        Log.d("Dri3", "Read depth " + depth);
        inputStream.skip(3);
        long modifiers = inputStream.readLong();
        Log.d("Dri3", "Read modifiers " + modifiers);
        
        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);
        Pixmap pixmap = client.xServer.pixmapManager.getPixmap(pixmapId);
        if (pixmap != null) throw new BadIdChoice(pixmapId);
        
        int fd = inputStream.getAncillaryFd();

        pixmapFromHardwareBuffer(client, pixmapId, width, height, depth, fd, window);
    }
    
    private void pixmapFromHardwareBuffer(XClient client, int pixmapId, short width, short height, byte depth, int fd, Window window) throws IOException, XRequestError {
        // The client segfaults inside glXMakeCurrent right after these imports, so this is the last
        // thing the server does before the crash window. Naming each step tells us whether the AHB
        // import itself is what goes wrong.
        Log.i("Dri3", "importing AHB: pixmap=0x" + Integer.toHexString(pixmapId)
                + " " + width + "x" + height + " depth=" + depth + " fd=" + fd);
        try {
            GPUImage gpuImage = new GPUImage(fd);
            Log.i("Dri3", "  GPUImage created for pixmap=0x" + Integer.toHexString(pixmapId));
            Drawable drawable = client.xServer.drawableManager.createDrawable(pixmapId, width, height, depth);
            drawable.setGPUImage(gpuImage);
            drawable.setOnDrawListener(() -> client.xServer.windowManager.triggerOnUpdateWindowContentDirect(window, drawable));
            client.xServer.getXServerView().nativeAddDirectContent(window.id, drawable);
            Pixmap pixmap = client.xServer.pixmapManager.createPixmap(drawable);
            client.registerAsOwnerOfResource(pixmap);
            
            DirectContent directContent = new DirectContent(window, pixmap);
            directContents.put(pixmap.id, directContent);

            if (PresentPathStats.ENABLED) PresentPathStats.onDri3Import();
            Log.i("Dri3", "  direct content registered for pixmap=0x" + Integer.toHexString(pixmapId));
        }
        catch (Throwable t) {
            Log.e("Dri3", "AHB import FAILED for pixmap=0x" + Integer.toHexString(pixmapId), t);
            throw t;
        }
        finally {
            XConnectorEpoll.closeFd(fd);
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION :
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.OPEN :
                try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
                    open(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.PIXMAP_FROM_BUFFER:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                    pixmapFromBuffer(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.PIXMAP_FROM_BUFFERS:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                    pixmapFromBuffers(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.GET_SUPPORTED_MODIFIERS:
                getSupportedModifiers(client, inputStream, outputStream);
                break;
            default:
                // Most likely FenceFromFD/FDFromFence or BuffersFromPixmap. Naming it beats
                // guessing from a dead connection.
                Log.e("Dri3", "unhandled DRI3 opcode " + opcode);
                throw new BadImplementation();
        }
    }

    @Override
    public void onFreeResource(XResource resource) {
        if (resource instanceof Pixmap) {
            Pixmap pixmap = (Pixmap)resource;
            DirectContent content = directContents.get(pixmap.id);
            if (content != null) {
                xServer.getXServerView().nativeRemoveDirectContent(content.window.id, pixmap.id);
                directContents.remove(pixmap.id);
            }
        }    
    }
}
