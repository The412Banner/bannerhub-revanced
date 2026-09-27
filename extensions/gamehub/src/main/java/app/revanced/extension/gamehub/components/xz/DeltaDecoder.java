// SPDX-License-Identifier: 0BSD
// SPDX-FileCopyrightText: The XZ for Java authors and contributors
// SPDX-FileContributor: Lasse Collin <lasse.collin@tukaani.org>

package app.revanced.extension.gamehub.components.xz;

import java.io.InputStream;

class DeltaDecoder extends DeltaCoder implements FilterDecoder {
    private final int distance;

    DeltaDecoder(byte[] props) throws UnsupportedOptionsException {
        if (props.length != 1)
            throw new UnsupportedOptionsException(
                    "Unsupported Delta filter properties");

        distance = (props[0] & 0xFF) + 1;
    }

    @Override
    public int getMemoryUsage() {
        return 1;
    }

    @Override
    public InputStream getInputStream(InputStream in, ArrayCache arrayCache) {
        return new DeltaInputStream(in, distance);
    }
}
