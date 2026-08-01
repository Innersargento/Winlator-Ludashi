package com.winlator.cmod.xserver.extensions;

import android.util.SparseArray;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xserver.ShmFence;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.XResource;
import com.winlator.cmod.xserver.XResourceManager;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.errors.BadFence;
import com.winlator.cmod.xserver.errors.BadIdChoice;
import com.winlator.cmod.xserver.errors.BadImplementation;
import com.winlator.cmod.xserver.errors.BadMatch;
import com.winlator.cmod.xserver.errors.XRequestError;

import java.io.IOException;

public class SyncExtension implements Extension, XResourceManager.OnResourceLifecycleListener {
    public static final byte MAJOR_OPCODE = -104;
    private final SparseArray<SyncFence> fences = new SparseArray<SyncFence>();
    private XServer xserver;

    private static abstract class ClientOpcodes {
        private static final byte CREATE_FENCE = 14;
        private static final byte TRIGGER_FENCE = 15;
        private static final byte RESET_FENCE = 16;
        private static final byte DESTROY_FENCE = 17;
        private static final byte AWAIT_FENCE = 19;
    }
    
    private class SyncFence {
        int fenceId;
        int drawableId;
        /* Who to reclaim it with. A fence outlives the drawable it names -- the
         * drawable only picks the screen -- so nothing but an explicit
         * DestroyFence or this client going away may end it.
         */
        XClient owner;
        boolean triggered;
        /* Non-zero for fences created through DRI3 FenceFromFD, where the state
         * lives in a page shared with the client and this object is only a
         * handle onto it. See ShmFence.
         */
        long shmPtr;
    }

    private static boolean isTriggered(SyncFence fence) {
        return fence.shmPtr != 0 ? ShmFence.query(fence.shmPtr) : fence.triggered;
    }

    private static void trigger(SyncFence fence) {
        fence.triggered = true;
        if (fence.shmPtr != 0) ShmFence.trigger(fence.shmPtr);
    }

    private static void reset(SyncFence fence) {
        fence.triggered = false;
        if (fence.shmPtr != 0) ShmFence.reset(fence.shmPtr);
    }

    private static void destroy(SyncFence fence) {
        if (fence.shmPtr != 0) {
            ShmFence.unmap(fence.shmPtr);
            fence.shmPtr = 0;
        }
    }

    /**
     * Registers a fence whose state lives in a page shared with the client.
     * Called by DRI3 FenceFromFD, which owns the mapping until the fence dies.
     */
    public void createFenceFromFd(XClient owner, int drawableId, int fenceId,
                                  boolean initiallyTriggered, long shmPtr) throws XRequestError {
        synchronized (fences) {
            if (fences.indexOfKey(fenceId) >= 0) throw new BadIdChoice(fenceId);

            SyncFence fence = new SyncFence();
            fence.owner = owner;
            fence.drawableId = drawableId;
            fence.fenceId = fenceId;
            fence.shmPtr = shmPtr;
            fence.triggered = initiallyTriggered;
            if (initiallyTriggered) ShmFence.trigger(shmPtr);
            fences.put(fenceId, fence);

            android.util.Log.d("Sync", "registered shm fence 0x" + Integer.toHexString(fenceId)
                                       + " for drawable 0x" + Integer.toHexString(drawableId)
                                       + (initiallyTriggered ? " (triggered)" : ""));
        }
    }
    
    public SyncExtension(XServer xserver) {
        this.xserver = xserver;
        this.xserver.pixmapManager.addOnResourceLifecycleListener(this);
        this.xserver.windowManager.addOnResourceLifecycleListener(this);
    }

    @Override
    public String getName() {
        return "SYNC";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return Byte.MIN_VALUE;
    }

    @Override
    public byte getFirstEventId() {
        return 0;
    }

    public void setTriggered(int id) {
        synchronized (fences) {
            if (fences.indexOfKey(id) >= 0) {
                trigger(fences.get(id));
            }
            else {
                /* Doing nothing here is indistinguishable from succeeding, and
                 * the client is meanwhile parked in xshmfence_await() waiting
                 * for this exact fence. Never let it pass in silence.
                 */
                android.util.Log.w("Sync", "setTriggered: no fence 0x"
                                           + Integer.toHexString(id)
                                           + "; a client may be stuck waiting on it");
            }
        }
    }

    private void createFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int drawableId = inputStream.readInt();
            int id = inputStream.readInt();

            if (fences.indexOfKey(id) >= 0) throw new BadIdChoice(id);

            boolean initiallyTriggered = inputStream.readByte() == 1;
            inputStream.skip(3);
            
            SyncFence fence = new SyncFence();
            fence.owner = client;
            fence.drawableId = drawableId;
            fence.fenceId = id;
            fence.triggered = initiallyTriggered;
            fences.put(id, fence);
        }
    }

    private void triggerFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int id = inputStream.readInt();
            if (fences.indexOfKey(id) < 0) throw new BadFence(id);
            trigger(fences.get(id));
        }
    }

    private void resetFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int id = inputStream.readInt();
            if (fences.indexOfKey(id) < 0) throw new BadFence(id);

            SyncFence fence = fences.get(id);
            if (!isTriggered(fence)) throw new BadMatch();

            reset(fence);
        }
    }

    private void destroyFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int id = inputStream.readInt();
            if (fences.indexOfKey(id) < 0) throw new BadFence(id);
            destroy(fences.get(id));
            fences.delete(id);
        }
    }

    private void awaitFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int length = client.getRemainingRequestLength();
            int[] ids = new int[length / 4];
            int i = 0;

            while (length != 0) {
                ids[i++] = inputStream.readInt();
                length -= 4;
            }

            boolean anyTriggered = false;
            do {
                for (int id : ids) {
                    if (fences.indexOfKey(id) < 0) throw new BadFence(id);
                    anyTriggered = isTriggered(fences.get(id));
                    if (anyTriggered) break;
                }
            }
            while (!anyTriggered);
        }
    }
    
    /**
     * Reclaims what a departing client never destroyed itself. Fences are not
     * XResources, so {@link XClient#freeResources} does not reach them.
     */
    public void freeFencesOwnedBy(XClient client) {
        synchronized (fences) {
            for (int i = fences.size() - 1; i >= 0; i--) {
                SyncFence fence = fences.valueAt(i);
                if (fence.owner == client) {
                    destroy(fence);
                    fences.removeAt(i);
                }
            }
        }
    }

    @Override
    public void onFreeResource(XResource resource) {
        /* Deliberately empty. Freeing a drawable used to destroy every fence
         * created from it, but a fence's lifetime is its own -- the drawable
         * only selects the screen. loader_dri3 frees the pixmap first and then
         * destroys the fence, so reclaiming here answered its DestroyFence
         * with BadFence, and a client that legitimately kept using the fence
         * would have found it silently dead. Fences now go on DestroyFence or
         * with their owner, via freeFencesOwnedBy().
         */
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.CREATE_FENCE :
                createFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.TRIGGER_FENCE:
                triggerFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.RESET_FENCE:
                resetFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.DESTROY_FENCE:
                destroyFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.AWAIT_FENCE:
                awaitFence(client, inputStream, outputStream);
                break;
            default:
                throw new BadImplementation();
        }
    }
}
