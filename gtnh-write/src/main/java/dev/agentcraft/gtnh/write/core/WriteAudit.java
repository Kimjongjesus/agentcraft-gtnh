package dev.agentcraft.gtnh.write.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

import dev.agentcraft.gtnh.write.proto.Clock;

/**
 * {@code agentcraft-write-audit.log}: the card 6 edit audit line format
 * ({@code <UTC> who=name/uuid8 action=.. where=.. before=.. after=.. detail=".."}), one line per
 * request, refusal, prompt, confirm, cancel, result, arm, disarm, lock, unlock and verify.
 * Control characters are flattened, fields are capped, the file is created 0600 on POSIX and rolls
 * to {@code .1} past the roll size. {@link #record} returns false when the line could not be
 * written: the module treats that as "disarm" (no audit, no action). Confirm tokens and keys are
 * never passed here.
 */
public final class WriteAudit {

    public static final int FIELD_CAP = 300;

    private final File file;
    private final long rollBytes;
    private final Clock clock;
    private final Deque<String> recent = new ArrayDeque<>();
    private volatile String lastError = "";
    private long lines;

    public WriteAudit(File file, long rollBytes, Clock clock) {
        this.file = file;
        this.rollBytes = rollBytes;
        this.clock = clock;
    }

    public File file() {
        return file;
    }

    public String lastError() {
        return lastError;
    }

    public synchronized long lines() {
        return lines;
    }

    /** One printable line: control characters and the section sign become spaces, long values are cut. */
    static String flat(String s) {
        if (s == null || s.isEmpty()) return "-";
        StringBuilder b = new StringBuilder(Math.min(s.length(), FIELD_CAP + 8));
        for (int i = 0; i < s.length() && b.length() <= FIELD_CAP; i++) {
            char c = s.charAt(i);
            b.append(c < 32 || c == 127 || c == '\u00a7' || c == '\u2028' || c == '\u2029' || (c >= 0x80 && c < 0xa0) ? ' ' : c);
        }
        String t = b.toString();
        return t.length() > FIELD_CAP ? t.substring(0, FIELD_CAP) + "..." : t;
    }

    public static String utc(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    /** {@code name/uuid8} for a player, "console" when there is none. */
    public static String who(String name, String uuid) {
        if (name == null && uuid == null) return "console";
        String u = uuid == null ? "" : uuid.length() > 8 ? uuid.substring(0, 8) : uuid;
        return (name == null ? "?" : name) + "/" + u;
    }

    public static String line(long ms, String who, String action, String target, String before, String after, String detail) {
        return utc(ms) + " who=" + flat(who) + " action=" + flat(action) + " where=" + flat(target) + " before=" + flat(before) + " after=" + flat(after)
            + " detail=\"" + flat(detail).replace('"', '\'') + "\"";
    }

    /** Appends one line. false = it could not be written (lastError says why). */
    public synchronized boolean record(String who, String action, String target, String before, String after, String detail) {
        String line = line(clock.now(), who, action, target, before, after, detail);
        recent.addFirst(line);
        while (recent.size() > 64) recent.removeLast();
        lines++;
        try {
            Path p = file.toPath();
            File dir = file.getAbsoluteFile().getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
            if (Files.exists(p) && Files.size(p) > rollBytes) {
                Path old = new File(file.getPath() + ".1").toPath();
                Files.deleteIfExists(old);
                Files.move(p, old);
            }
            ensureFile(p);
            Files.write(p, (line + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND, StandardOpenOption.WRITE);
            lastError = "";
            return true;
        } catch (IOException | RuntimeException e) {
            lastError = e.toString();
            return false;
        }
    }

    private static void ensureFile(Path p) throws IOException {
        if (Files.exists(p)) {
            try {
                Set<PosixFilePermission> perms = Files.getPosixFilePermissions(p);
                Set<PosixFilePermission> want = PosixFilePermissions.fromString("rw-------");
                if (!perms.equals(want)) Files.setPosixFilePermissions(p, want);
            } catch (UnsupportedOperationException e) {
                // not POSIX
            }
            return;
        }
        try {
            Files.createFile(p, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            Files.createFile(p);
        }
    }

    /** true when the file can be appended to right now (opens it for append, writes nothing). */
    public synchronized boolean writable() {
        try {
            File dir = file.getAbsoluteFile().getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
            ensureFile(file.toPath());
            Files.newOutputStream(file.toPath(), StandardOpenOption.APPEND, StandardOpenOption.WRITE).close();
            return true;
        } catch (IOException | RuntimeException e) {
            lastError = e.toString();
            return false;
        }
    }

    public synchronized List<String> recent(int n) {
        List<String> out = new ArrayList<>();
        for (String s : recent) {
            if (out.size() >= n) break;
            out.add(s);
        }
        return out;
    }
}
