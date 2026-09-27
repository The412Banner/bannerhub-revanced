// SPDX-License-Identifier: 0BSD
// SPDX-FileCopyrightText: The XZ for Java authors and contributors
// SPDX-FileContributor: Lasse Collin <lasse.collin@tukaani.org>
// SPDX-FileContributor: Igor Pavlov <https://7-zip.org/>

package app.revanced.extension.gamehub.components.xz.simple;

import app.revanced.extension.gamehub.components.xz.common.ByteArrayView;

// BCJ filter for SPARC instructions
public final class SPARC implements SimpleFilter {
    private final boolean isEncoder;
    private int pos;

    public SPARC(boolean isEncoder, int startPos) {
        this.isEncoder = isEncoder;
        pos = startPos;
    }

    @Override
    public int code(byte[] buf, int off, int len) {
        int end = off + len - 4;
        int i;

        for (i = off; i <= end; i += 4) {
            if ((buf[i] == 0x40 && (buf[i + 1] & 0xC0) == 0x00)
                    || (buf[i] == 0x7F && (buf[i + 1] & 0xC0) == 0xC0)) {
                int src = ByteArrayView.getIntBE(buf, i);

                int pc = (pos + i - off) >>> 2;
                if (!isEncoder)
                    pc = -pc;

                int dest = src + pc;
                dest <<= 9;
                dest >>= 9;
                dest = 0x40000000 | (dest & 0x3FFFFFFF);

                ByteArrayView.setIntBE(buf, i, dest);
            }
        }

        i -= off;
        pos += i;
        return i;
    }
}
