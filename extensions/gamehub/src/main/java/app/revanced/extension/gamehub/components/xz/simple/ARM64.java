// SPDX-License-Identifier: 0BSD
// SPDX-FileCopyrightText: The XZ for Java authors and contributors
// SPDX-FileContributor: Lasse Collin <lasse.collin@tukaani.org>
// SPDX-FileContributor: Igor Pavlov <https://7-zip.org/>

package app.revanced.extension.gamehub.components.xz.simple;

import app.revanced.extension.gamehub.components.xz.common.ByteArrayView;

// BCJ filter for ARM64 (AArch64) instructions
public final class ARM64 implements SimpleFilter {
    private final boolean isEncoder;
    private int pos;

    public ARM64(boolean isEncoder, int startPos) {
        this.isEncoder = isEncoder;
        pos = startPos;
    }

    @Override
    public int code(byte[] buf, int off, int len) {
        int end = off + len - 4;
        int i;

        for (i = off; i <= end; i += 4) {
            // Only the highest byte is needed to identify the BL and ADRP
            // instructions.
            int instr = buf[i + 3];

            if ((instr & 0xFC) == 0x94) {
                // BL instruction:
                // The full 26-bit immediate is converted.
                // The range is +/-128 MiB.
                //
                // Using the full range helps quite a lot with big
                // executables. Smaller range would reduce false positives
                // in non-code sections of the input though so this is
                // a compromise that slightly favors big files. With the
                // full range, only six bits of the 32 need to match to
                // trigger a conversion.
                instr = ByteArrayView.getIntLE(buf, i);

                int pc = (pos + i - off) >>> 2;
                if (!isEncoder)
                    pc = -pc;

                instr = 0x94000000 | ((instr + pc) & 0x03FFFFFF);
                ByteArrayView.setIntLE(buf, i, instr);

            } else if ((instr & 0x9F) == 0x90) {
                // ADRP instruction:
                // Only values in the range +/-512 MiB are converted.
                //
                // Using less than the full +/-4 GiB range reduces false
                // positives on non-code sections of the input while being
                // excellent for executables up to 512 MiB. The positive
                // effect of ADRP conversion is smaller than that of BL
                // but it also doesn't hurt so much in non-code sections
                // of input because, with +/-512 MiB range, nine bits of 32
                // need to match to trigger a conversion (two 10-bit match
                // choices = 9 bits).
                instr = ByteArrayView.getIntLE(buf, i);
                int src = ((instr >>> 29) & 3) | ((instr >>> 3) & 0x001FFFFC);

                // With the addition only one branch is needed to
                // check the +/- range. This is usually false when
                // processing ARM64 code so branch prediction will
                // handle it well in terms of performance.
                //
                // if ((src & 0x001E0000) != 0
                //  && (src & 0x001E0000) != 0x001E0000)
                if (((src + 0x00020000) & 0x001C0000) != 0)
                    continue;

                int pc = (pos + i - off) >>> 12;
                if (!isEncoder)
                    pc = -pc;

                int dest = src + pc;

                instr &= 0x9000001F;
                instr |= (dest & 3) << 29;
                instr |= (dest & 0x0003FFFC) << 3;
                instr |= (-(dest & 0x00020000)) & 0x00E00000;
                ByteArrayView.setIntLE(buf, i, instr);
            }
        }

        i -= off;
        pos += i;
        return i;
    }
}
