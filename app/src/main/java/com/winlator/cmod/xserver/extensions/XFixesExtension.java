package com.winlator.cmod.xserver.extensions;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.util.Log;
import android.util.SparseArray;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.errors.BadImplementation;
import com.winlator.cmod.xserver.errors.XRequestError;

import java.io.IOException;

/**
 * Regions only, which is what Present needs: its valid-area and update-area
 * arguments are XFixes REGIONs by protocol, so a client driving Present has to
 * be able to build one. Mesa's loader_dri3 calls CreateRegion unconditionally
 * on the first window swap, and libxcb does not merely raise BadRequest when an
 * extension is absent -- it calls _xcb_conn_shutdown() and the connection dies.
 *
 * The regions are recorded but not otherwise honoured: presentPixmap already
 * discards the update-area and repaints the whole drawable.
 */
public class XFixesExtension implements Extension {
    public static final byte MAJOR_OPCODE = -107;
    private static final String TAG = "XFixes";
    /* 2.0 is the version that introduced regions, and is the least that lets a
     * client use them -- CreateRegion is itself a version-2 request, so there is
     * no lower version to hide behind.
     *
     * It does not buy the regions alone, though: requests 23 through 27, the
     * cursor-name and change-cursor calls, are version 2 as well, so announcing
     * 2.0 announces those too. libXcursor takes it up immediately -- it names
     * every themed cursor it loads, but only when XFixes answers -- which is
     * why SetCursorName started arriving the moment this extension existed.
     * Only ExpandRegion (28), Hide/ShowCursor (29, 30) and the pointer barriers
     * (31, 32) sit above this version.
     */
    private static final int MAJOR_VERSION = 2;
    private static final int MINOR_VERSION = 0;

    private final SparseArray<int[]> regions = new SparseArray<>();

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte CREATE_REGION = 5;
        private static final byte DESTROY_REGION = 10;
        private static final byte SET_REGION = 11;
        private static final byte SET_CURSOR_NAME = 23;
    }

    @Override
    public String getName() {
        return "XFIXES";
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

    private void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        inputStream.skip(8);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(MAJOR_VERSION);
            outputStream.writeInt(MINOR_VERSION);
            outputStream.writePad(16);
        }
    }

    /** Reads the trailing RECTANGLE list as flat x, y, width, height shorts. */
    private static int[] readRectangles(XClient client, XInputStream inputStream) throws IOException {
        int length = client.getRemainingRequestLength();
        int[] rects = new int[length / 2];

        for (int i = 0; i < rects.length; i++) rects[i] = inputStream.readShort();
        inputStream.skip(length - rects.length * 2);
        return rects;
    }

    private void createRegion(XClient client, XInputStream inputStream) throws IOException {
        int regionId = inputStream.readInt();
        int[] rects = readRectangles(client, inputStream);

        synchronized (regions) {
            regions.put(regionId, rects);
        }
    }

    private void setRegion(XClient client, XInputStream inputStream) throws IOException {
        int regionId = inputStream.readInt();
        int[] rects = readRectangles(client, inputStream);

        synchronized (regions) {
            regions.put(regionId, rects);
        }
    }

    private void destroyRegion(XClient client, XInputStream inputStream) throws IOException {
        int regionId = inputStream.readInt();

        synchronized (regions) {
            regions.remove(regionId);
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION:
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.CREATE_REGION:
                createRegion(client, inputStream);
                break;
            case ClientOpcodes.SET_REGION:
                setRegion(client, inputStream);
                break;
            case ClientOpcodes.DESTROY_REGION:
                destroyRegion(client, inputStream);
                break;
            case ClientOpcodes.SET_CURSOR_NAME:
                /* Dropped, not refused. The name only exists so a client can
                 * later reload the cursor by theme through GetCursorName or
                 * ChangeCursorByName, neither of which anything here calls, and
                 * the cursor itself is unaffected either way. Answering
                 * BadImplementation instead costs nothing at the protocol level
                 * -- the dispatcher resynchronises past the request -- but it
                 * puts an error per themed cursor into a log that is the only
                 * instrument left for the real failures.
                 */
                break;
            default:
                Log.e(TAG, "unhandled XFixes opcode " + opcode);
                throw new BadImplementation();
        }
    }
}
