// SPDX-License-Identifier: 0BSD
// SPDX-FileCopyrightText: The XZ for Java authors and contributors
// SPDX-FileContributor: Lasse Collin <lasse.collin@tukaani.org>
// SPDX-FileContributor: Igor Pavlov <https://7-zip.org/>

package app.revanced.extension.gamehub.components.xz.simple;

import app.revanced.extension.gamehub.components.xz.common.ByteArrayView;

// BCJ filter for x86 instructions
public final class X86 implements SimpleFilter {
    private static final boolean[] MASK_TO_ALLOWED_STATUS
            = {true, true, true, false, true, false, false, false};

    private static final int[] MASK_TO_BIT_NUMBER = {0, 1, 2, 2, 3, 3, 3, 3};

    private final boolean isEncoder;
    private int pos;
    private int prevMask = 0;

    private static boolean test86MSByte(byte b) {
        int i = b & 0xFF;
        return i == 0x00 || i == 0xFF;
    }

    public X86(boolean isEncoder, int startPos) {
        this.isEncoder = isEncoder;
        pos = startPos + 5;
    }

    @Override
    public int code(byte[] buf, int off, int len) {
        int prevPos = off - 1;
        int end = off + len - 5;
        int i;

        for (i = off; i <= end; ++i) {
            if ((buf[i] & 0xFE) != 0xE8)
                continue;

            prevPos = i - prevPos;
            if ((prevPos & ~3) != 0) { // (unsigned)prevPos > 3
                prevMask = 0;
            } else {
                prevMask = (prevMask << (prevPos - 1)) & 7;
                if (prevMask != 0) {
                    if (!MASK_TO_ALLOWED_STATUS[prevMask] || test86MSByte(
                            buf[i + 4 - MASK_TO_BIT_NUMBER[prevMask]])) {
                        prevPos = i;
                        prevMask = (prevMask << 1) | 1;
                        continue;
                    }
                }
            }

            prevPos = i;

            if (test86MSByte(buf[i + 4])) {
                int src = ByteArrayView.getIntLE(buf, i + 1);
                int dest;
                while (true) {
                    if (isEncoder)
                        dest = src + (pos + i - off);
                    else
                        dest = src - (pos + i - off);

                    if (prevMask == 0)
                        break;

                    int index = MASK_TO_BIT_NUMBER[prevMask] * 8;
                    if (!test86MSByte((byte)(dest >>> (24 - index))))
                        break;

                    src = dest ^ ((1 << (32 - index)) - 1);
                }

                // Sign extend bit 24 to bits [31:24].
                dest <<= 7;
                dest >>= 7;

                ByteArrayView.setIntLE(buf, i + 1, dest);
                i += 4;
            } else {
                prevMask = (prevMask << 1) | 1;
            }
        }

        prevPos = i - prevPos;
        prevMask = ((prevPos & ~3) != 0) ? 0 : prevMask << (prevPos - 1);

        i -= off;
        pos += i;
        return i;
    }
}
