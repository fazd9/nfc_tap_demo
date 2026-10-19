package com.pri.nfctouchdemo.nfc;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

//pri added by zhuyang,Trusted first-frame protocol: shared frame I/O and HELLO handshake,20261017,start
public final class Proto {

    public static final int MAGIC = 0x4E464331;
    public static final int TYPE_PREVIEW = 1;
    public static final int TYPE_FULL = 2;
    public static final int TYPE_META = 3;
    public static final int TYPE_ACCEPT = 4;
    public static final int TYPE_REJECT = 5;
    public static final int TYPE_HELLO = 6;
    public static final int TYPE_HELLO_ACK = 7;
    public static final int TYPE_ROLE = 8; //pri added by zhuyang,Role convergence frame: r=<U|S|R>|sid=<sender sid>,20261017
    //pri added by zhuyang,Application heartbeat frames: empty payload,20261017,start
    public static final int TYPE_PING = 9;
    public static final int TYPE_PONG = 10;
    //pri added by zhuyang,Application heartbeat frames: empty payload,20261017,end
    //pri added by zhuyang,Chunked delivery: per-file metadata, sequenced data chunks and an explicit completion handshake,20261017,start
    public static final int TYPE_FILE_BEGIN = 11;
    public static final int TYPE_DATA = 12;
    public static final int TYPE_DONE = 13;
    public static final int TYPE_DONE_ACK = 14;
    public static final int TYPE_CANCEL = 15;
    public static final int TYPE_META_INFO = 16; //pri added by zhuyang,Batch 4: total byte count sent right after META for the storage precheck,20261017
    public static final int TYPE_SKIP = 17; //pri added by zhuyang,Receiver already has this content hash: payload [4B index], no DATA follows,20261019
    //pri added by zhuyang,Chunked delivery: per-file metadata, sequenced data chunks and an explicit completion handshake,20261017,end

    public static final int MAX_FRAME_LEN = 64 * 1024 * 1024;
    public static final int HELLO_VERSION = 1;
    public static final int HELLO_TIMEOUT_MS = 1500;
    public static final int MAX_HELLO_LEN = 1024;
    public static final int MAX_ROLE_LEN = 256; //pri added by zhuyang,ROLE payload is a short text line,20261017
    //pri added by zhuyang,Per-type frame caps: an oversized LEN is rejected before any array is allocated,20261017,start
    public static final int MAX_DATA_LEN = 1024 * 1024 + 4;
    public static final int MAX_FILE_BEGIN_LEN = 4 * 1024;
    public static final int MAX_DONE_LEN = 64;
    public static final int MAX_CANCEL_LEN = 1024;
    public static final int MAX_META_INFO_LEN = 128; //pri added by zhuyang,Batch 4: "n=<total>|b=<totalBytes>" fits easily,20261017
    public static final int MAX_SKIP_LEN = 4; //pri added by zhuyang,TYPE_SKIP carries one big-endian image index,20261019
    //pri added by zhuyang,Per-type frame caps: an oversized LEN is rejected before any array is allocated,20261017,end

    public static final class Frame {
        public final int type;
        public final int length;
        public final byte[] data;

        Frame(int type, int length, byte[] data) {
            this.type = type;
            this.length = length;
            this.data = data;
        }
    }

    public static final class Hello {
        public final int version;
        public final String sid;
        public final String role;
        public final boolean hasPhotos;
        //pri modify by zhuyang,Kept for HELLO format compatibility only: the sender no longer uses it to skip,20261019
        public final String have;

        Hello(int version, String sid, String role, boolean hasPhotos, String have) {
            this.version = version;
            this.sid = sid;
            this.role = role;
            this.hasPhotos = hasPhotos;
            this.have = have;
        }
    }

    //pri added by zhuyang,Decoded ROLE frame used by the role arbitration,20261017,start
    public static final class Role {
        public final String role;
        public final String sid;

        Role(String role, String sid) {
            this.role = role;
            this.sid = sid;
        }
    }
    //pri added by zhuyang,Decoded ROLE frame used by the role arbitration,20261017,end

    //pri added by zhuyang,Decoded FILE_BEGIN and DONE payloads,20261017,start
    public static final class FileBegin {
        public final int index;
        public final int total;
        public final long size;
        public final String mime;
        public final String sha256; //pri added by zhuyang,Batch 4: lowercase SHA-256 of the image bytes (null = old peer, skip the check),20261017

        FileBegin(int index, int total, long size, String mime, String sha256) {
            this.index = index;
            this.total = total;
            this.size = size;
            this.mime = mime;
            this.sha256 = sha256;
        }
    }

    public static final class Done {
        public final int count;
        public final long totalBytes;

        Done(int count, long totalBytes) {
            this.count = count;
            this.totalBytes = totalBytes;
        }
    }

    //pri added by zhuyang,Batch 4: META_INFO payload used for the ACCEPT-time storage precheck,20261017
    public static final class MetaInfo {
        public final int total;
        public final long totalBytes;

        MetaInfo(int total, long totalBytes) {
            this.total = total;
            this.totalBytes = totalBytes;
        }
    }
    //pri added by zhuyang,Decoded FILE_BEGIN and DONE payloads,20261017,end

    private Proto() {}

    public static boolean isLengthOnly(int type) {
        return type == TYPE_META || type == TYPE_ACCEPT || type == TYPE_REJECT
                || type == TYPE_PING || type == TYPE_PONG; //pri added by zhuyang,Heartbeat frames carry no payload,20261017
    }

    //pri added by zhuyang,Type-level payload cap: data frames are the only ones allowed to be large,20261017,start
    public static int maxPayloadFor(int type) {
        switch (type) {
            case TYPE_FILE_BEGIN: return MAX_FILE_BEGIN_LEN;
            case TYPE_DATA: return MAX_DATA_LEN;
            case TYPE_DONE:
            case TYPE_DONE_ACK: return MAX_DONE_LEN;
            case TYPE_CANCEL: return MAX_CANCEL_LEN;
            case TYPE_META_INFO: return MAX_META_INFO_LEN; //pri added by zhuyang,Batch 4: small text payload,20261017
            case TYPE_SKIP: return MAX_SKIP_LEN; //pri added by zhuyang,TYPE_SKIP is exactly one index,20261019
            case TYPE_ROLE: return MAX_ROLE_LEN;
            case TYPE_HELLO:
            case TYPE_HELLO_ACK: return MAX_HELLO_LEN;
            default: return MAX_FRAME_LEN;
        }
    }
    //pri added by zhuyang,Type-level payload cap: data frames are the only ones allowed to be large,20261017,end

    public static Frame readFrame(DataInputStream in, int timeoutMs, int maxLen) throws IOException {
        long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : 0L;
        byte[] header = new byte[12];
        if (!readFully(in, header, 0, header.length, deadline)) return null;
        int magic = ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16)
                | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        int type = ((header[4] & 0xFF) << 24) | ((header[5] & 0xFF) << 16)
                | ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
        int len = ((header[8] & 0xFF) << 24) | ((header[9] & 0xFF) << 16)
                | ((header[10] & 0xFF) << 8) | (header[11] & 0xFF);
        if (magic != MAGIC) {
            throw new IOException("bad frame magic 0x" + Integer.toHexString(magic));
        }
        //pri added by zhuyang,Reject an oversized LEN by type before allocating the payload,20261017
        int cap = Math.min(maxLen, maxPayloadFor(type));
        if (len < 0 || len > cap) {
            throw new IOException("frame length out of range: type=" + type + " len=" + len + " > " + cap);
        }
        byte[] data;
        if (isLengthOnly(type)) {
            data = new byte[0];
        } else {
            data = new byte[len];
            if (!readFully(in, data, 0, len, deadline)) {
                throw new EOFException("truncated frame payload");
            }
        }
        return new Frame(type, len, data);
    }

    public static void writeFrame(DataOutputStream out, int type, int len, byte[] data)
            throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(type);
        out.writeInt(data == null ? len : data.length);
        if (data != null) out.write(data);
    }

    public static byte[] buildHello(String sid, String role, boolean hasPhotos) {
        return buildHello(sid, role, hasPhotos, null);
    }

    //pri modify by zhuyang,Batch 4: optional have= is still encoded/decoded for old peers but is no longer advisory,20261019
    public static byte[] buildHello(String sid, String role, boolean hasPhotos, String have) {
        String s = "v=" + HELLO_VERSION + "|sid=" + sid + "|r=" + role
                + "|hp=" + (hasPhotos ? "1" : "0");
        if (have != null && !have.isEmpty()) s += "|have=" + have;
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] buildHelloAck(String sid, String role) {
        return buildHelloAck(sid, role, null);
    }

    //pri modify by zhuyang,Batch 4: optional have= is still encoded/decoded for old peers but is no longer advisory,20261019
    public static byte[] buildHelloAck(String sid, String role, String have) {
        String s = "v=" + HELLO_VERSION + "|sid=" + sid + "|r=" + role;
        if (have != null && !have.isEmpty()) s += "|have=" + have;
        return s.getBytes(StandardCharsets.UTF_8);
    }

    //pri added by zhuyang,Build/parse the ROLE frame that follows HELLO_ACK on every authenticated link,20261017,start
    public static byte[] buildRole(String role, String sid) {
        String s = "r=" + role + "|sid=" + sid;
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static Role parseRole(byte[] data) {
        if (data == null) return null;
        String role = null;
        String sid = null;
        String text = new String(data, StandardCharsets.UTF_8);
        for (String kv : text.split("\\|")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            String k = kv.substring(0, eq);
            String v = kv.substring(eq + 1);
            if ("r".equals(k)) {
                role = v;
            } else if ("sid".equals(k)) {
                sid = v;
            }
        }
        if (!isRole(role) || !isHex32(sid)) return null;
        return new Role(role, sid);
    }
    //pri added by zhuyang,Build/parse the ROLE frame that follows HELLO_ACK on every authenticated link,20261017,end

    public static Hello parseHello(byte[] data) {
        if (data == null) return null;
        int version = 0;
        String sid = null;
        String role = null;
        boolean hp = false;
        String have = null;
        String text = new String(data, StandardCharsets.UTF_8);
        for (String kv : text.split("\\|")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            String k = kv.substring(0, eq);
            String v = kv.substring(eq + 1);
            if ("v".equals(k)) {
                try {
                    version = Integer.parseInt(v);
                } catch (NumberFormatException e) {
                    return null;
                }
            } else if ("sid".equals(k)) {
                sid = v;
            } else if ("r".equals(k)) {
                role = v;
            } else if ("hp".equals(k)) {
                hp = "1".equals(v);
            } else if ("have".equals(k)) {
                //pri modify by zhuyang,Batch 4: parse-only compatibility; the receiver no longer emits it and the sender ignores it,20261019
                have = normalizeBitmap(v);
                if (have == null) Bus.log("传输: HELLO have= 位图解析失败,按空处理");
            }
        }
        if (version <= 0 || !isHex32(sid) || !isRole(role)) return null;
        return new Hello(version, sid, role, hp, have);
    }

    //pri added by zhuyang,Batch 4: image-index bitmap codec kept only for HELLO format compatibility; resume now uses sha256,20261019,start
    public static String normalizeBitmap(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || s.length() > 16) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < 16; i++) sb.append('0');
        for (int i = 0; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return null;
        }
        sb.append(s.toLowerCase(java.util.Locale.US));
        return sb.toString();
    }

    public static boolean bitmapHas(String have, int index) {
        if (have == null || index < 0 || index >= 64) return false;
        int pos = 15 - (index / 4);
        if (pos < 0 || pos >= have.length()) return false;
        int nibble = Character.digit(have.charAt(pos), 16);
        if (nibble < 0) return false;
        return ((nibble >> ((index % 4) * 4)) & 1) != 0;
    }

    public static int bitmapCount(String have, int limit) {
        if (have == null) return 0;
        int max = Math.min(limit, 64);
        int n = 0;
        for (int i = 0; i < max; i++) if (bitmapHas(have, i)) n++;
        return n;
    }
    //pri added by zhuyang,Batch 4: image-index bitmap codec kept only for HELLO format compatibility; resume now uses sha256,20261019,end

    //pri added by zhuyang,FILE_BEGIN / DONE / DONE_ACK / CANCEL codecs for the chunked delivery,20261017,start
    public static byte[] buildFileBegin(int index, int total, long size, String mime) {
        return buildFileBegin(index, total, size, mime, null);
    }

    //pri added by zhuyang,Batch 4: append h=<sha256hex>; omitted for an unknown hash,20261017
    public static byte[] buildFileBegin(int index, int total, long size, String mime, String sha256) {
        String s = "i=" + index + "|n=" + total + "|s=" + size + "|m=" + (mime == null ? "" : mime);
        if (isHex64(sha256)) s += "|h=" + sha256.toLowerCase(java.util.Locale.US);
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static FileBegin parseFileBegin(byte[] data) {
        if (data == null) return null;
        int index = -1;
        int total = -1;
        long size = -1;
        String mime = null;
        String sha = null;
        String text = new String(data, StandardCharsets.UTF_8);
        for (String kv : text.split("\\|")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            String k = kv.substring(0, eq);
            String v = kv.substring(eq + 1);
            try {
                if ("i".equals(k)) {
                    index = Integer.parseInt(v);
                } else if ("n".equals(k)) {
                    total = Integer.parseInt(v);
                } else if ("s".equals(k)) {
                    size = Long.parseLong(v);
                } else if ("m".equals(k)) {
                    mime = v;
                } else if ("h".equals(k)) {
                    sha = isHex64(v) ? v.toLowerCase(java.util.Locale.US) : null; //pri added by zhuyang,Batch 4: malformed hash is skipped,20261017
                }
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (index < 0 || total <= 0 || index >= total || size < -1) return null;
        return new FileBegin(index, total, size, mime, sha);
    }

    //pri added by zhuyang,Batch 4: META_INFO "n=<total>|b=<totalBytes>", sent right after META,20261017,start
    public static byte[] buildMetaInfo(int total, long totalBytes) {
        String s = "n=" + total + "|b=" + totalBytes;
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static MetaInfo parseMetaInfo(byte[] data) {
        if (data == null) return null;
        int total = -1;
        long bytes = -1;
        String text = new String(data, StandardCharsets.UTF_8);
        for (String kv : text.split("\\|")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            String k = kv.substring(0, eq);
            String v = kv.substring(eq + 1);
            try {
                if ("n".equals(k)) {
                    total = Integer.parseInt(v);
                } else if ("b".equals(k)) {
                    bytes = Long.parseLong(v);
                }
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (total < 0 || bytes < 0) return null;
        return new MetaInfo(total, bytes);
    }
    //pri added by zhuyang,Batch 4: META_INFO "n=<total>|b=<totalBytes>", sent right after META,20261017,end

    public static int readSeq(byte[] data) {
        return ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16)
                | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
    }

    //pri added by zhuyang,TYPE_SKIP: receiver already completed this sha256, so the sender must not stream DATA,20261019,start
    public static byte[] buildSkip(int index) {
        byte[] b = new byte[4];
        b[0] = (byte) (index >>> 24);
        b[1] = (byte) (index >>> 16);
        b[2] = (byte) (index >>> 8);
        b[3] = (byte) index;
        return b;
    }

    public static int parseSkip(byte[] data) {
        if (data == null || data.length != 4) return -1;
        return readSeq(data);
    }
    //pri added by zhuyang,TYPE_SKIP: receiver already completed this sha256, so the sender must not stream DATA,20261019,end

    public static byte[] buildDone(int count, long totalBytes) {
        byte[] b = new byte[12];
        b[0] = (byte) (count >>> 24);
        b[1] = (byte) (count >>> 16);
        b[2] = (byte) (count >>> 8);
        b[3] = (byte) count;
        for (int i = 0; i < 8; i++) {
            b[4 + i] = (byte) (totalBytes >>> (56 - 8 * i));
        }
        return b;
    }

    public static Done parseDone(byte[] data) {
        if (data == null || data.length != 12) return null;
        int count = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16)
                | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        long total = 0;
        for (int i = 0; i < 8; i++) {
            total = (total << 8) | (data[4 + i] & 0xFFL);
        }
        return new Done(count, total);
    }

    public static byte[] buildDoneAck(int code) {
        byte[] b = new byte[4];
        b[0] = (byte) (code >>> 24);
        b[1] = (byte) (code >>> 16);
        b[2] = (byte) (code >>> 8);
        b[3] = (byte) code;
        return b;
    }

    public static int parseDoneAck(byte[] data) {
        if (data == null || data.length != 4) return -1;
        return ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16)
                | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
    }

    public static byte[] buildCancel(String reason) {
        String s = reason == null ? "" : reason;
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= MAX_CANCEL_LEN) return b;
        byte[] cut = new byte[MAX_CANCEL_LEN];
        System.arraycopy(b, 0, cut, 0, MAX_CANCEL_LEN);
        return cut;
    }
    //pri added by zhuyang,FILE_BEGIN / DONE / DONE_ACK / CANCEL codecs for the chunked delivery,20261017,end

    private static boolean isRole(String role) {
        return role != null && role.length() == 1
                && (role.charAt(0) == 'U' || role.charAt(0) == 'S' || role.charAt(0) == 'R');
    }

    private static boolean isHex32(String s) {
        if (s == null || s.length() != 32) return false;
        for (int i = 0; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return false;
        }
        return true;
    }

    //pri added by zhuyang,Batch 4: SHA-256 hex must be exactly 64 hex chars,20261017
    private static boolean isHex64(String s) {
        if (s == null || s.length() != 64) return false;
        for (int i = 0; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return false;
        }
        return true;
    }

    //pri added by zhuyang,Blocking reads let EOF surface at once; timed callers set SO_TIMEOUT to the same value,20261017
    private static boolean readFully(DataInputStream in, byte[] buf, int off, int len, long deadline)
            throws IOException {
        int done = 0;
        while (done < len) {
            int n = in.read(buf, off + done, len - done);
            if (n < 0) {
                if (done == 0) return false;
                throw new EOFException("frame truncated");
            }
            done += n;
            if (deadline > 0 && System.currentTimeMillis() >= deadline) {
                throw new SocketTimeoutException("frame read timeout");
            }
        }
        return true;
    }
}
//pri added by zhuyang,Trusted first-frame protocol: shared frame I/O and HELLO handshake,20261017,end
