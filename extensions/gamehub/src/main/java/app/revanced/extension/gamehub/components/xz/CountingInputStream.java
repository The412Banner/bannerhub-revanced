// SPDX-License-Identifier: 0BSD
// SPDX-FileCopyrightText: The XZ for Java authors and contributors
// SPDX-FileContributor: Lasse Collin <lasse.collin@tukaani.org>

package app.revanced.extension.gamehub.components.xz;

import java.io.InputStream;
import java.io.IOException;

/**
 * Counts the number of bytes read from an input stream.
 * The {@code close()} method does nothing, that is, the underlying
 * {@code InputStream} isn't closed.
 */
class CountingInputStream extends CloseIgnoringInputStream {
    private long size = 0;

    public CountingInputStream(InputStream in) {
        super(in);
    }

    @Override
    public int read() throws IOException {
        int ret = in.read();
        if (ret != -1 && size >= 0)
            ++size;

        return ret;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int ret = in.read(b, off, len);
        if (ret > 0 && size >= 0)
            size += ret;

        return ret;
    }

    public long getSize() {
        return size;
    }
}
