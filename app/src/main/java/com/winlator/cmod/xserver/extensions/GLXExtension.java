package com.winlator.cmod.xserver.extensions;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.util.Log;
import android.util.SparseArray;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Visual;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.errors.BadImplementation;
import com.winlator.cmod.xserver.errors.XRequestError;

import java.io.IOException;

/**
 * Minimal GLX server for direct-rendering clients.
 *
 * Mesa built with -Dglx=dri does all rendering and presentation client-side: buffers come from
 * DRI3 and reach the screen through Present, both of which this server already implements. What it
 * still needs from GLX is the bookkeeping half of the protocol -- version negotiation, the fbconfig
 * list, and XIDs for contexts and drawables. None of the ~200 indirect-rendering requests are ever
 * sent by a direct client, so they are deliberately not implemented.
 *
 * With -Dglx=xlib (the previous build) none of this was reached: GLX was implemented entirely
 * inside libGL and the server never saw a GLX request, at the cost of presenting every frame by
 * reading the framebuffer back to the CPU and pushing it through MIT-SHM.
 *
 * Unhandled opcodes are logged rather than silently failing, so the first run tells us exactly
 * which request a client stops on.
 */
public class GLXExtension implements Extension {
    private static final String TAG = "GLXExtension";

    public static final byte MAJOR_OPCODE = -105;
    private static final byte FIRST_EVENT = 65;
    private static final byte FIRST_ERROR = -112;

    // The version we advertise. 1.4 is what Mesa's DRI loader expects for
    // GLX_ARB_create_context / GLX_ARB_create_context_profile to be usable.
    private static final int GLX_MAJOR = 1;
    private static final int GLX_MINOR = 4;

    private static final String SERVER_VENDOR = "Winlator";
    private static final String SERVER_VERSION = "1.4";
    private static final String SERVER_EXTENSIONS =
            "GLX_ARB_create_context GLX_ARB_create_context_profile "
            + "GLX_EXT_create_context_es2_profile GLX_ARB_multisample "
            + "GLX_EXT_visual_info GLX_EXT_visual_rating GLX_SGI_make_current_read";

    private static abstract class ClientOpcodes {
        private static final byte RENDER = 1;
        private static final byte CREATE_CONTEXT = 3;
        private static final byte DESTROY_CONTEXT = 4;
        private static final byte MAKE_CURRENT = 5;
        private static final byte IS_DIRECT = 6;
        private static final byte QUERY_VERSION = 7;
        private static final byte WAIT_GL = 8;
        private static final byte WAIT_X = 9;
        private static final byte SWAP_BUFFERS = 11;
        private static final byte GET_VISUAL_CONFIGS = 14;
        private static final byte QUERY_EXTENSIONS_STRING = 18;
        private static final byte QUERY_SERVER_STRING = 19;
        private static final byte CLIENT_INFO = 20;
        private static final byte GET_FB_CONFIGS = 21;
        private static final byte CREATE_NEW_CONTEXT = 24;
        private static final byte QUERY_CONTEXT = 25;
        private static final byte MAKE_CONTEXT_CURRENT = 26;
        private static final byte CREATE_PIXMAP = 22;
        private static final byte DESTROY_PIXMAP = 23;
        private static final byte GET_DRAWABLE_ATTRIBUTES = 29;
        private static final byte CHANGE_DRAWABLE_ATTRIBUTES = 30;
        private static final byte CREATE_WINDOW = 31;
        private static final byte DELETE_WINDOW = 32;
        private static final byte SET_CLIENT_INFO_ARB = 33;
        private static final byte CREATE_CONTEXT_ATTRIBS_ARB = 34;
        private static final byte SET_CLIENT_INFO_2ARB = 35;
    }

    /** GLX attribute tags, from the GLX 1.4 spec / glxtokens.h. */
    private static abstract class Attr {
        private static final int BUFFER_SIZE = 2;
        private static final int LEVEL = 3;
        private static final int DOUBLEBUFFER = 5;
        private static final int STEREO = 6;
        private static final int AUX_BUFFERS = 7;
        private static final int RED_SIZE = 8;
        private static final int GREEN_SIZE = 9;
        private static final int BLUE_SIZE = 10;
        private static final int ALPHA_SIZE = 11;
        private static final int DEPTH_SIZE = 12;
        private static final int STENCIL_SIZE = 13;
        private static final int ACCUM_RED_SIZE = 14;
        private static final int ACCUM_GREEN_SIZE = 15;
        private static final int ACCUM_BLUE_SIZE = 16;
        private static final int ACCUM_ALPHA_SIZE = 17;
        private static final int CONFIG_CAVEAT = 0x20;
        private static final int X_VISUAL_TYPE = 0x22;
        private static final int TRANSPARENT_TYPE = 0x23;
        private static final int VISUAL_ID = 0x800B;
        private static final int SCREEN = 0x800C;
        private static final int PRESERVED_CONTENTS = 0x801B;
        private static final int LARGEST_PBUFFER = 0x801C;
        private static final int WIDTH = 0x801D;
        private static final int HEIGHT = 0x801E;
        private static final int EVENT_MASK = 0x801F;
        private static final int DRAWABLE_TYPE = 0x8010;
        private static final int RENDER_TYPE = 0x8011;
        private static final int X_RENDERABLE = 0x8012;
        private static final int FBCONFIG_ID = 0x8013;
        private static final int MAX_PBUFFER_WIDTH = 0x8016;
        private static final int MAX_PBUFFER_HEIGHT = 0x8017;
        private static final int MAX_PBUFFER_PIXELS = 0x8018;
        private static final int VISUAL_SELECT_GROUP = 0x8028;
        private static final int SAMPLE_BUFFERS = 100000;
        private static final int SAMPLES = 100001;

        // Values
        private static final int NONE = 0x8000;
        private static final int TRUE_COLOR = 0x8002;
        private static final int RGBA_BIT = 0x01;
        private static final int WINDOW_BIT = 0x01;
        private static final int PIXMAP_BIT = 0x02;
    }

    /**
     * Number of (tag, value) pairs emitted per fbconfig by {@link #writeFBConfig}. Must match that
     * method exactly -- the client reads this many pairs per config out of a flat array, so a
     * mismatch shifts every following config rather than failing cleanly.
     */
    private static final int FBCONFIG_NUM_ATTRIBS = 26;
    /** Fixed-layout property count of the legacy GetVisualConfigs reply. */
    private static final int VISUAL_CONFIG_NUM_PROPS = 18;

    private static class FBConfig {
        int id;
        int visualId;
        boolean doubleBuffer;
        int depthSize;
        int stencilSize;
    }

    private static class Context {
        int id;
        int fbconfigId;
        boolean isDirect;
    }

    private final XServer xServer;
    private final SparseArray<Context> contexts = new SparseArray<>();
    /** GLXWindow/GLXPixmap XID -> the X drawable it wraps. */
    private final SparseArray<Integer> glxDrawables = new SparseArray<>();
    private FBConfig[] fbConfigs;

    public GLXExtension(XServer xServer) {
        this.xServer = xServer;
    }

    @Override
    public String getName() {
        return "GLX";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return FIRST_ERROR;
    }

    @Override
    public byte getFirstEventId() {
        return FIRST_EVENT;
    }

    /**
     * Builds the advertised fbconfig list, bound to the server's displayable visual.
     *
     * Mesa intersects this list with what the DRI driver reports, so anything here that zink cannot
     * back is dropped client-side -- but a config missing here can never be selected. The set is
     * therefore deliberately broad across the axes Wine's wglChoosePixelFormat varies.
     */
    private FBConfig[] getFBConfigs() {
        if (fbConfigs != null) return fbConfigs;

        Visual visual = xServer.pixmapManager.visual;
        // The bisect that cut this list down to depth 24 is over: the access violation it was
        // hunting turned out to be a box64 artifact, not an exotic config. Running the same Mesa
        // and the same server under ARM64EC/FEX -- where winex11's unix half is native aarch64 and
        // no emulator bridge sits on glXMakeCurrent -- reaches a direct context and three AHB
        // imports without faulting. Configs were exonerated, so the full matrix is back.
        int[] depths = {24, 32, 16, 0};
        int[] stencils = {8, 0};
        boolean[] doubles = {true, false};

        fbConfigs = new FBConfig[depths.length * stencils.length * doubles.length];
        int i = 0;
        int nextId = 0x60;
        for (boolean doubleBuffer : doubles) {
            for (int depth : depths) {
                for (int stencil : stencils) {
                    FBConfig config = new FBConfig();
                    config.id = nextId++;
                    config.visualId = visual.id;
                    config.doubleBuffer = doubleBuffer;
                    config.depthSize = depth;
                    config.stencilSize = stencil;
                    fbConfigs[i++] = config;
                }
            }
        }
        return fbConfigs;
    }

    private void writeReplyHeader(XClient client, XOutputStream outputStream, byte data, int replyLength)
            throws IOException {
        outputStream.writeByte(RESPONSE_CODE_SUCCESS);
        outputStream.writeByte(data);
        outputStream.writeShort(client.getSequenceNumber());
        outputStream.writeInt(replyLength);
    }

    private void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream)
            throws IOException {
        int clientMajor = inputStream.readInt();
        int clientMinor = inputStream.readInt();

        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, (byte)0, 0);
            outputStream.writeInt(GLX_MAJOR);
            outputStream.writeInt(GLX_MINOR);
            outputStream.writePad(16);
        }
    }

    /** Reply carrying a single counted, NUL-terminated string (QueryServerString/ExtensionsString). */
    private void writeStringReply(XClient client, XOutputStream outputStream, String value)
            throws IOException {
        byte[] bytes = value.getBytes();
        int n = bytes.length + 1;              // includes the terminating NUL
        int padded = (n + 3) & ~3;

        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, (byte)0, padded / 4);
            outputStream.writeInt(0);
            outputStream.writeInt(n);
            outputStream.writePad(16);
            outputStream.write(bytes);
            outputStream.writePad(padded - bytes.length);
        }
    }

    private void queryServerString(XClient client, XInputStream inputStream, XOutputStream outputStream)
            throws IOException {
        inputStream.readInt();                 // screen
        int name = inputStream.readInt();

        String value;
        switch (name) {
            case 1:  value = SERVER_VENDOR; break;      // GLX_VENDOR
            case 2:  value = SERVER_VERSION; break;     // GLX_VERSION
            case 3:  value = SERVER_EXTENSIONS; break;  // GLX_EXTENSIONS
            default: value = ""; break;
        }
        writeStringReply(client, outputStream, value);
    }

    private void queryExtensionsString(XClient client, XInputStream inputStream, XOutputStream outputStream)
            throws IOException {
        inputStream.readInt();                 // screen
        writeStringReply(client, outputStream, SERVER_EXTENSIONS);
    }

    private void writeFBConfig(XOutputStream outputStream, FBConfig config) throws IOException {
        writeAttrib(outputStream, Attr.FBCONFIG_ID, config.id);
        writeAttrib(outputStream, Attr.VISUAL_ID, config.visualId);
        // GLX_SCREEN is deliberately absent: Mesa's fbconfig tag parser does not know it and warns
        // once per config. It belongs in the QueryContext reply, not here.
        writeAttrib(outputStream, Attr.X_VISUAL_TYPE, Attr.TRUE_COLOR);
        writeAttrib(outputStream, Attr.X_RENDERABLE, 1);
        writeAttrib(outputStream, Attr.DRAWABLE_TYPE, Attr.WINDOW_BIT | Attr.PIXMAP_BIT);
        writeAttrib(outputStream, Attr.RENDER_TYPE, Attr.RGBA_BIT);
        writeAttrib(outputStream, Attr.CONFIG_CAVEAT, Attr.NONE);
        writeAttrib(outputStream, Attr.TRANSPARENT_TYPE, Attr.NONE);
        writeAttrib(outputStream, Attr.VISUAL_SELECT_GROUP, 0);
        writeAttrib(outputStream, Attr.BUFFER_SIZE, 32);
        writeAttrib(outputStream, Attr.LEVEL, 0);
        writeAttrib(outputStream, Attr.DOUBLEBUFFER, config.doubleBuffer ? 1 : 0);
        writeAttrib(outputStream, Attr.STEREO, 0);
        writeAttrib(outputStream, Attr.AUX_BUFFERS, 0);
        writeAttrib(outputStream, Attr.RED_SIZE, 8);
        writeAttrib(outputStream, Attr.GREEN_SIZE, 8);
        writeAttrib(outputStream, Attr.BLUE_SIZE, 8);
        writeAttrib(outputStream, Attr.ALPHA_SIZE, 8);
        writeAttrib(outputStream, Attr.DEPTH_SIZE, config.depthSize);
        writeAttrib(outputStream, Attr.STENCIL_SIZE, config.stencilSize);
        writeAttrib(outputStream, Attr.ACCUM_RED_SIZE, 0);
        writeAttrib(outputStream, Attr.ACCUM_GREEN_SIZE, 0);
        writeAttrib(outputStream, Attr.ACCUM_BLUE_SIZE, 0);
        writeAttrib(outputStream, Attr.ACCUM_ALPHA_SIZE, 0);
        writeAttrib(outputStream, Attr.SAMPLE_BUFFERS, 0);
        writeAttrib(outputStream, Attr.SAMPLES, 0);
    }

    private void writeAttrib(XOutputStream outputStream, int tag, int value) throws IOException {
        outputStream.writeInt(tag);
        outputStream.writeInt(value);
    }

    private void getFBConfigs(XClient client, XInputStream inputStream, XOutputStream outputStream)
            throws IOException {
        inputStream.readInt();                 // screen
        FBConfig[] configs = getFBConfigs();
        int words = configs.length * FBCONFIG_NUM_ATTRIBS * 2;

        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, (byte)0, words);
            outputStream.writeInt(configs.length);
            outputStream.writeInt(FBCONFIG_NUM_ATTRIBS);
            outputStream.writePad(16);
            for (FBConfig config : configs) writeFBConfig(outputStream, config);
        }
    }

    /**
     * Legacy GLX 1.2 visual list. The first 18 words are positional rather than tagged; Wine still
     * reaches this through glXChooseVisual when it probes for a usable visual.
     */
    private void getVisualConfigs(XClient client, XInputStream inputStream, XOutputStream outputStream)
            throws IOException {
        inputStream.readInt();                 // screen
        FBConfig[] configs = getFBConfigs();
        int words = configs.length * VISUAL_CONFIG_NUM_PROPS;

        // The second word is the *X* visual class (TrueColor == 4), not the GLX token: the client
        // runs it through convert_from_x_visual_type(), which maps 0..5 and yields GLX_NONE for
        // anything else. Sending GLX_TRUE_COLOR (0x8002) here poisons every visual it advertises,
        // and this is the list glXChooseVisual answers from.
        int xVisualClass = xServer.pixmapManager.visual.visualClass;

        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, (byte)0, words);
            outputStream.writeInt(configs.length);
            outputStream.writeInt(VISUAL_CONFIG_NUM_PROPS);
            outputStream.writePad(16);
            for (FBConfig config : configs) {
                outputStream.writeInt(config.visualId);
                outputStream.writeInt(xVisualClass);
                outputStream.writeInt(1);                                  // rgba
                outputStream.writeInt(8);                                  // red
                outputStream.writeInt(8);                                  // green
                outputStream.writeInt(8);                                  // blue
                outputStream.writeInt(8);                                  // alpha
                outputStream.writeInt(0);                                  // accum red
                outputStream.writeInt(0);                                  // accum green
                outputStream.writeInt(0);                                  // accum blue
                outputStream.writeInt(0);                                  // accum alpha
                outputStream.writeInt(config.doubleBuffer ? 1 : 0);
                outputStream.writeInt(0);                                  // stereo
                outputStream.writeInt(32);                                 // buffer size
                outputStream.writeInt(config.depthSize);
                outputStream.writeInt(config.stencilSize);
                outputStream.writeInt(0);                                  // aux buffers
                outputStream.writeInt(0);                                  // level
            }
        }
    }

    /**
     * Direct clients still allocate a server-side XID for their context so that glXQueryContext and
     * resource cleanup work; no GL state lives here.
     */
    private void createContext(XClient client, int contextId, int fbconfigId, boolean isDirect) {
        Context context = new Context();
        context.id = contextId;
        context.fbconfigId = fbconfigId;
        context.isDirect = isDirect;
        contexts.put(contextId, context);
    }

    private void createContextAttribsARB(XClient client, XInputStream inputStream) {
        int contextId = inputStream.readInt();
        int fbconfigId = inputStream.readInt();
        inputStream.readInt();                 // screen
        inputStream.readInt();                 // share_list
        boolean isDirect = inputStream.readByte() != 0;
        inputStream.skip(3);
        // numAttribs and the attribute pairs follow; none of them mean anything to a server that
        // holds no GL state, and skipRequest() consumes whatever is left.
        createContext(client, contextId, fbconfigId, isDirect);
    }

    private void isDirect(XClient client, XInputStream inputStream, XOutputStream outputStream)
            throws IOException {
        int contextId = inputStream.readInt();
        Context context = contexts.get(contextId);
        boolean direct = context == null || context.isDirect;

        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, (byte)0, 0);
            outputStream.writeByte((byte)(direct ? 1 : 0));
            outputStream.writePad(23);
        }
    }

    private void queryContext(XClient client, XInputStream inputStream, XOutputStream outputStream)
            throws IOException {
        int contextId = inputStream.readInt();
        Context context = contexts.get(contextId);
        int fbconfigId = context != null ? context.fbconfigId : 0;

        final int numAttribs = 3;
        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, (byte)0, numAttribs * 2);
            outputStream.writeInt(numAttribs);
            outputStream.writePad(20);
            writeAttrib(outputStream, Attr.FBCONFIG_ID, fbconfigId);
            writeAttrib(outputStream, Attr.SCREEN, 0);
            writeAttrib(outputStream, Attr.RENDER_TYPE, Attr.RGBA_BIT);
        }
    }

    /**
     * Direct contexts never send MakeCurrent -- Mesa keeps that entirely client-side -- so this only
     * runs for an indirect client. Returning a nonzero tag keeps such a client alive rather than
     * failing it outright, even though no GL state is bound here.
     *
     * The two opcodes do not share a layout: MakeCurrent is (drawable, context, oldContextTag) while
     * MakeContextCurrent is (oldContextTag, drawable, readDrawable, context).
     */
    private void makeCurrent(XClient client, XInputStream inputStream, XOutputStream outputStream,
                             boolean contextCurrentVariant) throws IOException {
        int drawable;
        int contextId;
        if (contextCurrentVariant) {
            inputStream.readInt();             // old context tag
            drawable = inputStream.readInt();
            inputStream.readInt();             // read drawable
            contextId = inputStream.readInt();
        }
        else {
            drawable = inputStream.readInt();
            contextId = inputStream.readInt();
            inputStream.readInt();             // old context tag
        }
        Log.w(TAG, "MakeCurrent from an indirect client: context=0x"
                + Integer.toHexString(contextId) + " drawable=0x" + Integer.toHexString(drawable));

        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, (byte)0, 0);
            outputStream.writeInt(contextId != 0 ? 1 : 0);
            outputStream.writePad(20);
        }
    }

    /**
     * Size matters here: the client sizes its drawable -- and therefore the swapchain kopper builds
     * for it -- from GLX_WIDTH/GLX_HEIGHT. Reporting them as zero, or omitting them, yields a
     * degenerate swapchain rather than a clean failure.
     */
    private void getDrawableAttributes(XClient client, XInputStream inputStream, XOutputStream outputStream)
            throws IOException {
        int drawableId = inputStream.readInt();

        // The id may be a GLXWindow we handed out, or a plain X drawable.
        Integer backing = glxDrawables.get(drawableId);
        int lookupId = backing != null ? backing : drawableId;
        Drawable drawable = xServer.drawableManager.getDrawable(lookupId);

        int width = drawable != null ? drawable.width : 0;
        int height = drawable != null ? drawable.height : 0;

        final int numAttribs = 4;
        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, (byte)0, numAttribs * 2);
            outputStream.writeInt(numAttribs);
            outputStream.writePad(20);
            writeAttrib(outputStream, Attr.WIDTH, width);
            writeAttrib(outputStream, Attr.HEIGHT, height);
            writeAttrib(outputStream, Attr.PRESERVED_CONTENTS, 1);
            writeAttrib(outputStream, Attr.EVENT_MASK, 0);
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream)
            throws IOException, XRequestError {
        try {
            dispatch(client, inputStream, outputStream);
        }
        finally {
            // XClientRequestHandler only rewinds to the next request boundary when a handler throws
            // XRequestError; after a successful dispatch it does not. Any byte left unread here --
            // a trailing attribute list, a field this server does not care about -- would shift
            // every subsequent request on the connection and the client dies with a broken pipe.
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
            case ClientOpcodes.QUERY_SERVER_STRING:
                queryServerString(client, inputStream, outputStream);
                break;
            case ClientOpcodes.QUERY_EXTENSIONS_STRING:
                queryExtensionsString(client, inputStream, outputStream);
                break;
            case ClientOpcodes.GET_FB_CONFIGS:
                getFBConfigs(client, inputStream, outputStream);
                break;
            case ClientOpcodes.GET_VISUAL_CONFIGS:
                getVisualConfigs(client, inputStream, outputStream);
                break;

            case ClientOpcodes.CREATE_CONTEXT: {
                int contextId = inputStream.readInt();
                inputStream.readInt();          // visual
                inputStream.readInt();          // screen
                inputStream.readInt();          // share_list
                boolean direct = inputStream.readByte() != 0;
                inputStream.skip(3);
                createContext(client, contextId, 0, direct);
                break;
            }
            case ClientOpcodes.CREATE_NEW_CONTEXT: {
                int contextId = inputStream.readInt();
                int fbconfigId = inputStream.readInt();
                inputStream.readInt();          // screen
                inputStream.readInt();          // render_type
                inputStream.readInt();          // share_list
                boolean direct = inputStream.readByte() != 0;
                inputStream.skip(3);
                createContext(client, contextId, fbconfigId, direct);
                break;
            }
            case ClientOpcodes.CREATE_CONTEXT_ATTRIBS_ARB:
                createContextAttribsARB(client, inputStream);
                break;
            case ClientOpcodes.DESTROY_CONTEXT: {
                int contextId = inputStream.readInt();
                contexts.remove(contextId);
                break;
            }
            case ClientOpcodes.IS_DIRECT:
                isDirect(client, inputStream, outputStream);
                break;
            case ClientOpcodes.QUERY_CONTEXT:
                queryContext(client, inputStream, outputStream);
                break;
            case ClientOpcodes.MAKE_CURRENT:
                makeCurrent(client, inputStream, outputStream, false);
                break;
            case ClientOpcodes.MAKE_CONTEXT_CURRENT:
                makeCurrent(client, inputStream, outputStream, true);
                break;

            // winex11 reaches these only through its "GLXPixmap hack", the fallback it takes for a
            // child GL window when XComposite is unavailable -- and the Wine built for this
            // container has no XComposite support compiled in at all, so the fallback is the only
            // path it has. Leaving CreatePixmap unimplemented made the hack fail with
            // BadImplementation, which cost the drawable its window status: kopper saw a pixmap,
            // built no VkSwapchain, and zink fell back to presenting by CPU readback.
            //
            // A GLXPixmap never gets a swapchain -- that is by construction, not a gap here. This
            // restores correctness for that path, not speed.
            case ClientOpcodes.CREATE_PIXMAP: {
                inputStream.readInt();          // screen
                inputStream.readInt();          // fbconfig
                int pixmapId = inputStream.readInt();
                int glxPixmapId = inputStream.readInt();
                // numAttribs and the attribute list follow; skipRequest() consumes them.
                glxDrawables.put(glxPixmapId, pixmapId);
                break;
            }
            case ClientOpcodes.DESTROY_PIXMAP: {
                int glxPixmapId = inputStream.readInt();
                glxDrawables.remove(glxPixmapId);
                break;
            }
            case ClientOpcodes.CREATE_WINDOW: {
                inputStream.readInt();          // screen
                inputStream.readInt();          // fbconfig
                int windowId = inputStream.readInt();
                int glxWindowId = inputStream.readInt();
                // numAttribs and the attribute list follow; skipRequest() consumes them.
                glxDrawables.put(glxWindowId, windowId);
                break;
            }
            case ClientOpcodes.DELETE_WINDOW: {
                int glxWindowId = inputStream.readInt();
                glxDrawables.remove(glxWindowId);
                break;
            }
            case ClientOpcodes.GET_DRAWABLE_ATTRIBUTES:
                getDrawableAttributes(client, inputStream, outputStream);
                break;
            // Consumed and ignored: the server keeps no per-client GL state, and these carry none
            // that a direct client can observe.
            case ClientOpcodes.CHANGE_DRAWABLE_ATTRIBUTES:
            case ClientOpcodes.CLIENT_INFO:
            case ClientOpcodes.SET_CLIENT_INFO_ARB:
            case ClientOpcodes.SET_CLIENT_INFO_2ARB:
            case ClientOpcodes.WAIT_GL:
            case ClientOpcodes.WAIT_X:
            case ClientOpcodes.SWAP_BUFFERS:
                break;

            case ClientOpcodes.RENDER:
                // Indirect rendering. A direct client never sends this; if one does, the build is
                // not using DRI3 and that is the thing to fix, not this branch.
                Log.e(TAG, "indirect GLX Render request -- client is NOT direct rendering");
                break;

            default:
                Log.e(TAG, "unhandled GLX opcode " + opcode + " (len="
                        + client.getRemainingRequestLength() + ")");
                throw new BadImplementation();
        }
    }
}
