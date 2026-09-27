// SPDX-License-Identifier: 0BSD
// SPDX-FileCopyrightText: The XZ for Java authors and contributors
// SPDX-FileContributor: Lasse Collin <lasse.collin@tukaani.org>
// SPDX-FileContributor: Igor Pavlov <https://7-zip.org/>

package app.revanced.extension.gamehub.components.xz.simple;

import app.revanced.extension.gamehub.components.xz.common.ByteArrayView;

// BCJ filter for big endian PowerPC instructions
public final class PowerPC implements SimpleFilter {
    private final boolean isEncoder;
    private int pos;

    public PowerPC(boolean isEncoder, int startPos) {
        this.isEncoder = isEncoder;
        pos = startPos;
    }

    @Override
    public int code(byte[] buf, int off, int len) {
        int end = off + len - 4;
        int i;

        for (i = off; i <= end; i += 4) {
            if ((buf[i] & 0xFC) == 0x48 && (buf[i + 3] & 0x03) == 0x01) {
                int instr = ByteArrayView.getIntBE(buf, i);

                int pc = pos + i - off;
                if (!isEncoder)
                    pc = -pc;

                instr = 0x48000001 | ((instr + pc) & 0x03FFFFFC);
                ByteArrayView.setIntBE(buf, i, instr);
            }
        }

        i -= off;
        pos += i;
        return i;
    }
}
