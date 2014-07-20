package byte_streams;

import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class ByteBufferInputStream extends InputStream {

    private ByteBuffer _buf;

    public ByteBufferInputStream(ByteBuffer buf) {
        _buf = buf;
    }

    public void close() {
    }

    public int available() {
        return _buf.remaining();
    }

    public boolean markSupported() {
        return true;
    }

    public void mark(int readlimit) {
        _buf.mark();
    }

    public void reset() {
        _buf.reset();
    }

    public long skip(long n) {
        int nP = Math.min((int)n, _buf.remaining());
        _buf.position(_buf.position() + nP);
        return (long)nP;
    }

    public int read() throws IOException {
        if (!_buf.hasRemaining()) {
            return -1;
        } else {
            return (int) _buf.get() & 0xFF;
        }
    }

    public int read(byte[] bytes, int offset, int length) throws IOException {
        length = Math.min(length, _buf.remaining());
        if (length == 0) {
            return -1;
        } else {
            _buf.get(bytes, offset, length);
            return length;
        }
    }
}
