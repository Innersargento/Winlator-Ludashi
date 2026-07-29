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

/**
 * Composite extension, enough of it for winex11.
 *
 * Wine only looks for one thing here: whether XCompositeQueryExtension succeeds. If it does,
 * winex11 sets usexcomposite and create_gl_drawable() takes the DC_GL_CHILD_WIN path -- a real
 * X child window, redirected, with a GLX drawable on top of it. If it does not, winex11 falls
 * back to the "GLXPixmap hack", and that fallback is what broke rendering here: the GLX drawable
 * becomes a pixmap, kopper sees is_window == 0, no VkSwapchain is built, and zink presents by
 * reading the framebuffer back to the CPU. That is the black screen, and the corePutImage
 * traffic behind it.
 *
 * On redirection being a no-op: in a stock X server, redirecting a window moves its rendering
 * off the parent's storage and into a private pixmap, so a compositing manager can paint it.
 * This server is already built that way -- every Window owns its Drawable (Window.getContent())
 * and the window manager composites those. So the state redirection is supposed to establish
 * already holds for every window; the requests only need to validate, record, and succeed.
 *
 * The distinction that would matter, CompositeRedirectManual meaning "stop painting this window
 * yourself, a compositing manager owns it now", has no one to honour it here: this server is the
 * compositor, and Wine wants exactly the painting it already does. The update mode is recorded
 * so the state can be queried while debugging, and deliberately not acted on.
 *
 * Three requests are left unimplemented rather than faked, because a wrong answer here would
 * cost more to debug than a named error -- which is precisely how the GLXPixmap fallback above
 * was found.
 */
public class CompositeExtension implements Extension {
    private static final String TAG = "Composite";
    public static final byte MAJOR_OPCODE = -106;

    /** 0.4 is the current protocol; NameWindowPixmap needs >= 0.2, GetOverlayWindow >= 0.3. */
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

    /** CompositeRedirectAutomatic / CompositeRedirectManual, as sent in the update field. */
    private static final int REDIRECT_AUTOMATIC = 0;
    private static final int REDIRECT_MANUAL = 1;

    /** window id -> update mode, for whole windows and for subwindow redirection. */
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
            // Same reason as in GLXExtension: XClientRequestHandler only rewinds to the next
            // request boundary when a handler throws, so anything left unread would shift every
            // following request on the connection.
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
                // Would have to hand back an XFixes REGION, and there is no XFixes here.
                Log.e(TAG, "CreateRegionFromBorderClip requested, but this server has no XFixes");
                throw new BadImplementation();
            case ClientOpcodes.GET_OVERLAY_WINDOW:
            case ClientOpcodes.RELEASE_OVERLAY_WINDOW:
                // For a compositing manager that wants to draw above every other window. Nothing
                // in this stack does; answering with the root window would quietly be a lie.
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

        // Both sides settle on the lower version, compared as a (major, minor) pair.
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

    /**
     * Redirect/Unredirect, for a window or for its subwindows. Both requests carry the same body:
     * a WINDOW and a one-byte update mode.
     */
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

    /**
     * Names the window's backing storage as a pixmap the client can then draw from or texture.
     *
     * The alias has to be the window's actual buffer -- a fresh drawable would collect rendering
     * nobody ever sees. The window's content must therefore be backed by a GPUImage that can be
     * pointed at from a second drawable, which is the same mechanism DRI3 uses to hand out
     * imported AHardwareBuffers.
     */
    private void nameWindowPixmap(XClient client, XInputStream inputStream)
            throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        int pixmapId = inputStream.readInt();

        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        Drawable content = window.getContent();
        GPUImage gpuImage = content != null ? content.getGPUImage() : null;
        if (gpuImage == null) {
            // Aliasing by copying would silently desynchronise the moment either side is drawn to.
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
