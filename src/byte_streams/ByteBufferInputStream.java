package byte_streams;

import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class ByteBufferInputStream extends InputStream {

    private ByteBuffer _buf;

    public ByteBufferInputStream(ByteBuffer buf) {
        _buf = buf;
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
        int nPrime = Math.min((int)n, _buf.remaining());
        _buf.position(_buf.position() + nPrime);
        return (long)nPrime;
    }

    public int read() throws IOException {
        if (!_buf.hasRemaining()) {
            return -1;
        } else {
            return _buf.get();
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
