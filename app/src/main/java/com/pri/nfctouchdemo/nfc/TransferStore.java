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

//pri modify by zhuyang,Batch 4: per-session incoming temp files plus a content-hash completion set,20261019,start
/**
 * Receive-side scratch space. Every image is written to getCacheDir()/incoming/<sid>/<index>.part
 * and only inserted into MediaStore after its size and SHA-256 were verified. Verified content is
 * kept in getCacheDir()/incoming/completed/<sha256> and recorded in an in-memory sha256 -> file map,
 * so a later round can skip the network transfer of identical content regardless of its index.
 */
public final class TransferStore {

    private final File mRoot;
    private final File mCompletedDir;
    //pri modify by zhuyang,Resume is decided by content hash (cross-round), never by sid/index,20261019
    private final ConcurrentHashMap<String, File> mCompletedHashes =
            new ConcurrentHashMap<String, File>();

    public TransferStore(Context ctx) {
        mRoot = new File(ctx.getCacheDir(), "incoming");
        mCompletedDir = new File(mRoot, "completed");
        loadCompleted();
    }

    //pri added by zhuyang,Batch 4: restore the sha256 set from cache/incoming/completed so dedupe survives a process restart,20261019
    private void loadCompleted() {
        File[] files = mCompletedDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (f.isFile() && isHex64(name)) {
                mCompletedHashes.put(name.toLowerCase(Locale.US), f);
            }
        }
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

    public void discard(String sid, int index) {
        try { partFile(sid, index).delete(); } catch (Throwable ignored) {}
    }

    //pri modify by zhuyang,Completed content lives outside the sid dir so discardAll never removes it,20261019
    /** Delete every .part of this sid only; completed content files are kept for cross-round dedupe. */
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

    //pri added by zhuyang,Content-hash completion set: only verified files that reached MediaStore are added,20261019,start
    public File completedFile(String sha256) {
        if (sha256 == null) return null;
        String key = sha256.toLowerCase(Locale.US);
        File f = mCompletedHashes.get(key);
        if (f == null) return null;
        if (!f.exists()) {
            mCompletedHashes.remove(key, f);
            return null;
        }
        return f;
    }

    public void markCompleted(String sha256, File file) {
        if (sha256 == null || file == null) return;
        mCompletedHashes.put(sha256.toLowerCase(Locale.US), file);
    }

    /** Move a verified .part into the persistent completed store; the caller then saves it to MediaStore. */
    public File persistCompleted(String sha256, File source) throws IOException {
        if (sha256 == null || !isHex64(sha256)) return null;
        if (!mCompletedDir.exists() && !mCompletedDir.mkdirs() && !mCompletedDir.exists()) {
            throw new IOException("cannot create completed dir");
        }
        File dst = new File(mCompletedDir, sha256.toLowerCase(Locale.US));
        if (dst.getAbsoluteFile().equals(source.getAbsoluteFile())) return dst;
        if (dst.exists() && dst.length() == source.length()) {
            try { source.delete(); } catch (Throwable ignored) {}
            return dst;
        }
        if (dst.exists()) {
            try { dst.delete(); } catch (Throwable ignored) {}
        }
        if (!source.renameTo(dst)) {
            copyFile(source, dst);
            try { source.delete(); } catch (Throwable ignored) {}
        }
        return dst;
    }

    private static void copyFile(File src, File dst) throws IOException {
        InputStream in = new FileInputStream(src);
        OutputStream out = null;
        try {
            out = new BufferedOutputStream(new FileOutputStream(dst, false), 64 * 1024);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally {
            try { in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
        }
    }

    private static boolean isHex64(String s) {
        if (s == null || s.length() != 64) return false;
        for (int i = 0; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return false;
        }
        return true;
    }
    //pri added by zhuyang,Content-hash completion set: only verified files that reached MediaStore are added,20261019,end

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
//pri modify by zhuyang,Batch 4: per-session incoming temp files plus a content-hash completion set,20261019,end
