package dev.agentcraft.gtnh.bridge;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Minimal RFC 6455 client: plain ws:// only, text frames, masked client frames, ping/pong, close.
 * Deliberately sends no Origin header (the adapter rejects browsers by Origin). Java 8 API only,
 * so it runs on GTNH's Java 17-25 runtime and in the 1.7.10 dev environment alike.
 */
public final class WebSocketClient {

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_MESSAGE = 16 * 1024 * 1024;

    private final Socket socket = new Socket();
    private final SecureRandom random = new SecureRandom();
    private InputStream in;
    private OutputStream out;
    private volatile boolean closed;

    public void connect(URI uri, int timeoutMs) throws IOException {
        if (!"ws".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("only ws:// URLs are supported, got " + uri.getScheme());
        }
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 80;
        socket.connect(new InetSocketAddress(host, port), timeoutMs);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(60_000); // the adapter pings every 15 s
        in = socket.getInputStream();
        out = socket.getOutputStream();

        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String key = Base64.getEncoder()
            .encodeToString(nonce);
        String path = uri.getRawPath() == null || uri.getRawPath()
            .isEmpty() ? "/" : uri.getRawPath();
        String req = "GET " + path
            + " HTTP/1.1\r\n"
            + "Host: "
            + host
            + ":"
            + port
            + "\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Key: "
            + key
            + "\r\n"
            + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        String head = readHttpHead();
        String status = head.split("\r\n", 2)[0];
        if (!status.contains(" 101 ")) {
            throw new IOException("handshake refused: " + status);
        }
        String expected = accept(key);
        boolean ok = false;
        for (String line : head.split("\r\n")) {
            int i = line.indexOf(':');
            if (i > 0 && line.substring(0, i)
                .trim()
                .equalsIgnoreCase("Sec-WebSocket-Accept")
                && line.substring(i + 1)
                    .trim()
                    .equals(expected)) {
                ok = true;
            }
        }
        if (!ok) {
            throw new IOException("bad Sec-WebSocket-Accept");
        }
    }

    private String readHttpHead() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) throw new EOFException("closed during handshake");
            buf.write(b);
            if (buf.size() > 16384) throw new IOException("handshake response too large");
            state = (b == '\r' && (state == 0 || state == 2)) || (b == '\n' && (state == 1 || state == 3)) ? state + 1
                : (b == '\r' ? 1 : 0);
        }
        return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    static String accept(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return Base64.getEncoder()
                .encodeToString(sha1.digest((key + GUID).getBytes(StandardCharsets.ISO_8859_1)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized void sendText(String text) throws IOException {
        sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    private synchronized void sendFrame(int opcode, byte[] payload) throws IOException {
        if (closed) throw new IOException("closed");
        ByteArrayOutputStream f = new ByteArrayOutputStream(payload.length + 14);
        f.write(0x80 | opcode);
        int n = payload.length;
        if (n < 126) {
            f.write(0x80 | n);
        } else if (n < 65536) {
            f.write(0x80 | 126);
            f.write((n >>> 8) & 0xFF);
            f.write(n & 0xFF);
        } else {
            f.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) f.write((int) (((long) n >>> (8 * i)) & 0xFF));
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        f.write(mask, 0, 4);
        for (int i = 0; i < n; i++) f.write(payload[i] ^ mask[i & 3]);
        out.write(f.toByteArray());
        out.flush();
    }

    /** Blocks until the next complete text message; answers pings. Throws on close/error. */
    public String readText() throws IOException {
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        boolean inMessage = false;
        while (true) {
            int b1 = readByte();
            int b2 = readByte();
            boolean fin = (b1 & 0x80) != 0;
            int opcode = b1 & 0x0F;
            long len = b2 & 0x7F;
            if (len == 126) {
                len = (readByte() << 8) | readByte();
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | readByte();
            }
            if ((b2 & 0x80) != 0) throw new IOException("server frames must not be masked");
            if (len > MAX_MESSAGE || msg.size() + len > MAX_MESSAGE) throw new IOException("message too large");
            byte[] data = readFully((int) len);
            switch (opcode) {
                case 0x8: // close
                    close();
                    throw new EOFException("closed by server");
                case 0x9: // ping
                    sendFrame(0xA, data);
                    continue;
                case 0xA: // pong
                    continue;
                case 0x1:
                case 0x2:
                    msg.reset();
                    msg.write(data, 0, data.length);
                    inMessage = true;
                    break;
                case 0x0:
                    if (!inMessage) throw new IOException("unexpected continuation frame");
                    msg.write(data, 0, data.length);
                    break;
                default:
                    throw new IOException("bad opcode " + opcode);
            }
            if (fin) {
                return new String(msg.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException("connection closed");
        return b;
    }

    private byte[] readFully(int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new EOFException("connection closed");
            off += r;
        }
        return buf;
    }

    public void close() {
        if (closed) return;
        if (out != null) {
            try {
                sendFrame(0x8, new byte[] { 0x03, (byte) 0xE8 });
            } catch (IOException | RuntimeException ignored) {}
        }
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {}
    }
}
