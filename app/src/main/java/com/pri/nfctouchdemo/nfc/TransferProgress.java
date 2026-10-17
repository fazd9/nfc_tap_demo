package com.pri.nfctouchdemo.nfc;

import android.os.SystemClock;

//pri added by zhuyang,Byte-level transfer progress shared by the sender and the receiver,20261017,start
/**
 * One snapshot-able progress model per transfer. Xfer updates it on its worker thread and posts a
 * {@link #snapshot()} to the main thread at 4-10Hz; TapService only renders it.
 * index is 1-based (0 = no file started yet); startedAt uses elapsedRealtime.
 */
public final class TransferProgress {

    public static final int STATE_IDLE = 0;
    public static final int STATE_OFFERING = 1;
    public static final int STATE_TRANSFERRING = 2;
    public static final int STATE_DONE = 3;
    public static final int STATE_FAILED = 4;

    public int state = STATE_IDLE;
    public int index;
    public int total;
    public long totalBytes;
    public long transferredBytes;
    public double speedBps;
    public final long startedAt;
    public String error;

    private long lastSampleAt;
    private long lastSampleBytes;
    private long lastNotifyAt;

    public TransferProgress() {
        startedAt = SystemClock.elapsedRealtime();
        lastSampleAt = startedAt;
    }

    private TransferProgress(TransferProgress o) {
        state = o.state;
        index = o.index;
        total = o.total;
        totalBytes = o.totalBytes;
        transferredBytes = o.transferredBytes;
        speedBps = o.speedBps;
        startedAt = o.startedAt;
        error = o.error;
        lastSampleAt = o.lastSampleAt;
        lastSampleBytes = o.lastSampleBytes;
        lastNotifyAt = o.lastNotifyAt;
    }

    public TransferProgress snapshot() {
        return new TransferProgress(this);
    }

    //pri added by zhuyang,EWMA 0.3*new + 0.7*old, sampled every 500ms so a burst does not spike the rate,20261017
    public void addBytes(long delta) {
        if (delta <= 0) return;
        transferredBytes += delta;
        long now = SystemClock.elapsedRealtime();
        long dt = now - lastSampleAt;
        if (dt >= 500) {
            double instant = (transferredBytes - lastSampleBytes) * 1000.0 / dt;
            speedBps = speedBps <= 0 ? instant : 0.3 * instant + 0.7 * speedBps;
            lastSampleAt = now;
            lastSampleBytes = transferredBytes;
        }
    }

    public void setOffering(int fileTotal) {
        state = STATE_OFFERING;
        total = fileTotal;
    }

    public void setPlannedTotals(int fileTotal, long bytes) {
        total = fileTotal;
        totalBytes = bytes;
    }

    /** Begin one file: index is 1-based, announcedBytes may be -1 when the size is unknown. */
    public void beginFile(int oneBasedIndex, int fileTotal, long announcedBytes) {
        index = oneBasedIndex;
        if (fileTotal > 0) total = fileTotal;
        if (announcedBytes > 0) totalBytes += announcedBytes;
        state = STATE_TRANSFERRING;
    }

    public void done() {
        state = STATE_DONE;
        error = null;
        if (totalBytes > 0) transferredBytes = totalBytes;
    }

    public void fail(String why) {
        state = STATE_FAILED;
        error = why;
    }

    //pri added by zhuyang,Throttle UI updates to at most one per minIntervalMs (4Hz at 250ms),20261017
    public boolean takeNotifySlot(long minIntervalMs) {
        long now = SystemClock.elapsedRealtime();
        if (lastNotifyAt != 0 && now - lastNotifyAt < minIntervalMs) return false;
        lastNotifyAt = now;
        return true;
    }
}
//pri added by zhuyang,Byte-level transfer progress shared by the sender and the receiver,20261017,end
