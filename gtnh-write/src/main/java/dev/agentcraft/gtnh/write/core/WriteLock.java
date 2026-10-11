package dev.agentcraft.gtnh.write.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.agentcraft.gtnh.write.proto.Clock;
import dev.agentcraft.gtnh.write.proto.Fields;
import dev.agentcraft.gtnh.write.proto.StrictJson;

/**
 * The game-side write lock, persisted as {@code write-lock.json}. Fail closed: only a positively absent
 * file (NoSuchFileException on a no-follow attribute read) means unlocked; anything else - unreadable,
 * access denied, indeterminate, a symlink (dangling or not), a directory or other non-regular file, or a
 * file that is not exactly {@code {locked:true,...}} - means LOCKED.
 * Any op may lock; only the owner or the console may unlock. A lock held in memory stays held even
 * if the file cannot be written.
 */
public final class WriteLock {

    private final File file;
    private final Clock clock;
    private boolean locked;
    private String info = "";
    private long since;

    public WriteLock(File file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    public File file() {
        return file;
    }

    public synchronized boolean isLocked() {
        return locked;
    }

    public synchronized String info() {
        return info;
    }

    public synchronized long since() {
        return since;
    }

    /** Reads the file (start-up and on every re-check); an unreadable file locks. */
    public synchronized void load() {
        Path p = file.toPath();
        try {
            // only a positively absent file (NoSuchFileException) means "no lock"; the link itself is looked at (a
            // dangling symlink is not "absent"), and anything that cannot be determined is a lock
            BasicFileAttributes at = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!at.isRegularFile()) throw new IOException(at.isSymbolicLink() ? "lock path is a symlink" : "lock path is not a regular file");
            if (at.size() > 65536) throw new IOException("lock file too large");
            Map<String, Object> m = StrictJson.parseObject(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
            Fields f = new Fields(m, "lock");
            boolean l = f.bool("locked");
            String by = f.str("by", 0, 200);
            String reason = f.str("reason", 0, 400);
            long s = f.integer("since", 0, Long.MAX_VALUE);
            f.done();
            if (!l) throw new IOException("lock file says locked=false (delete it with /agentcraft write unlock)");
            locked = true;
            info = "by " + by + (reason.isEmpty() ? "" : ": " + reason);
            since = s;
        } catch (NoSuchFileException e) {
            // a lock that is held in memory is not released by a vanished file (only unlock does that)
        } catch (IOException | StrictJson.ParseException | Fields.Bad | RuntimeException e) {
            if (!locked) since = clock.now();
            locked = true;
            info = "lock file unreadable (" + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")";
        }
    }

    /** Sets the lock (always succeeds in memory) and persists it; the text says if the file could not be written. */
    public synchronized String lock(String by, String reason) {
        if (!locked) since = clock.now();
        locked = true;
        info = "by " + by + (reason == null || reason.isEmpty() ? "" : ": " + reason);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("locked", Boolean.TRUE);
        m.put("by", cap(by, 200));
        m.put("reason", cap(reason == null ? "" : reason, 400));
        m.put("since", Long.valueOf(since));
        try {
            write(StrictJson.write(m));
            return "locked";
        } catch (IOException e) {
            return "locked in memory only (lock file not written: " + e.getMessage() + ")";
        }
    }

    /** Clears the lock if {@code isOwner || isConsole}; null = done, else why not. */
    public synchronized String unlock(boolean isOwner, boolean isConsole) {
        if (!isOwner && !isConsole) return "only the owner or the console may unlock";
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            return "lock file could not be removed (" + e.getMessage() + "); the lock stays";
        }
        locked = false;
        info = "";
        since = 0;
        return null;
    }

    private void write(String json) throws IOException {
        File dir = file.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        Path tmp = new File(file.getPath() + ".tmp").toPath();
        Files.deleteIfExists(tmp);
        try {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            Files.createFile(tmp);
        }
        Files.write(tmp, (json + "\n").getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static String cap(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }
}
