package com.github.luben.zstd;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

public class ZstdInputStreamNoFinalizer extends FilterInputStream {
    public ZstdInputStreamNoFinalizer(InputStream in) throws IOException {
        super(in);
        throw new UnsupportedOperationException();
    }
}
