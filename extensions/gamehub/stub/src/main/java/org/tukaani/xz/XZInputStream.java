package org.tukaani.xz;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

public class XZInputStream extends FilterInputStream {
    public XZInputStream(InputStream in, int memoryLimit) throws IOException {
        super(in);
        throw new UnsupportedOperationException();
    }
}
