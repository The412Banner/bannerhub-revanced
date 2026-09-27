// SPDX-License-Identifier: 0BSD
// SPDX-FileCopyrightText: The XZ for Java authors and contributors
// SPDX-FileContributor: Lasse Collin <lasse.collin@tukaani.org>
// SPDX-FileContributor: Igor Pavlov <https://7-zip.org/>

package app.revanced.extension.gamehub.components.xz.rangecoder;

import java.io.DataInputStream;
import java.io.IOException;
import app.revanced.extension.gamehub.components.xz.ArrayCache;
import app.revanced.extension.gamehub.components.xz.CorruptedInputException;

public final class RangeDecoderFromBuffer extends RangeDecoder {
    private static final int INIT_SIZE = 5;

    private final byte[] buf;
    private int pos;

    public RangeDecoderFromBuffer(int inputSizeMax, ArrayCache arrayCache) {
        // We will use the *end* of the array so if the cache gives us
        // a bigger-than-requested array, we still want to use buf.length.
        buf = arrayCache.getByteArray(inputSizeMax - INIT_SIZE, false);
        pos = buf.length;
    }

    public void putArraysToCache(ArrayCache arrayCache) {
        arrayCache.putArray(buf);
    }

    public void prepareInputBuffer(DataInputStream in, int len)
            throws IOException {
        if (len < INIT_SIZE)
            throw new CorruptedInputException();

        if (in.readUnsignedByte() != 0x00)
            throw new CorruptedInputException();

        code = in.readInt();
        range = 0xFFFFFFFF;

        // Read the data to the end of the buffer. If the data is corrupt
        // and the decoder, reading from buf, tries to read past the end of
        // the data, ArrayIndexOutOfBoundsException will be thrown and
        // the problem is detected immediately.
        len -= INIT_SIZE;
        pos = buf.length - len;
        in.readFully(buf, pos, len);
    }

    public boolean isFinished() {
        return pos == buf.length && code == 0;
    }

    @Override
    public void normalize() throws IOException {
        if ((range & TOP_MASK) == 0) {
            try {
                // If the input is corrupt, this might throw
                // ArrayIndexOutOfBoundsException.
                code = (code << SHIFT_BITS) | (buf[pos++] & 0xFF);
                range <<= SHIFT_BITS;
            } catch (ArrayIndexOutOfBoundsException e) {
                throw new CorruptedInputException();
            }
        }
    }
}
