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

public class GLXExtension implements Extension {
    private static final String TAG = "GLXExtension";

    public static final byte MAJOR_OPCODE = -105;
    private static final byte FIRST_EVENT = 65;
    private static final byte FIRST_ERROR = -112;

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

        private static final int NONE = 0x8000;
        private static final int TRUE_COLOR = 0x8002;
        private static final int RGBA_BIT = 0x01;
        private static final int WINDOW_BIT = 0x01;
        private static final int PIXMAP_BIT = 0x02;
    }

    private static final int FBCONFIG_NUM_ATTRIBS = 26;
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

    private FBConfig[] getFBConfigs() {
        if (fbConfigs != null) return fbConfigs;

        Visual visual = xServer.pixmapManager.visual;
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

    private void writeStringReply(XClient client, XOutputStream outputStream, String value)
            throws IOException {
        byte[] bytes = value.getBytes();
        int n = bytes.length + 1;
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
        inputStream.readInt();
        int name = inputStream.readInt();

        String value;
        switch (name) {
            case 1:  value = SERVER_VENDOR; break;
            case 2:  value = SERVER_VERSION; break;
            case 3:  value = SERVER_EXTENSIONS; break;
            default: value = ""; break;
        }
        writeStringReply(client, outputStream, value);
    }

    private void queryExtensionsString(XClient client, XInputStream inputStream, XOutputStream outputStream)
            throws IOException {
        inputStream.readInt();
        writeStringReply(client, outputStream, SERVER_EXTENSIONS);
    }

    private void writeFBConfig(XOutputStream outputStream, FBConfig config) throws IOException {
        writeAttrib(outputStream, Attr.FBCONFIG_ID, config.id);
        writeAttrib(outputStream, Attr.VISUAL_ID, config.visualId);
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
        inputStream.readInt();
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

    private void getVisualConfigs(XClient client, XInputStream inputStream, XOutputStream outputStream)
            throws IOException {
        inputStream.readInt();
        FBConfig[] configs = getFBConfigs();
        int words = configs.length * VISUAL_CONFIG_NUM_PROPS;

        int xVisualClass = xServer.pixmapManager.visual.visualClass;

        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, (byte)0, words);
            outputStream.writeInt(configs.length);
            outputStream.writeInt(VISUAL_CONFIG_NUM_PROPS);
            outputStream.writePad(16);
            for (FBConfig config : configs) {
                outputStream.writeInt(config.visualId);
                outputStream.writeInt(xVisualClass);
                outputStream.writeInt(1);
                outputStream.writeInt(8);
                outputStream.writeInt(8);
                outputStream.writeInt(8);
                outputStream.writeInt(8);
                outputStream.writeInt(0);
                outputStream.writeInt(0);
                outputStream.writeInt(0);
                outputStream.writeInt(0);
                outputStream.writeInt(config.doubleBuffer ? 1 : 0);
                outputStream.writeInt(0);
                outputStream.writeInt(32);
                outputStream.writeInt(config.depthSize);
                outputStream.writeInt(config.stencilSize);
                outputStream.writeInt(0);
                outputStream.writeInt(0);
            }
        }
    }

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
        inputStream.readInt();
        inputStream.readInt();
        boolean isDirect = inputStream.readByte() != 0;
        inputStream.skip(3);
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

    private void makeCurrent(XClient client, XInputStream inputStream, XOutputStream outputStream,
                             boolean contextCurrentVariant) throws IOException {
        int drawable;
        int contextId;
        if (contextCurrentVariant) {
            inputStream.readInt();
            drawable = inputStream.readInt();
            inputStream.readInt();
            contextId = inputStream.readInt();
        }
        else {
            drawable = inputStream.readInt();
            contextId = inputStream.readInt();
            inputStream.readInt();
        }
        Log.w(TAG, "MakeCurrent from an indirect client: context=0x"
                + Integer.toHexString(contextId) + " drawable=0x" + Integer.toHexString(drawable));

        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, (byte)0, 0);
            outputStream.writeInt(contextId != 0 ? 1 : 0);
            outputStream.writePad(20);
        }
    }

    private void getDrawableAttributes(XClient client, XInputStream inputStream, XOutputStream outputStream)
            throws IOException {
        int drawableId = inputStream.readInt();

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
                inputStream.readInt();
                inputStream.readInt();
                inputStream.readInt();
                boolean direct = inputStream.readByte() != 0;
                inputStream.skip(3);
                createContext(client, contextId, 0, direct);
                break;
            }
            case ClientOpcodes.CREATE_NEW_CONTEXT: {
                int contextId = inputStream.readInt();
                int fbconfigId = inputStream.readInt();
                inputStream.readInt();
                inputStream.readInt();
                inputStream.readInt();
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

            case ClientOpcodes.CREATE_PIXMAP: {
                inputStream.readInt();
                inputStream.readInt();
                int pixmapId = inputStream.readInt();
                int glxPixmapId = inputStream.readInt();
                glxDrawables.put(glxPixmapId, pixmapId);
                break;
            }
            case ClientOpcodes.DESTROY_PIXMAP: {
                int glxPixmapId = inputStream.readInt();
                glxDrawables.remove(glxPixmapId);
                break;
            }
            case ClientOpcodes.CREATE_WINDOW: {
                inputStream.readInt();
                inputStream.readInt();
                int windowId = inputStream.readInt();
                int glxWindowId = inputStream.readInt();
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
            case ClientOpcodes.CHANGE_DRAWABLE_ATTRIBUTES:
            case ClientOpcodes.CLIENT_INFO:
            case ClientOpcodes.SET_CLIENT_INFO_ARB:
            case ClientOpcodes.SET_CLIENT_INFO_2ARB:
            case ClientOpcodes.WAIT_GL:
            case ClientOpcodes.WAIT_X:
            case ClientOpcodes.SWAP_BUFFERS:
                break;

            case ClientOpcodes.RENDER:
                Log.e(TAG, "indirect GLX Render request -- client is NOT direct rendering");
                break;

            default:
                Log.e(TAG, "unhandled GLX opcode " + opcode + " (len="
                        + client.getRemainingRequestLength() + ")");
                throw new BadImplementation();
        }
    }
}
