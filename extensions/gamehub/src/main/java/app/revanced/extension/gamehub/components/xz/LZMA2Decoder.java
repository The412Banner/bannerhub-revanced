// SPDX-License-Identifier: 0BSD
// SPDX-FileCopyrightText: The XZ for Java authors and contributors
// SPDX-FileContributor: Lasse Collin <lasse.collin@tukaani.org>

package app.revanced.extension.gamehub.components.xz;

import java.io.InputStream;

class LZMA2Decoder extends LZMA2Coder implements FilterDecoder {
    private int dictSize;

    LZMA2Decoder(byte[] props) throws UnsupportedOptionsException {
        // Up to 1.5 GiB dictionary is supported. The bigger ones
        // are too big for int.
        if (props.length != 1 || (props[0] & 0xFF) > 37)
            throw new UnsupportedOptionsException(
                    "Unsupported LZMA2 properties");

        dictSize = 2 | (props[0] & 1);
        dictSize <<= (props[0] >>> 1) + 11;
    }

    @Override
    public int getMemoryUsage() {
        return LZMA2InputStream.getMemoryUsage(dictSize);
    }

    @Override
    public InputStream getInputStream(InputStream in, ArrayCache arrayCache) {
        return new LZMA2InputStream(in, dictSize, null, arrayCache);
    }
}
