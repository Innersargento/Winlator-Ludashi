package com.winlator.cmod.xserver.extensions;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.util.Log;
import android.util.SparseIntArray;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.renderer.GPUImage;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Pixmap;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.errors.BadImplementation;
import com.winlator.cmod.xserver.errors.BadMatch;
import com.winlator.cmod.xserver.errors.BadWindow;
import com.winlator.cmod.xserver.errors.XRequestError;

import java.io.IOException;

public class CompositeExtension implements Extension {
    private static final String TAG = "Composite";
    public static final byte MAJOR_OPCODE = -106;

    private static final int COMPOSITE_MAJOR = 0;
    private static final int COMPOSITE_MINOR = 4;

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte REDIRECT_WINDOW = 1;
        private static final byte REDIRECT_SUBWINDOWS = 2;
        private static final byte UNREDIRECT_WINDOW = 3;
        private static final byte UNREDIRECT_SUBWINDOWS = 4;
        private static final byte CREATE_REGION_FROM_BORDER_CLIP = 5;
        private static final byte NAME_WINDOW_PIXMAP = 6;
        private static final byte GET_OVERLAY_WINDOW = 7;
        private static final byte RELEASE_OVERLAY_WINDOW = 8;
    }

    private static final int REDIRECT_AUTOMATIC = 0;
    private static final int REDIRECT_MANUAL = 1;

    private final SparseIntArray redirectedWindows = new SparseIntArray();
    private final SparseIntArray redirectedSubwindows = new SparseIntArray();

    private final XServer xServer;

    public CompositeExtension(XServer xServer) {
        this.xServer = xServer;
    }

    @Override
    public String getName() {
        return "Composite";
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

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream)
            throws IOException, XRequestError {
        try {
            dispatch(client, inputStream, outputStream);
        }
        finally {
            client.skipRequest();
        }
    }

    private void dispatch(XClient client, XInputStream inputStream, XOutputStream outputStream)
            throws IOException, XRequestError {
        int opcode = client.getRequestData();

        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION:
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.REDIRECT_WINDOW:
                setRedirect(client, inputStream, redirectedWindows, true, true);
                break;
            case ClientOpcodes.REDIRECT_SUBWINDOWS:
                setRedirect(client, inputStream, redirectedSubwindows, true, false);
                break;
            case ClientOpcodes.UNREDIRECT_WINDOW:
                setRedirect(client, inputStream, redirectedWindows, false, true);
                break;
            case ClientOpcodes.UNREDIRECT_SUBWINDOWS:
                setRedirect(client, inputStream, redirectedSubwindows, false, false);
                break;
            case ClientOpcodes.NAME_WINDOW_PIXMAP:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER,
                        XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                    nameWindowPixmap(client, inputStream);
                }
                break;
            case ClientOpcodes.CREATE_REGION_FROM_BORDER_CLIP:
                Log.e(TAG, "CreateRegionFromBorderClip requested, but this server has no XFixes");
                throw new BadImplementation();
            case ClientOpcodes.GET_OVERLAY_WINDOW:
            case ClientOpcodes.RELEASE_OVERLAY_WINDOW:
                Log.e(TAG, "overlay window requested (opcode " + opcode + "), not implemented");
                throw new BadImplementation();
            default:
                Log.e(TAG, "unhandled Composite opcode " + opcode);
                throw new BadImplementation();
        }
    }

    private void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream)
            throws IOException {
        int clientMajor = inputStream.readInt();
        int clientMinor = inputStream.readInt();

        boolean clientIsOlder = clientMajor < COMPOSITE_MAJOR
                || (clientMajor == COMPOSITE_MAJOR && clientMinor < COMPOSITE_MINOR);
        int major = clientIsOlder ? clientMajor : COMPOSITE_MAJOR;
        int minor = clientIsOlder ? clientMinor : COMPOSITE_MINOR;

        Log.d(TAG, "QueryVersion client=" + clientMajor + "." + clientMinor
                + " -> " + major + "." + minor);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(major);
            outputStream.writeInt(minor);
            outputStream.writePad(16);
        }
    }

    private void setRedirect(XClient client, XInputStream inputStream, SparseIntArray state,
                             boolean redirect, boolean wholeWindow) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        int update = inputStream.readByte() & 0xff;

        Window window;
        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
            window = client.xServer.windowManager.getWindow(windowId);
        }
        if (window == null) throw new BadWindow(windowId);

        if (redirect) state.put(windowId, update);
        else state.delete(windowId);

        Log.d(TAG, (redirect ? "Redirect" : "Unredirect") + (wholeWindow ? "Window" : "Subwindows")
                + " 0x" + Integer.toHexString(windowId)
                + " update=" + (update == REDIRECT_MANUAL ? "Manual"
                              : update == REDIRECT_AUTOMATIC ? "Automatic" : String.valueOf(update))
                + " (already how this server stores windows; recorded, not acted on)");
    }

    private void nameWindowPixmap(XClient client, XInputStream inputStream)
            throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        int pixmapId = inputStream.readInt();

        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        Drawable content = window.getContent();
        GPUImage gpuImage = content != null ? content.getGPUImage() : null;
        if (gpuImage == null) {
            Log.e(TAG, "NameWindowPixmap 0x" + Integer.toHexString(windowId)
                    + ": window content has no GPUImage to alias, refusing to hand back a "
                    + "disconnected pixmap");
            throw new BadMatch();
        }

        Drawable alias = client.xServer.drawableManager.createDrawable(
                pixmapId, content.width, content.height, content.visual);
        if (alias == null) throw new BadMatch();
        alias.setGPUImage(gpuImage);

        Pixmap pixmap = client.xServer.pixmapManager.createPixmap(alias);
        if (pixmap == null) throw new BadMatch();
        client.registerAsOwnerOfResource(pixmap);

        Log.d(TAG, "NameWindowPixmap window=0x" + Integer.toHexString(windowId)
                + " -> pixmap=0x" + Integer.toHexString(pixmapId)
                + " " + content.width + "x" + content.height);
    }
}
