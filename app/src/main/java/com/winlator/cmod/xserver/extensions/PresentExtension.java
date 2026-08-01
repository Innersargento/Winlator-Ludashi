package com.winlator.cmod.xserver.extensions;

import android.util.Log;
import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.util.SparseArray;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Pixmap;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XResource;
import com.winlator.cmod.xserver.XResourceManager;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.errors.BadImplementation;
import com.winlator.cmod.xserver.errors.BadMatch;
import com.winlator.cmod.xserver.errors.BadPixmap;
import com.winlator.cmod.xserver.errors.BadWindow;
import com.winlator.cmod.xserver.errors.XRequestError;
import com.winlator.cmod.xserver.events.PresentCompleteNotify;
import com.winlator.cmod.xserver.events.PresentIdleNotify;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PresentExtension implements Extension, XResourceManager.OnResourceLifecycleListener {
    public static final byte MAJOR_OPCODE = -103;
    /* A target further out than this is a client bug, not a wait anyone means
     * to sit through; completing it late beats parking a task forever.
     */
    private static final long MAX_WAIT_FRAMES = 300;
    public enum Kind {PIXMAP, MSC_NOTIFY}
    public enum Mode {COPY, FLIP, SKIP}
    private final SparseArray<Event> events = new SparseArray<>();
    /* NotifyMSC is the one request here that finishes later than it arrives.
     * It cannot wait on the X server's request thread -- that thread is the
     * whole server -- so the completion is handed to a timer. Daemon, so a
     * pending wait never holds the process open.
     */
    private final ScheduledExecutorService mscScheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "present-msc");
                thread.setDaemon(true);
                return thread;
            });
    private SyncExtension syncExtension;
    private XServer xServer;

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte PRESENT_PIXMAP = 1;
        private static final byte NOTIFY_MSC = 2;
        private static final byte SELECT_INPUT = 3;
        private static final byte QUERY_CAPABILITIES = 4;
    }

    private static class Event {
        private Window window;
        private XClient client;
        private int id;
        private Bitmask mask;
    }
    
    public PresentExtension(XServer xserver) {
        this.xServer = xserver;
        this.xServer.windowManager.addOnResourceLifecycleListener(this);
    }

    @Override
    public String getName() {
        return "Present";
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

    private void sendIdleNotify(Window window, Pixmap pixmap, int serial, int idleFence) {
        if (idleFence != 0) syncExtension.setTriggered(idleFence);

        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                Event event = events.valueAt(i);
                if (event.window == window && event.mask.isSet(PresentIdleNotify.getEventMask())) {
                    event.client.sendEvent(new PresentIdleNotify(event.id, window, pixmap, serial, idleFence));
                }
            }
        }
    }

    private void sendCompleteNotify(Window window, int serial, Kind kind, Mode mode, long ust, long msc) {
        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                Event event = events.valueAt(i);
                if (event.window == window && event.mask.isSet(PresentCompleteNotify.getEventMask())) {
                    event.client.sendEvent(new PresentCompleteNotify(event.id, window, serial, kind, mode, ust, msc));
                }
            }
        }
    }

    private static void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(8);

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

    private static void queryCapabilities(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.readInt();

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(0);
            outputStream.writePad(20);
        }
    }

    private void presentPixmap(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        int pixmapId = inputStream.readInt();
        int serial = inputStream.readInt();
        inputStream.skip(8);
        short xOff = inputStream.readShort();
        short yOff = inputStream.readShort();
        inputStream.skip(8);
        int idleFence = inputStream.readInt();
        inputStream.skip(client.getRemainingRequestLength());

        final Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        final Pixmap pixmap = client.xServer.pixmapManager.getPixmap(pixmapId);
        if (pixmap == null) throw new BadPixmap(pixmapId);

        long ust = ust();
        long msc = ust / frameInterval();

        pixmap.drawable.updateDirect();
        sendIdleNotify(window, pixmap, serial, idleFence);
        sendCompleteNotify(window, serial, Kind.PIXMAP, Mode.COPY, ust, msc);
    }

    /**
     * There is no CRTC behind any of this, so the MSC is derived from the clock
     * at the display's refresh rate: frame n began at n * frameInterval().
     */
    private static long ust() {
        return System.nanoTime() / 1000;
    }

    private long frameInterval() {
        return (long)(1000000.0f / xServer.getRefreshRate());
    }

    /**
     * "Tell me when the frame counter reaches n", the request loader_dri3 uses
     * once vblank_mode puts it on the MSC path. Answering it with
     * BadImplementation is not a graceful degradation: the client is waiting on
     * a reply that turns into a protocol error in the middle of an unrelated
     * GLX call, and Mesa takes that badly.
     *
     * The wait cannot happen here -- this is the server's only request thread --
     * so a satisfied condition completes inline and everything else goes to the
     * timer.
     */
    private void notifyMSC(XClient client, XInputStream inputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        int serial = inputStream.readInt();
        /* CARD64 has to start 8-byte aligned, so there is a pad here that the
         * protocol document does not draw but the wire format has.
         */
        inputStream.skip(4);
        long targetMsc = inputStream.readLong();
        long divisor = inputStream.readLong();
        long remainder = inputStream.readLong();

        final Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        long interval = frameInterval();
        long ust = ust();
        long currentMsc = ust / interval;

        /* Both counters are CARD64, so every comparison against them is
         * unsigned -- a client is free to hand us a target with the top bit set.
         */
        if (divisor != 0) {
            targetMsc = currentMsc - Long.remainderUnsigned(currentMsc, divisor) + remainder;
            if (Long.compareUnsigned(targetMsc, currentMsc) <= 0) targetMsc += divisor;
        }

        if (Long.compareUnsigned(targetMsc, currentMsc) <= 0) {
            sendCompleteNotify(window, serial, Kind.MSC_NOTIFY, Mode.COPY, ust, currentMsc);
            return;
        }

        long framesAhead = targetMsc - currentMsc;
        if (Long.compareUnsigned(framesAhead, MAX_WAIT_FRAMES) > 0) framesAhead = MAX_WAIT_FRAMES;

        final long completionMsc = currentMsc + framesAhead;
        mscScheduler.schedule(
                () -> sendCompleteNotify(window, serial, Kind.MSC_NOTIFY, Mode.COPY,
                                         completionMsc * interval, completionMsc),
                framesAhead * interval, TimeUnit.MICROSECONDS);
    }

    private void selectInput(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int eventId = inputStream.readInt();
        int windowId = inputStream.readInt();
        Bitmask mask = new Bitmask(inputStream.readInt());

        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);


        synchronized (events) {
            Event event = events.get(eventId);
            if (event != null) {
                if (event.window != window || event.client != client) throw new BadMatch();

                if (!mask.isEmpty()) {
                    event.mask = mask;
                }
                else events.remove(eventId);
            }
            else {
                event = new Event();
                event.id = eventId;
                event.window = window;
                event.client = client;
                event.mask = mask;
                events.put(eventId, event);
            }
        }
    }
    
    
    @Override
    public void onFreeResource(XResource resource) {
        if (resource instanceof Window) {
            Window window = (Window) resource;
            for (int i = 0; i < events.size(); i++) {
                int key = events.keyAt(i);
                Event event = events.get(key);
                if (event.window == window)
                    events.remove(event.id);
            }
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        if (syncExtension == null) syncExtension = client.xServer.getExtension(SyncExtension.MAJOR_OPCODE);

        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION :
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.PRESENT_PIXMAP:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER)) {
                    presentPixmap(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.NOTIFY_MSC:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                    notifyMSC(client, inputStream);
                }
                break;
            case ClientOpcodes.SELECT_INPUT:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                    selectInput(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.QUERY_CAPABILITIES:
                queryCapabilities(client, inputStream, outputStream);
                break;
            default:
                Log.e("Present", "unhandled Present opcode " + opcode);
                throw new BadImplementation();
        }
    }
}
