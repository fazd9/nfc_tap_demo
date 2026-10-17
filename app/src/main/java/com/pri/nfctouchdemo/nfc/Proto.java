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

    public static final int MAX_FRAME_LEN = 64 * 1024 * 1024;
    public static final int HELLO_VERSION = 1;
    public static final int HELLO_TIMEOUT_MS = 1500;
    public static final int MAX_HELLO_LEN = 1024;

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

        Hello(int version, String sid, String role, boolean hasPhotos) {
            this.version = version;
            this.sid = sid;
            this.role = role;
            this.hasPhotos = hasPhotos;
        }
    }

    private Proto() {}

    public static boolean isLengthOnly(int type) {
        return type == TYPE_META || type == TYPE_ACCEPT || type == TYPE_REJECT;
    }

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
        if (len < 0 || len > maxLen) {
            throw new IOException("frame length out of range: " + len + " > " + maxLen);
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
        String s = "v=" + HELLO_VERSION + "|sid=" + sid + "|r=" + role
                + "|hp=" + (hasPhotos ? "1" : "0");
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] buildHelloAck(String sid, String role) {
        String s = "v=" + HELLO_VERSION + "|sid=" + sid + "|r=" + role;
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static Hello parseHello(byte[] data) {
        if (data == null) return null;
        int version = 0;
        String sid = null;
        String role = null;
        boolean hp = false;
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
            }
        }
        if (version <= 0 || !isHex32(sid) || !isRole(role)) return null;
        return new Hello(version, sid, role, hp);
    }

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
