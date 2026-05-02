package org.apache.commons.compress.archivers.tar;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

public class TarArchiveInputStream extends FilterInputStream {
    public TarArchiveInputStream(InputStream in) {
        super(in);
        throw new UnsupportedOperationException();
    }

    public TarArchiveEntry getNextEntry() throws IOException {
        throw new UnsupportedOperationException();
    }
}
