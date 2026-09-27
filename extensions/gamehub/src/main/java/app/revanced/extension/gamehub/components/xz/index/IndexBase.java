// SPDX-License-Identifier: 0BSD
// SPDX-FileCopyrightText: The XZ for Java authors and contributors
// SPDX-FileContributor: Lasse Collin <lasse.collin@tukaani.org>

package app.revanced.extension.gamehub.components.xz.index;

import app.revanced.extension.gamehub.components.xz.common.Util;
import app.revanced.extension.gamehub.components.xz.XZIOException;

abstract class IndexBase {
    private final XZIOException invalidIndexException;
    long blocksSum = 0;
    long uncompressedSum = 0;
    long indexListSize = 0;
    long recordCount = 0;

    IndexBase(XZIOException invalidIndexException) {
        this.invalidIndexException = invalidIndexException;
    }

    private long getUnpaddedIndexSize() {
        // Index Indicator + Number of Records + List of Records + CRC32
        return 1 + Util.getVLISize(recordCount) + indexListSize + 4;
    }

    public long getIndexSize() {
        return (getUnpaddedIndexSize() + 3) & ~3;
    }

    public long getStreamSize() {
        return Util.STREAM_HEADER_SIZE + blocksSum + getIndexSize()
                + Util.STREAM_HEADER_SIZE;
    }

    int getIndexPaddingSize() {
        return (int)((4 - getUnpaddedIndexSize()) & 3);
    }

    void add(long unpaddedSize, long uncompressedSize) throws XZIOException {
        blocksSum += (unpaddedSize + 3) & ~3;
        uncompressedSum += uncompressedSize;
        indexListSize += Util.getVLISize(unpaddedSize)
                         + Util.getVLISize(uncompressedSize);
        ++recordCount;

        if (blocksSum < 0 || uncompressedSum < 0
                || getIndexSize() > Util.BACKWARD_SIZE_MAX
                || getStreamSize() < 0)
            throw invalidIndexException;
    }
}
