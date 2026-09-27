// SPDX-License-Identifier: 0BSD
// SPDX-FileCopyrightText: The XZ for Java authors and contributors
// SPDX-FileContributor: Brett Okken <brett.okken.os@gmail.com>
// SPDX-FileContributor: Lasse Collin <lasse.collin@tukaani.org>

package app.revanced.extension.gamehub.components.xz.check;

import app.revanced.extension.gamehub.components.xz.common.ByteArrayView;

public class CRC64 extends Check {
    private static final long[][] TABLE = new long[4][256];

    static {
        final long poly64 = 0xC96C5795D7870F42L;

        for (int s = 0; s < 4; ++s) {
            for (int b = 0; b < 256; ++b) {
                long r = s == 0 ? b : TABLE[s - 1][b];
                for (int i = 0; i < 8; ++i) {
                    if ((r & 1) == 1) {
                        r = (r >>> 1) ^ poly64;
                    } else {
                        r >>>= 1;
                    }
                }
                TABLE[s][b] = r;
            }
        }
    }

    private long crc = -1;

    public CRC64() {
        size = 8;
        name = "CRC64";
    }

    @Override
    public void update(byte[] buf, int off, int len) {
        final int end = off + len;
        int i = off;

        for (int end4 = end - 3; i < end4; i += 4) {
            final int tmp = (int)crc;
            crc = TABLE[3][(tmp & 0xFF) ^ (buf[i] & 0xFF)] ^
                  TABLE[2][((tmp >>> 8) & 0xFF) ^ (buf[i + 1] & 0xFF)] ^
                  (crc >>> 32) ^
                  TABLE[1][((tmp >>> 16) & 0xFF) ^ (buf[i + 2] & 0xFF)] ^
                  TABLE[0][((tmp >>> 24) & 0xFF) ^ (buf[i + 3] & 0xFF)];
        }

        while (i < end)
            crc = TABLE[0][(buf[i++] & 0xFF) ^ ((int)crc & 0xFF)] ^
                  (crc >>> 8);
    }

    @Override
    public byte[] finish() {
        byte[] buf = new byte[8];
        ByteArrayView.setLongLE(buf, 0, ~crc);
        crc = -1;
        return buf;
    }
}
