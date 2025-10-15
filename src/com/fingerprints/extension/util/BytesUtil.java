package com.fingerprints.extension.util;

public class BytesUtil {
    public static final int IDENTIFY_STATE_END = 255;

    public static int toInt(byte[] array, int offset) {
        int offset2 = offset + 1;
        int offset3 = offset2 + 1;
        int i = (array[offset] & IDENTIFY_STATE_END) | ((array[offset2] & IDENTIFY_STATE_END) << 8);
        int offset4 = offset3 + 1;
        int i2 = i | ((array[offset3] & IDENTIFY_STATE_END) << 16);
        int i3 = offset4 + 1;
        int value = i2 | ((array[offset4] & IDENTIFY_STATE_END) << 24);
        return value;
    }

    public static long toLong(byte[] array, int offset) {
        int offset2 = offset + 1;
        int offset3 = offset2 + 1;
        int i = (array[offset] & IDENTIFY_STATE_END) | ((array[offset2] & IDENTIFY_STATE_END) << 8);
        int offset4 = offset3 + 1;
        int i2 = i | ((array[offset3] & IDENTIFY_STATE_END) << 16);
        int offset5 = offset4 + 1;
        int i3 = i2 | ((array[offset4] & IDENTIFY_STATE_END) << 24);
        int offset6 = offset5 + 1;
        int i4 = i3 | ((array[offset5] & IDENTIFY_STATE_END) << 32);
        int offset7 = offset6 + 1;
        int i5 = i4 | ((array[offset6] & IDENTIFY_STATE_END) << 40);
        int offset8 = offset7 + 1;
        int i6 = i5 | ((array[offset7] & IDENTIFY_STATE_END) << 48);
        int i7 = offset8 + 1;
        long value = i6 | ((array[offset8] & IDENTIFY_STATE_END) << 56);
        return value;
    }

    public static byte[] intToBytes(int value) {
        byte[] array = {
            (byte) (value & IDENTIFY_STATE_END), (byte) ((value >> 8) & IDENTIFY_STATE_END),
            (byte) ((value >> 16) & IDENTIFY_STATE_END), (byte) ((value >> 24) & IDENTIFY_STATE_END)
        };
        return array;
    }

    public static byte[] longToBytes(long x) {
        byte[] v = {
            (byte) ((x >> 0) & 255), (byte) ((x >> 8) & 255),
            (byte) ((x >> 16) & 255), (byte) ((x >> 24) & 255),
            (byte) ((x >> 32) & 255), (byte) ((x >> 40) & 255),
            (byte) ((x >> 48) & 255), (byte) ((x >> 56) & 255)
        };
        return v;
    }

    public static byte[] boolToBytes(boolean z) {
        return new byte[]{z ? (byte) 1 : (byte) 0};
    }

    public static boolean toBoolean(byte x) {
        boolean v = (x & 1) == 1;
        return v;
    }
}
