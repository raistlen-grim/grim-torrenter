package com.grimtorrenter.engine.blocklist;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Fails with an IOException the moment more than {@code limit} bytes have been read - what
 * bounds a download and a decompressed stream against a hostile or corrupt list (a gzip bomb).
 * See design_docs/0078. */
final class LimitedInputStream extends FilterInputStream {

    private final long limit;
    private final String what;
    private long read;

    LimitedInputStream(InputStream in, long limit, String what) {
        super(in);
        this.limit = limit;
        this.what = what;
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value >= 0) {
            count(1);
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int n = super.read(buffer, offset, length);
        if (n > 0) {
            count(n);
        }
        return n;
    }

    private void count(long n) throws IOException {
        read += n;
        if (read > limit) {
            throw new IOException(what + " is larger than the " + (limit >> 20) + " MB limit");
        }
    }
}
