import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Base64;

import dev.agentcraft.gtnh.bridge.WebSocketClient;
import dev.agentcraft.gtnh.write.core.WriteLock;
import dev.agentcraft.gtnh.write.mc.ControlLink;

/**
 * Security-review hardening: F5 (the lock file is "unlocked" only when positively absent) and F11 (the write link's
 * transport: 16384-byte limit before allocation, text only). Pure Java, loopback sockets only.
 */
public class HardeningCheck {

    public static void main(String[] a) throws Exception {
        lockFile();
        transport();
        Check.summary("HardeningCheck");
    }

    // ---- F5 ------------------------------------------------------------------------------------------------------

    static WriteLock fresh(File f) {
        return new WriteLock(f, new Check.FakeClock());
    }

    static void lockFile() throws Exception {
        File dir = Check.tmpDir("f5");
        File f = new File(dir, "write-lock.json");

        WriteLock none = fresh(f);
        none.load();
        Check.ok(!none.isLocked(), "F5: a positively absent file is unlocked");

        // directory in place of the file
        Files.createDirectory(f.toPath());
        WriteLock dirLock = fresh(f);
        dirLock.load();
        Check.ok(dirLock.isLocked() && dirLock.info().contains("not a regular file"), "F5: a directory at the lock path latches locked (" + dirLock.info() + ")");
        Files.delete(f.toPath());

        // a missing parent directory is still "absent"
        WriteLock noParent = fresh(new File(dir, "nope/write-lock.json"));
        noParent.load();
        Check.ok(!noParent.isLocked(), "F5: a missing parent directory is absent too (nothing was ever locked)");

        // symlinks (dangling and to a good lock file)
        boolean links = true;
        try {
            Files.createSymbolicLink(f.toPath(), new File(dir, "does-not-exist").toPath());
        } catch (IOException | UnsupportedOperationException e) {
            links = false;
            System.out.println("  (symlinks unavailable here: " + e + "; symlink checks skipped)");
        }
        if (links) {
            WriteLock dangling = fresh(f);
            dangling.load();
            Check.ok(dangling.isLocked() && dangling.info().contains("symlink"), "F5: a dangling symlink latches locked (" + dangling.info() + ")");
            Files.delete(f.toPath());
            WriteLock src = fresh(new File(dir, "real.json"));
            src.lock("QAOwner", "real");
            Files.createSymbolicLink(f.toPath(), new File(dir, "real.json").toPath());
            WriteLock viaLink = fresh(f);
            viaLink.load();
            Check.ok(viaLink.isLocked() && viaLink.info().contains("symlink"), "F5: a symlink (even to a good lock file) is not followed: locked (" + viaLink.info() + ")");
            // the owner can still clear it, and then it is absent
            Check.eq(viaLink.unlock(true, false), null, "F5: unlock removes the symlink");
            Check.ok(!Files.exists(f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS), "F5: ... and the path is gone");
            WriteLock after = fresh(f);
            after.load();
            Check.ok(!after.isLocked(), "F5: ... so a fresh load is unlocked");
        }

        // access denied: the directory cannot be searched
        File secret = new File(dir, "noaccess");
        secret.mkdir();
        File inner = new File(secret, "write-lock.json");
        try {
            Path pd = secret.toPath();
            Files.setPosixFilePermissions(pd, PosixFilePermissions.fromString("---------"));
            boolean denied = !new File(secret, "x").getAbsoluteFile().getParentFile().canExecute();
            if (denied) {
                WriteLock den = fresh(inner);
                den.load();
                Check.ok(den.isLocked() && den.info().contains("AccessDenied"), "F5: access denied latches locked (" + den.info() + ")");
            } else {
                System.out.println("  (running with enough privilege to ignore directory permissions; access-denied check skipped)");
            }
        } catch (UnsupportedOperationException e) {
            System.out.println("  (no POSIX permissions here; access-denied check skipped)");
        } finally {
            try {
                Files.setPosixFilePermissions(secret.toPath(), PosixFilePermissions.fromString("rwx------"));
            } catch (Exception ignored) {}
        }

        // existing rules still hold: a good file locks, a bad file locks, a vanished file does not release a held lock
        File g = new File(dir, "good.json");
        WriteLock w = fresh(g);
        w.lock("QAOwner", "keep");
        WriteLock reader = fresh(g);
        reader.load();
        Check.ok(reader.isLocked() && reader.info().contains("keep"), "F5: a good lock file is read back");
        Files.delete(g.toPath());
        reader.load();
        Check.ok(reader.isLocked(), "F5: a vanished file does not release a lock already held in memory");
        Files.write(g.toPath(), "{\"locked\":false}".getBytes(StandardCharsets.UTF_8));
        WriteLock bad = fresh(g);
        bad.load();
        Check.ok(bad.isLocked(), "F5: a malformed/locked=false file latches locked");
    }

    // ---- F11 -----------------------------------------------------------------------------------------------------

    /** A one-shot loopback WebSocket server: completes the upgrade, writes the given raw frames, then holds the socket open. */
    static final class Server implements Runnable {

        final ServerSocket ss;
        final byte[][] frames;
        volatile Socket peer;
        volatile boolean sentAll;

        Server(byte[]... frames) throws IOException {
            this.ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            this.frames = frames;
            Thread t = new Thread(this, "ws-test-server");
            t.setDaemon(true);
            t.start();
        }

        URI uri() {
            return URI.create("ws://127.0.0.1:" + ss.getLocalPort() + "/");
        }

        @Override
        public void run() {
            try {
                Socket s = ss.accept();
                peer = s;
                InputStream in = s.getInputStream();
                ByteArrayOutputStream head = new ByteArrayOutputStream();
                int state = 0;
                while (state < 4) {
                    int b = in.read();
                    if (b < 0) return;
                    head.write(b);
                    state = (b == '\r' && (state == 0 || state == 2)) || (b == '\n' && (state == 1 || state == 3)) ? state + 1 : (b == '\r' ? 1 : 0);
                }
                String key = "";
                for (String line : new String(head.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n")) {
                    if (line.toLowerCase().startsWith("sec-websocket-key:")) key = line.substring(line.indexOf(':') + 1).trim();
                }
                MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
                String acc = Base64.getEncoder().encodeToString(sha1.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
                OutputStream out = s.getOutputStream();
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + acc + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                for (byte[] f : frames) out.write(f);
                out.flush();
                sentAll = true;
                // hold the connection open: a client that waited for a declared-but-never-sent payload would hang here
                Thread.sleep(8000);
                s.close();
            } catch (Exception ignored) {} finally {
                try {
                    ss.close();
                } catch (IOException ignored) {}
            }
        }

        void close() {
            try {
                ss.close();
                if (peer != null) peer.close();
            } catch (IOException ignored) {}
        }
    }

    /** An unmasked server frame. */
    static byte[] frame(boolean fin, int opcode, byte[] payload) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write((fin ? 0x80 : 0) | opcode);
        int n = payload.length;
        if (n < 126) {
            o.write(n);
        } else if (n < 65536) {
            o.write(126);
            o.write(n >>> 8);
            o.write(n & 0xFF);
        } else {
            o.write(127);
            for (int i = 7; i >= 0; i--) o.write((int) (((long) n >>> (8 * i)) & 0xFF));
        }
        o.write(payload, 0, n);
        return o.toByteArray();
    }

    /** Only the header of a frame that claims {@code claimed} bytes (as the 8-byte length form); no payload follows. */
    static byte[] headerOnly(int opcode, long claimed) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x80 | opcode);
        o.write(127);
        for (int i = 7; i >= 0; i--) o.write((int) ((claimed >>> (8 * i)) & 0xFF));
        return o.toByteArray();
    }

    static byte[] text(int n) {
        byte[] b = new byte[n];
        java.util.Arrays.fill(b, (byte) 'a');
        return b;
    }

    /** Connects {@code client} to a server that sends {@code frames}; returns the first message or "ERR: ..." (and how long it took). */
    static String read(WebSocketClient client, byte[]... frames) throws Exception {
        Server srv = new Server(frames);
        try {
            client.connect(srv.uri(), 3000);
            return client.readText();
        } catch (IOException e) {
            return "ERR: " + e.getMessage();
        } finally {
            client.close();
            srv.close();
        }
    }

    static WebSocketClient strict() {
        return ControlLink.newClient();
    }

    static void transport() throws Exception {
        String r = read(strict(), frame(true, 1, text(16384)));
        Check.eq(r.length(), 16384, "F11: a text message of exactly 16384 bytes is accepted");
        r = read(strict(), frame(true, 1, "{}".getBytes(StandardCharsets.UTF_8)));
        Check.eq(r, "{}", "F11: a small text message is accepted");
        r = read(strict(), frame(true, 1, text(16385)));
        Check.ok(r.startsWith("ERR") && r.contains("too large"), "F11: 16385 bytes is refused (" + r + ")");

        // the limit is applied from the header: a huge declared length is refused at once, without waiting for or allocating it
        long t0 = System.nanoTime();
        r = read(strict(), headerOnly(1, 1L << 30));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Check.ok(r.startsWith("ERR") && r.contains("too large"), "F11: a 1 GiB header is refused (" + r + ")");
        Check.ok(ms < 3000, "F11: ... without waiting for the payload (" + ms + " ms; the server never sends it)");
        r = read(strict(), headerOnly(1, 1L << 62));
        Check.ok(r.startsWith("ERR") && r.contains("too large"), "F11: a 2^62 header is refused (" + r + ")");
        r = read(strict(), headerOnly(1, Long.MIN_VALUE));
        Check.ok(r.startsWith("ERR") && r.contains("too large"), "F11: a header with the top bit set (negative length) is refused (" + r + ")");

        // fragments are summed against the limit before the next one is read
        r = read(strict(), frame(false, 1, text(8000)), frame(true, 0, text(8000)));
        Check.eq(r.length(), 16000, "F11: two fragments within the limit are joined");
        r = read(strict(), frame(false, 1, text(10000)), headerOnly(0, 10000));
        Check.ok(r.startsWith("ERR") && r.contains("too large"), "F11: fragments over the limit are refused when the second header arrives (" + r + ")");

        // text only
        r = read(strict(), frame(true, 2, "{}".getBytes(StandardCharsets.UTF_8)));
        Check.ok(r.startsWith("ERR") && r.contains("binary"), "F11: a binary frame closes the connection (" + r + ")");
        r = read(strict(), frame(false, 1, "ab".getBytes(StandardCharsets.UTF_8)), frame(true, 2, "cd".getBytes(StandardCharsets.UTF_8)));
        Check.ok(r.startsWith("ERR"), "F11: binary inside a fragmented text message is refused too (" + r + ")");

        // other strictness
        r = read(strict(), frame(false, 1, "ab".getBytes(StandardCharsets.UTF_8)), frame(true, 9, new byte[0]), frame(true, 0, "cd".getBytes(StandardCharsets.UTF_8)));
        Check.eq(r, "abcd", "F11: a ping between fragments is answered and the message completes");
        r = read(strict(), frame(false, 1, "ab".getBytes(StandardCharsets.UTF_8)), frame(true, 1, "cd".getBytes(StandardCharsets.UTF_8)));
        Check.ok(r.startsWith("ERR") && r.contains("unfinished"), "F11: a new data frame inside an unfinished message is refused (" + r + ")");
        r = read(strict(), frame(true, 1, new byte[] { (byte) 0xC3, (byte) 0x28 }));
        Check.ok(r.startsWith("ERR") && r.contains("UTF-8"), "F11: invalid UTF-8 is refused (" + r + ")");
        r = read(strict(), frame(true, 9, text(126)));
        Check.ok(r.startsWith("ERR") && r.contains("control"), "F11: an oversized ping is refused (" + r + ")");
        r = read(strict(), frame(true, 3, new byte[0]));
        Check.ok(r.startsWith("ERR"), "F11: a reserved opcode is refused (" + r + ")");
        r = read(strict(), frame(true, 0, text(3)));
        Check.ok(r.startsWith("ERR"), "F11: a continuation without a start is refused (" + r + ")");

        // the read client keeps its defaults: binary accepted as before, big messages (well over 16 KiB) accepted
        r = read(new WebSocketClient(), frame(true, 2, "bin".getBytes(StandardCharsets.UTF_8)));
        Check.eq(r, "bin", "F11: the default (read) client still accepts a binary frame");
        r = read(new WebSocketClient(), frame(true, 1, text(200000)));
        Check.eq(r.length(), 200000, "F11: the default (read) client still accepts a 200000-byte message");
        try {
            new WebSocketClient(0, true);
            Check.ok(false, "F11: a zero limit is refused");
        } catch (IllegalArgumentException e) {
            Check.ok(true, "F11: a zero limit is refused");
        }
    }
}
