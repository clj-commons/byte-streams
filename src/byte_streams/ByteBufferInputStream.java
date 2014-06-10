package byte_streams;

import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class ByteBufferInputStream extends InputStream {

    private ByteBuffer _buf;

    public ByteBufferInputStream(ByteBuffer buf) {
        _buf = buf;
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
