package com.pri.nfctouchdemo.nfc;

import android.content.Context;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

//pri added by zhuyang,Batch 4: per-session incoming temp files plus the image-level resume bitmap,20261017,start
/**
 * Receive-side scratch space. Every image is written to getCacheDir()/incoming/<sid>/<index>.part
 * and only inserted into MediaStore after its size and SHA-256 were verified. The completed bitmap
 * (bit i = image i verified) is kept in memory per sid and advertised in HELLO so the sender can
 * skip those indices on the next connection. Only the current sid's directory is ever touched.
 */
public final class TransferStore {

    private final File mRoot;
    // sid -> 64-bit completion bitmap (index >= 64 cannot be represented, as agreed for batch 4)
    private final ConcurrentHashMap<String, Long> mCompleted = new ConcurrentHashMap<String, Long>();

    public TransferStore(Context ctx) {
        mRoot = new File(ctx.getCacheDir(), "incoming");
    }

    //pri added by zhuyang,Keep the sid out of path traversal: only hex characters survive,20261017
    public static String sanitizeSid(String sid) {
        if (sid == null || sid.isEmpty()) return "nosid";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sid.length() && sb.length() < 32; i++) {
            char c = sid.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    public File partFile(String sid, int index) {
        return new File(new File(mRoot, sanitizeSid(sid)), index + ".part");
    }

    /** Create the directory on demand and truncate any stale file of the same index. */
    public OutputStream open(String sid, int index) throws IOException {
        File f = partFile(sid, index);
        File dir = f.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw new IOException("cannot create incoming dir");
        }
        return new BufferedOutputStream(new FileOutputStream(f, false), 64 * 1024);
    }

    /** Verification passed: the bitmap is updated and the staged file is handed to the caller. */
    public File commit(String sid, int index) {
        markComplete(sid, index);
        return partFile(sid, index);
    }

    public void discard(String sid, int index) {
        try { partFile(sid, index).delete(); } catch (Throwable ignored) {}
    }

    /** Delete every .part of this sid only; completed indices stay in the in-memory bitmap. */
    public void discardAll(String sid) {
        File dir = new File(mRoot, sanitizeSid(sid));
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                try { f.delete(); } catch (Throwable ignored) {}
            }
        }
        try { dir.delete(); } catch (Throwable ignored) {}
    }

    //pri added by zhuyang,Image-level resume bitmap shared with the sender through HELLO have=,20261017,start
    public void markComplete(String sid, int index) {
        if (index < 0 || index >= 64) return;
        String key = sanitizeSid(sid);
        long bit = 1L << index;
        synchronized (mCompleted) {
            Long old = mCompleted.get(key);
            mCompleted.put(key, (old == null ? 0L : old) | bit);
        }
    }

    public String haveHex(String sid) {
        Long v = mCompleted.get(sanitizeSid(sid));
        return String.format(Locale.US, "%016x", v == null ? 0L : v);
    }
    //pri added by zhuyang,Image-level resume bitmap shared with the sender through HELLO have=,20261017,end

    //pri added by zhuyang,One local read to check a pre-existing .part against FILE_BEGIN h=,20261017
    public static String sha256Of(File f) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("SHA-256 unavailable");
        }
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
        return hex(md.digest());
    }

    public static String hex(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format(Locale.US, "%02x", b & 0xFF));
        return sb.toString();
    }
}
//pri added by zhuyang,Batch 4: per-session incoming temp files plus the image-level resume bitmap,20261017,end
