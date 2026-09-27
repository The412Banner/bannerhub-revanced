// SPDX-License-Identifier: 0BSD
// SPDX-FileCopyrightText: The XZ for Java authors and contributors
// SPDX-FileContributor: Lasse Collin <lasse.collin@tukaani.org>

package app.revanced.extension.gamehub.components.xz.common;

public final class ByteArrayView {
    // See the version in the src9 directory.
    public static final int ALIGN_SHORT = 0;
    public static final int ALIGN_INT = 0;
    public static final int ALIGN_LONG = 0;

    public static short getShortBE(byte[] buf, int index) {
        return (short)((buf[index] << 8)
                       | (buf[index + 1] & 0xFF));
    }

    public static short getShortLE(byte[] buf, int index) {
        return (short)((buf[index] & 0xFF)
                       | (buf[index + 1] << 8));
    }

    public static int getIntBE(byte[] buf, int index) {
        return (buf[index] << 24)
               | ((buf[index + 1] & 0xFF) << 16)
               | ((buf[index + 2] & 0xFF) << 8)
               | (buf[index + 3] & 0xFF);
    }

    public static int getIntLE(byte[] buf, int index) {
        return (buf[index] & 0xFF)
               | ((buf[index + 1] & 0xFF) << 8)
               | ((buf[index + 2] & 0xFF) << 16)
               | (buf[index + 3] << 24);
    }

    public static long getLongBE(byte[] buf, int index) {
        return ((long)buf[index] << 56)
               | ((long)(buf[index + 1] & 0xFF) << 48)
               | ((long)(buf[index + 2] & 0xFF) << 40)
               | ((long)(buf[index + 3] & 0xFF) << 32)
               | ((long)(buf[index + 4] & 0xFF) << 24)
               | ((long)(buf[index + 5] & 0xFF) << 16)
               | ((long)(buf[index + 6] & 0xFF) << 8)
               | ((long)buf[index + 7] & 0xFF);
    }

    public static long getLongLE(byte[] buf, int index) {
        return ((long)buf[index] & 0xFF)
               | ((long)(buf[index + 1] & 0xFF) << 8)
               | ((long)(buf[index + 2] & 0xFF) << 16)
               | ((long)(buf[index + 3] & 0xFF) << 24)
               | ((long)(buf[index + 4] & 0xFF) << 32)
               | ((long)(buf[index + 5] & 0xFF) << 40)
               | ((long)(buf[index + 6] & 0xFF) << 48)
               | ((long)buf[index + 7] << 56);
    }

    public static void setShortBE(byte[] buf, int index, short value) {
        buf[index] = (byte)(value >>> 8);
        buf[index + 1] = (byte)(value);
    }

    public static void setShortLE(byte[] buf, int index, short value) {
        buf[index] = (byte)(value);
        buf[index + 1] = (byte)(value >>> 8);
    }

    public static void setIntBE(byte[] buf, int index, int value) {
        buf[index] = (byte)(value >>> 24);
        buf[index + 1] = (byte)(value >>> 16);
        buf[index + 2] = (byte)(value >>> 8);
        buf[index + 3] = (byte)(value);
    }

    public static void setIntLE(byte[] buf, int index, int value) {
        buf[index] = (byte)(value);
        buf[index + 1] = (byte)(value >>> 8);
        buf[index + 2] = (byte)(value >>> 16);
        buf[index + 3] = (byte)(value >>> 24);
    }

    public static void setLongBE(byte[] buf, int index, long value) {
        buf[index] = (byte)(value >>> 56);
        buf[index + 1] = (byte)(value >>> 48);
        buf[index + 2] = (byte)(value >>> 40);
        buf[index + 3] = (byte)(value >>> 32);
        buf[index + 4] = (byte)(value >>> 24);
        buf[index + 5] = (byte)(value >>> 16);
        buf[index + 6] = (byte)(value >>> 8);
        buf[index + 7] = (byte)(value);
    }

    public static void setLongLE(byte[] buf, int index, long value) {
        buf[index] = (byte)(value);
        buf[index + 1] = (byte)(value >>> 8);
        buf[index + 2] = (byte)(value >>> 16);
        buf[index + 3] = (byte)(value >>> 24);
        buf[index + 4] = (byte)(value >>> 32);
        buf[index + 5] = (byte)(value >>> 40);
        buf[index + 6] = (byte)(value >>> 48);
        buf[index + 7] = (byte)(value >>> 56);
    }

    private ByteArrayView() {}
}
