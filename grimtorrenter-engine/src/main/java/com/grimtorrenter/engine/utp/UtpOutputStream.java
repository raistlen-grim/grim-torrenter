package com.grimtorrenter.engine.utp;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/** Adapts UtpSocket.send() to the ordinary OutputStream contract - see UtpInputStream's own
 * Javadoc for why this pairing exists and its mse.Rc4InputStream/Rc4OutputStream precedent. */
final class UtpOutputStream extends OutputStream {

    private final UtpSocket socket;

    UtpOutputStream(UtpSocket socket) {
        this.socket = socket;
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        try {
            socket.send(Arrays.copyOfRange(b, off, off + len));
        } catch (UtpException e) {
            throw new IOException(e.getMessage(), e);
        }
    }
}
