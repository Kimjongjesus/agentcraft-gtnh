package dev.agentcraft.gtnh.edit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * {@code agentcraft-edit-audit.log}: one line per placement, removal, update, anchor or display
 * change, refusal, lock and unlock: UTC time, who, action, where, block id:meta before and after,
 * detail. Append-only; the file rolls to {@code .1} past {@link #rollBytes}. The newest lines are
 * also kept in memory for the editor screen. Values are flattened to one printable line.
 */
public final class AuditLog implements Ports.Audit {

    private final File file;
    private final long rollBytes;
    private final Deque<String> recent = new ArrayDeque<>();
    public String lastError = "";
    public long lines;

    public AuditLog(File file, long rollBytes) {
        this.file = file;
        this.rollBytes = rollBytes;
    }

    public File file() {
        return file;
    }

    private static String flat(String s) {
        if (s == null || s.isEmpty()) return "-";
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) b.append(c < 32 || c == 127 || c == '\u00a7' ? ' ' : c);
        String t = b.toString();
        return t.length() > 300 ? t.substring(0, 300) + "..." : t;
    }

    public static String utc(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    @Override
    public synchronized void record(String who, String action, String where, String before, String after, String detail) {
        String line = utc(System.currentTimeMillis()) + " who="
            + flat(who)
            + " action="
            + flat(action)
            + " where="
            + flat(where)
            + " before="
            + flat(before)
            + " after="
            + flat(after)
            + " detail=\""
            + flat(detail).replace('"', '\'')
            + "\"";
        recent.addFirst(line);
        while (recent.size() > 32) recent.removeLast();
        lines++;
        try {
            File dir = file.getAbsoluteFile()
                .getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            if (file.length() > rollBytes) {
                File old = new File(file.getPath() + ".1");
                Files.deleteIfExists(old.toPath());
                if (!file.renameTo(old)) lastError = "could not roll " + file;
            }
            Files.write(
                file.toPath(),
                (line + "\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE);
        } catch (IOException e) {
            lastError = e.toString();
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
