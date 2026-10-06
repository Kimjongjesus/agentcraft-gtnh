package dev.agentcraft.gtnh.edit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The edit tool's files, all in one directory next to {@code hq-anchors.json}:
 *
 * <pre>
 * hq-layout.json         the per-world layout: panels, display options, last undo/redo steps
 * edit-lock.json         present while /agentcraft edit lock is on
 * layouts/NAME.json      named snapshots (absolute positions)
 * exports/NAME.json      shareable layouts (relative to an origin, names stripped by default)
 * imports/NAME.json      layouts to import (exports/ is also offered)
 * presets/NAME.json      your own presets (the bundled ones live in the mod jar)
 * </pre>
 *
 * Every write goes to a temp file first and is moved over the target (atomic where the file
 * system allows), so a crash never leaves half a file. Names are [a-z0-9_-]{1,32}: nothing can
 * escape the directory. Reads refuse files larger than {@link #maxBytes}.
 */
public final class FileStore implements Ports.Store {

    public static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");

    public final File dir;
    public final int maxBytes;

    public FileStore(File dir, int maxBytes) {
        this.dir = dir;
        this.maxBytes = maxBytes;
    }

    public static boolean validName(String n) {
        return n != null && NAME.matcher(n)
            .matches();
    }

    public File layoutFile() {
        return new File(dir, "hq-layout.json");
    }

    public File lockFile() {
        return new File(dir, "edit-lock.json");
    }

    public File sub(String folder, String name) throws IOException {
        if (!validName(name)) throw new IOException("names are a-z 0-9 _ - (max 32): '" + name + "'");
        return new File(new File(dir, folder), name + ".json");
    }

    public static void atomicWrite(File target, String text) throws IOException {
        File parent = target.getAbsoluteFile()
            .getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
        File tmp = new File(target.getPath() + ".tmp");
        Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public String read(File f) throws IOException {
        if (!f.isFile()) return null;
        long n = f.length();
        if (n > maxBytes) throw new IOException(f.getName() + " is " + n + " bytes, over the " + maxBytes + "-byte layout limit");
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    public void write(File f, String text) throws IOException {
        if (text.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IOException("layout JSON would be over the " + maxBytes + "-byte limit; not written");
        }
        atomicWrite(f, text);
    }

    public List<String> list(String folder) {
        File[] fs = new File(dir, folder).listFiles();
        List<String> out = new ArrayList<>();
        if (fs == null) return out;
        for (File f : fs) {
            String n = f.getName();
            if (f.isFile() && n.endsWith(".json")) {
                String base = n.substring(0, n.length() - 5);
                if (validName(base)) out.add(base);
            }
        }
        Collections.sort(out);
        return out;
    }

    @Override
    public void saveLayout(String json) throws IOException {
        write(layoutFile(), json);
    }

    @Override
    public String loadLayout() throws IOException {
        return read(layoutFile());
    }

    @Override
    public void saveSnapshot(String name, String json) throws IOException {
        write(sub("layouts", name), json);
    }

    @Override
    public String loadSnapshot(String name) throws IOException {
        return read(sub("layouts", name));
    }

    @Override
    public List<String> snapshots() {
        return list("layouts");
    }

    @Override
    public void saveLock(String json) throws IOException {
        if (json == null) {
            Files.deleteIfExists(lockFile().toPath());
        } else {
            atomicWrite(lockFile(), json);
        }
    }

    @Override
    public String loadLock() throws IOException {
        return read(lockFile());
    }
}
