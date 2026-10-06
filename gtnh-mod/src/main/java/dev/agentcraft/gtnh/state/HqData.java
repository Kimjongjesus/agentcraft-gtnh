package dev.agentcraft.gtnh.state;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Card 3 view model shared by server and client: tasks (Task Wall), goals (Atrium; one per Kanban
 * board), library notes (Library). Parsed from the adapter's JSON on the server, then replicated to
 * clients as a compact binary blob (see {@link dev.agentcraft.gtnh.net.Net.Blob}). Every string is
 * sanitized (no formatting codes / control characters) and clipped; every list is bounded.
 */
public final class HqData {

    public static final String[] COLUMNS = { "todo", "doing", "review", "done", "blocked" };
    public static final int MAX_TASKS = 256, MAX_DEPS = 8, MAX_GOALS = 16, MAX_NOTES = 64;

    private HqData() {}

    public static int column(String status) {
        for (int i = 0; i < COLUMNS.length; i++) if (COLUMNS[i].equals(status)) return i;
        return -1; // cancelled (or unknown): never shown on the wall
    }

    static String str(JsonObject o, String k, int max) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return "";
        return AgentInfo.sanitize(e.getAsString(), max);
    }

    static long lng(JsonObject o, String k) {
        JsonElement e = o.get(k);
        try {
            return e == null || !e.isJsonPrimitive() ? 0L : e.getAsLong();
        } catch (NumberFormatException | UnsupportedOperationException ex) {
            return 0L;
        }
    }

    static int num(JsonObject o, String k) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, lng(o, k)));
    }

    static String readUTF(DataInputStream in, int max) throws IOException {
        return AgentInfo.sanitize(in.readUTF(), max);
    }

    static void writeUTF(DataOutputStream out, String s, int max) throws IOException {
        out.writeUTF(s == null ? "" : s.length() > max ? s.substring(0, max) : s);
    }

    // ---------------------------------------------------------------------------------------

    /** A Kanban card as the wall shows it. */
    public static final class Task {

        public String id = "", title = "", status = "todo", assignee = "", board = "", goalId = "";
        public String description = "", summary = "", blockedReason = "", branch = "";
        public int priority;
        public long updatedAt;
        public final List<String> deps = new ArrayList<>();

        public static Task fromJson(JsonObject o) {
            Task t = new Task();
            t.id = str(o, "id", 64);
            t.title = str(o, "title", 120);
            t.status = str(o, "status", 16);
            t.assignee = str(o, "assignee", 64);
            t.board = str(o, "board", 32);
            t.goalId = str(o, "goalId", 64);
            t.description = str(o, "description", 300);
            t.summary = str(o, "summary", 300);
            t.blockedReason = str(o, "blockedReason", 200);
            t.branch = str(o, "branch", 80);
            t.priority = num(o, "priority");
            t.updatedAt = lng(o, "updatedAt");
            if (o.has("deps") && o.get("deps")
                .isJsonArray()) {
                JsonArray a = o.getAsJsonArray("deps");
                for (int i = 0; i < a.size() && t.deps.size() < MAX_DEPS; i++) {
                    if (a.get(i)
                        .isJsonPrimitive()) t.deps.add(
                            AgentInfo.sanitize(
                                a.get(i)
                                    .getAsString(),
                                64));
                }
            }
            return t;
        }

        void write(DataOutputStream out) throws IOException {
            writeUTF(out, id, 64);
            writeUTF(out, title, 120);
            writeUTF(out, status, 16);
            writeUTF(out, assignee, 64);
            writeUTF(out, board, 32);
            writeUTF(out, goalId, 64);
            writeUTF(out, description, 300);
            writeUTF(out, summary, 300);
            writeUTF(out, blockedReason, 200);
            writeUTF(out, branch, 80);
            out.writeInt(priority);
            out.writeLong(updatedAt);
            int n = Math.min(deps.size(), MAX_DEPS);
            out.writeByte(n);
            for (int i = 0; i < n; i++) writeUTF(out, deps.get(i), 64);
        }

        static Task read(DataInputStream in) throws IOException {
            Task t = new Task();
            t.id = readUTF(in, 64);
            t.title = readUTF(in, 120);
            t.status = readUTF(in, 16);
            t.assignee = readUTF(in, 64);
            t.board = readUTF(in, 32);
            t.goalId = readUTF(in, 64);
            t.description = readUTF(in, 300);
            t.summary = readUTF(in, 300);
            t.blockedReason = readUTF(in, 200);
            t.branch = readUTF(in, 80);
            t.priority = in.readInt();
            t.updatedAt = in.readLong();
            int n = Math.min(in.readUnsignedByte(), MAX_DEPS);
            for (int i = 0; i < n; i++) t.deps.add(readUTF(in, 64));
            return t;
        }
    }

    /** One goal per Kanban board: progress, wall-column counts, open decision COUNT (no text). */
    public static final class Goal {

        public String id = "", board = "", text = "", status = "active";
        public float progress;
        public final int[] counts = new int[COLUMNS.length];
        public int total, openDecisions;
        public long updatedAt;

        public static Goal fromJson(JsonObject o) {
            Goal g = new Goal();
            g.id = str(o, "id", 64);
            g.board = str(o, "board", 32);
            g.text = str(o, "text", 80);
            g.status = str(o, "status", 16);
            try {
                g.progress = o.has("progress") ? Math.max(
                    0.0F,
                    Math.min(
                        1.0F,
                        o.get("progress")
                            .getAsFloat()))
                    : 0.0F;
            } catch (RuntimeException e) {
                g.progress = 0.0F;
            }
            if (o.has("counts") && o.get("counts")
                .isJsonObject()) {
                JsonObject c = o.getAsJsonObject("counts");
                for (int i = 0; i < COLUMNS.length; i++) g.counts[i] = Math.max(0, num(c, COLUMNS[i]));
            }
            g.total = Math.max(0, num(o, "total"));
            g.openDecisions = Math.max(0, num(o, "openDecisions"));
            g.updatedAt = lng(o, "updatedAt");
            return g;
        }

        void write(DataOutputStream out) throws IOException {
            writeUTF(out, id, 64);
            writeUTF(out, board, 32);
            writeUTF(out, text, 80);
            writeUTF(out, status, 16);
            out.writeFloat(progress);
            for (int c : counts) out.writeInt(c);
            out.writeInt(total);
            out.writeInt(openDecisions);
            out.writeLong(updatedAt);
        }

        static Goal read(DataInputStream in) throws IOException {
            Goal g = new Goal();
            g.id = readUTF(in, 64);
            g.board = readUTF(in, 32);
            g.text = readUTF(in, 80);
            g.status = readUTF(in, 16);
            g.progress = Math.max(0.0F, Math.min(1.0F, in.readFloat()));
            for (int i = 0; i < COLUMNS.length; i++) g.counts[i] = Math.max(0, in.readInt());
            g.total = Math.max(0, in.readInt());
            g.openDecisions = Math.max(0, in.readInt());
            g.updatedAt = in.readLong();
            return g;
        }

        /** Sum of several goals (the "all boards" view). */
        public static Goal sum(List<Goal> goals) {
            Goal s = new Goal();
            s.id = "all";
            s.board = "";
            s.text = "All boards";
            long done = 0;
            for (Goal g : goals) {
                for (int i = 0; i < COLUMNS.length; i++) s.counts[i] += g.counts[i];
                s.total += g.total;
                s.openDecisions += g.openDecisions;
                s.updatedAt = Math.max(s.updatedAt, g.updatedAt);
                done += g.counts[3];
            }
            s.progress = s.total == 0 ? 0.0F : (float) done / s.total;
            s.status = s.total == 0 ? "planning" : done == s.total ? "done" : "active";
            return s;
        }
    }

    /** A library note (protocol MemoryEntry), already filtered by the adapter. */
    public static final class Note {

        public String id = "", scope = "", title = "", body = "", author = "", kind = "", board = "", taskId = "";
        public long updated;

        public static Note fromJson(JsonObject o) {
            Note n = new Note();
            n.id = str(o, "id", 128);
            n.scope = str(o, "scope", 64);
            n.title = str(o, "title", 120);
            n.body = str(o, "body", 1500);
            n.author = str(o, "author", 64);
            n.kind = str(o, "kind", 16);
            n.board = str(o, "board", 32);
            n.taskId = str(o, "taskId", 64);
            n.updated = lng(o, "updated");
            return n;
        }

        void write(DataOutputStream out) throws IOException {
            writeUTF(out, id, 128);
            writeUTF(out, scope, 64);
            writeUTF(out, title, 120);
            writeUTF(out, body, 1500);
            writeUTF(out, author, 64);
            writeUTF(out, kind, 16);
            writeUTF(out, board, 32);
            writeUTF(out, taskId, 64);
            out.writeLong(updated);
        }

        static Note read(DataInputStream in) throws IOException {
            Note n = new Note();
            n.id = readUTF(in, 128);
            n.scope = readUTF(in, 64);
            n.title = readUTF(in, 120);
            n.body = readUTF(in, 1500);
            n.author = readUTF(in, 64);
            n.kind = readUTF(in, 16);
            n.board = readUTF(in, 32);
            n.taskId = readUTF(in, 64);
            n.updated = in.readLong();
            return n;
        }
    }

    // ---- wire: board blob (tasks + goals) and library blob ---------------------------------

    public static final int BOARD_VERSION = 1, LIBRARY_VERSION = 1;

    public static void writeBoard(DataOutputStream out, List<Task> tasks, List<Goal> goals) throws IOException {
        out.writeByte(BOARD_VERSION);
        int ng = Math.min(goals.size(), MAX_GOALS);
        out.writeByte(ng);
        for (int i = 0; i < ng; i++) goals.get(i)
            .write(out);
        int nt = Math.min(tasks.size(), MAX_TASKS);
        out.writeShort(nt);
        for (int i = 0; i < nt; i++) tasks.get(i)
            .write(out);
    }

    public static void readBoard(DataInputStream in, List<Task> tasks, List<Goal> goals) throws IOException {
        if (in.readUnsignedByte() != BOARD_VERSION) throw new IOException("board blob version");
        int ng = Math.min(in.readUnsignedByte(), MAX_GOALS);
        for (int i = 0; i < ng; i++) goals.add(Goal.read(in));
        int nt = Math.min(in.readUnsignedShort(), MAX_TASKS);
        for (int i = 0; i < nt; i++) tasks.add(Task.read(in));
    }

    public static void writeLibrary(DataOutputStream out, List<Note> notes) throws IOException {
        out.writeByte(LIBRARY_VERSION);
        int n = Math.min(notes.size(), MAX_NOTES);
        out.writeByte(n);
        for (int i = 0; i < n; i++) notes.get(i)
            .write(out);
    }

    public static void readLibrary(DataInputStream in, List<Note> notes) throws IOException {
        if (in.readUnsignedByte() != LIBRARY_VERSION) throw new IOException("library blob version");
        int n = Math.min(in.readUnsignedByte(), MAX_NOTES);
        for (int i = 0; i < n; i++) notes.add(Note.read(in));
    }
}
