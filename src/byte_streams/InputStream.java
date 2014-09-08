package byte_streams;

import java.io.IOException;

public class InputStream extends java.io.InputStream {

    public interface Streamable {
        int available();
        void close();
        long skip(long n);
        int read() throws IOException;
        int read(byte[] bytes, int offset, int length) throws IOException;
    }

    private Streamable _s;

    public InputStream(Streamable s) {
        _s = s;
    }

    public void close() {
        _s.close();
    }

    public int available() {
        return _s.available();
    }

    public boolean markSupported() {
        return false;
    }

    public void mark(int readlimit) {
        throw new UnsupportedOperationException();
    }

    public void reset() {
        throw new UnsupportedOperationException();
    }

    public long skip(long n) {
        return _s.skip(n);
    }

    public int read() throws IOException {
        return _s.read();
    }

    public int read(byte[] bytes, int offset, int length) throws IOException {
        return _s.read(bytes, offset, length);
    }
}
