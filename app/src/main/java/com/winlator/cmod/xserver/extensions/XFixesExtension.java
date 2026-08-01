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

public class XFixesExtension implements Extension {
    public static final byte MAJOR_OPCODE = -107;
    private static final String TAG = "XFixes";
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
                break;
            default:
                Log.e(TAG, "unhandled XFixes opcode " + opcode);
                throw new BadImplementation();
        }
    }
}
