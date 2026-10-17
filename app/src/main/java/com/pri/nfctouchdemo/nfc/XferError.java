package com.pri.nfctouchdemo.nfc;

//pri added by zhuyang,Batch 5: one failure object per stage and a stable error code,20261017,start
/**
 * A transfer failure carries the stage it happened in, a stable numeric code and an
 * optional human-readable detail. The detail is only for logs / a second banner line;
 * TapService maps the code to the fixed strings in strings.xml.
 */
public final class XferError {

    public enum Stage { NFC_EXCHANGE, CONNECT, OFFER, TRANSFER, DONE }

    public static final int UNKNOWN = 0;
    public static final int NFC_FAIL = 1;
    public static final int CONNECT_TIMEOUT = 2;
    public static final int CONNECT_REFUSED = 3;
    public static final int AUTH_FAILED = 4;
    public static final int OFFER_TIMEOUT = 5;
    public static final int PEER_REJECTED = 6;
    public static final int NO_SPACE = 7;
    public static final int TRANSFER_TIMEOUT = 8;
    public static final int TRANSFER_STALL = 9;
    public static final int CHECKSUM_FAILED = 10;
    public static final int PROTOCOL_ERROR = 11;
    public static final int CANCELLED = 12;
    public static final int ROLE_CONFLICT = 13;
    public static final int DONE_ACK_TIMEOUT = 14;

    public final Stage stage;
    public final int code;
    public final String detail;

    public XferError(Stage stage, int code) {
        this(stage, code, null);
    }

    public XferError(Stage stage, int code, String detail) {
        this.stage = stage;
        this.code = code;
        this.detail = detail;
    }

    public static String nameOf(int code) {
        switch (code) {
            case NFC_FAIL: return "NFC_FAIL";
            case CONNECT_TIMEOUT: return "CONNECT_TIMEOUT";
            case CONNECT_REFUSED: return "CONNECT_REFUSED";
            case AUTH_FAILED: return "AUTH_FAILED";
            case OFFER_TIMEOUT: return "OFFER_TIMEOUT";
            case PEER_REJECTED: return "PEER_REJECTED";
            case NO_SPACE: return "NO_SPACE";
            case TRANSFER_TIMEOUT: return "TRANSFER_TIMEOUT";
            case TRANSFER_STALL: return "TRANSFER_STALL";
            case CHECKSUM_FAILED: return "CHECKSUM_FAILED";
            case PROTOCOL_ERROR: return "PROTOCOL_ERROR";
            case CANCELLED: return "CANCELLED";
            case ROLE_CONFLICT: return "ROLE_CONFLICT";
            case DONE_ACK_TIMEOUT: return "DONE_ACK_TIMEOUT";
            default: return "UNKNOWN";
        }
    }

    @Override
    public String toString() {
        return nameOf(code) + "(" + code + ")"
                + (stage == null ? "" : "@" + stage)
                + (detail == null ? "" : " — " + detail);
    }
}
//pri added by zhuyang,Batch 5: one failure object per stage and a stable error code,20261017,end
