// SPDX-License-Identifier: 0BSD
// SPDX-FileCopyrightText: The XZ for Java authors and contributors
// SPDX-FileContributor: Lasse Collin <lasse.collin@tukaani.org>
// SPDX-FileContributor: Brett Okken <brett.okken.os@gmail.com>

package app.revanced.extension.gamehub.components.xz.delta;

public class DeltaDecoder extends DeltaCoder {
    public DeltaDecoder(int distance) {
        super(distance);
    }

    public void decode(byte[] buf, int off, int len) {
        int i = 0;

        // First process from the history buffer.
        for (int j = Math.min(len, distance); i < j; ++i) {
            buf[off + i] += history[i];
        }

        // Then process rest just within buf.
        for ( ; i < len; ++i) {
            buf[off + i] += buf[off + i - distance];
        }

        // Finally, populate the history buffer.
        if (len >= distance) {
            System.arraycopy(buf, off + len - distance, history, 0, distance);
        } else {
            assert i == len;

            // Copy from the end of the history buffer to the beginning.
            System.arraycopy(history, i, history, 0, distance - i);

            // Copy all of "in" to the end of the history buffer.
            System.arraycopy(buf, off, history, distance - i, len);
        }
    }
}
