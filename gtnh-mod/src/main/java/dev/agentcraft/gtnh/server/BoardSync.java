package dev.agentcraft.gtnh.server;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayerMP;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.agentcraft.gtnh.AgentCraftGTNH;
import dev.agentcraft.gtnh.Config;
import dev.agentcraft.gtnh.net.Net;
import dev.agentcraft.gtnh.state.HqData;

/**
 * Card 3 server state: tasks, goals (one per board) and library notes from the adapter, replicated
 * to clients as two binary blobs (board, library). Read-only: nothing flows back to the adapter.
 *
 * <p>
 * Throttle: a blob is re-encoded at most once per {@code boardSyncSeconds} and only while something
 * changed; it is sent only if its bytes differ from the last one sent (change-only). Players who
 * just logged in get the current blobs. Bounds: tasks {@link HqData#MAX_TASKS} (cancelled first,
 * then the oldest done cards are dropped), notes {@link HqData#MAX_NOTES}, blob size
 * {@link Net#MAX_BLOB} (tasks past the cap are left out, done cards last).
 */
public final class BoardSync {

    private final Map<String, HqData.Task> tasks = new LinkedHashMap<>();
    private final Map<String, HqData.Goal> goals = new LinkedHashMap<>();
    private final Map<String, HqData.Note> notes = new LinkedHashMap<>();
    private final Set<EntityPlayerMP> needsFull = new HashSet<>();
    private boolean boardDirty, libraryDirty;
    private long lastBoardSend, lastLibrarySend;
    private byte[] lastBoard = new byte[0], lastLibrary = new byte[0];
    private int gen;
    public long taskMessages, goalMessages, memoryMessages, boardBlobs, libraryBlobs, blobPackets, droppedTasks;
    public int lastBoardBytes, lastLibraryBytes;

    public void reset() {
        tasks.clear();
        goals.clear();
        notes.clear();
        needsFull.clear();
        lastBoard = new byte[0];
        lastLibrary = new byte[0];
        boardDirty = libraryDirty = false;
        taskMessages = goalMessages = memoryMessages = boardBlobs = libraryBlobs = blobPackets = droppedTasks = 0;
    }

    public Map<String, HqData.Task> tasks() {
        return Collections.unmodifiableMap(tasks);
    }

    public Map<String, HqData.Goal> goals() {
        return Collections.unmodifiableMap(goals);
    }

    public Map<String, HqData.Note> notes() {
        return Collections.unmodifiableMap(notes);
    }

    /** Board slugs the adapter knows (for bind validation / tab completion). */
    public List<String> boards() {
        List<String> out = new ArrayList<>();
        for (HqData.Goal g : goals.values()) if (!g.board.isEmpty()) out.add(g.board);
        return out;
    }

    /** Summary for one board, or all boards when board is empty / "all". */
    public HqData.Goal summary(String board) {
        List<HqData.Goal> pick = new ArrayList<>();
        for (HqData.Goal g : goals.values()) {
            if (board == null || board.isEmpty() || "all".equals(board) || board.equals(g.board)) pick.add(g);
        }
        if (pick.size() == 1) return pick.get(0);
        return HqData.Goal.sum(pick);
    }

    // ---- protocol -------------------------------------------------------------------------

    void applySnapshot(JsonObject m) {
        tasks.clear();
        for (JsonElement el : array(m, "tasks")) if (el.isJsonObject()) putTask(HqData.Task.fromJson(el.getAsJsonObject()));
        goals.clear();
        for (JsonElement el : array(m, "goals")) if (el.isJsonObject()) putGoal(HqData.Goal.fromJson(el.getAsJsonObject()));
        notes.clear();
        for (JsonElement el : array(m, "memory")) if (el.isJsonObject()) putNote(el.getAsJsonObject());
        boardDirty = libraryDirty = true;
    }

    /** task.upsert / goal.upsert / memory.upsert; false if the type is not ours. */
    boolean apply(String type, JsonObject m) {
        switch (type) {
            case "task.upsert":
                taskMessages++;
                if (m.has("task") && m.get("task")
                    .isJsonObject()) putTask(HqData.Task.fromJson(m.getAsJsonObject("task")));
                boardDirty = true;
                return true;
            case "goal.upsert":
                goalMessages++;
                if (m.has("goal") && m.get("goal")
                    .isJsonObject()) putGoal(HqData.Goal.fromJson(m.getAsJsonObject("goal")));
                boardDirty = true;
                return true;
            case "memory.upsert":
                memoryMessages++;
                if (m.has("entry") && m.get("entry")
                    .isJsonObject()) putNote(m.getAsJsonObject("entry"));
                libraryDirty = true;
                return true;
            default:
                return false;
        }
    }

    private static JsonArray array(JsonObject o, String k) {
        return o.has(k) && o.get(k)
            .isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }

    private void putTask(HqData.Task t) {
        if (t.id.isEmpty()) return;
        tasks.remove(t.id);
        tasks.put(t.id, t);
        if (tasks.size() <= HqData.MAX_TASKS) return;
        // over the cap: drop cancelled cards first, then the oldest done ones, then the oldest
        List<HqData.Task> all = new ArrayList<>(tasks.values());
        Collections.sort(all, (a, b) -> {
            int ra = rankForDrop(a), rb = rankForDrop(b);
            return ra != rb ? Integer.compare(ra, rb) : Long.compare(a.updatedAt, b.updatedAt);
        });
        for (int i = 0; tasks.size() > HqData.MAX_TASKS && i < all.size(); i++) {
            tasks.remove(all.get(i).id);
            droppedTasks++;
        }
    }

    private static int rankForDrop(HqData.Task t) {
        int c = HqData.column(t.status);
        return c < 0 ? 0 : c == 3 ? 1 : 2;
    }

    private void putGoal(HqData.Goal g) {
        if (g.id.isEmpty()) return;
        goals.put(g.id, g);
        while (goals.size() > HqData.MAX_GOALS) goals.remove(
            goals.keySet()
                .iterator()
                .next());
    }

    private void putNote(JsonObject o) {
        HqData.Note n = HqData.Note.fromJson(o);
        if (n.id.isEmpty()) return;
        JsonElement removed = o.get("removed");
        notes.remove(n.id);
        if (removed != null && removed.isJsonPrimitive() && removed.getAsBoolean()) return; // tombstone
        notes.put(n.id, n);
        if (notes.size() > HqData.MAX_NOTES) {
            List<HqData.Note> all = new ArrayList<>(notes.values());
            Collections.sort(all, (a, b) -> Long.compare(a.updated, b.updated));
            for (int i = 0; notes.size() > HqData.MAX_NOTES && i < all.size(); i++) notes.remove(all.get(i).id);
        }
    }

    // ---- replication ----------------------------------------------------------------------

    void playerJoined(EntityPlayerMP p) {
        needsFull.add(p);
    }

    void tick(int tick) {
        long now = System.currentTimeMillis();
        long minGap = Config.boardSyncSeconds * 1000L;
        if (boardDirty && now - lastBoardSend >= minGap) {
            boardDirty = false;
            lastBoardSend = now;
            byte[] b = encodeBoard();
            if (!Arrays.equals(b, lastBoard)) {
                lastBoard = b;
                lastBoardBytes = b.length;
                boardBlobs++;
                send(Net.Blob.BOARD, b, null);
            }
        }
        if (libraryDirty && now - lastLibrarySend >= minGap) {
            libraryDirty = false;
            lastLibrarySend = now;
            byte[] b = encodeLibrary();
            if (!Arrays.equals(b, lastLibrary)) {
                lastLibrary = b;
                lastLibraryBytes = b.length;
                libraryBlobs++;
                send(Net.Blob.LIBRARY, b, null);
            }
        }
        if (!needsFull.isEmpty() && tick % 20 == 0) {
            for (EntityPlayerMP p : needsFull) {
                if (lastBoard.length > 0) send(Net.Blob.BOARD, lastBoard, p);
                if (lastLibrary.length > 0) send(Net.Blob.LIBRARY, lastLibrary, p);
            }
            needsFull.clear();
        }
    }

    private void send(byte kind, byte[] data, EntityPlayerMP to) {
        gen++;
        List<Net.Blob> parts = Net.Blob.split(kind, gen, data);
        for (Net.Blob part : parts) {
            if (to == null) Net.sendToAll(part);
            else Net.sendTo(part, to);
            blobPackets++;
        }
        if (Config.verboseLog && to == null) {
            AgentCraftGTNH.LOG.info(
                "{} sync: {} bytes in {} packet(s) ({} tasks, {} goals, {} notes)",
                kind == Net.Blob.BOARD ? "board" : "library",
                data.length,
                parts.size(),
                tasks.size(),
                goals.size(),
                notes.size());
        }
    }

    /** Wall order: open columns first, newest first within; done last so the size cap drops them first. */
    private List<HqData.Task> wallOrder() {
        List<HqData.Task> out = new ArrayList<>();
        for (HqData.Task t : tasks.values()) if (HqData.column(t.status) >= 0) out.add(t);
        Collections.sort(out, (a, b) -> {
            boolean da = "done".equals(a.status), db = "done".equals(b.status);
            if (da != db) return da ? 1 : -1;
            return Long.compare(b.updatedAt, a.updatedAt);
        });
        return out;
    }

    byte[] encodeBoard() {
        List<HqData.Task> order = wallOrder();
        List<HqData.Goal> gl = new ArrayList<>(goals.values());
        // shrink until it fits the blob cap (worst case: huge multi-byte titles)
        int n = order.size();
        while (true) {
            final int k = n;
            byte[] b = encode(out -> HqData.writeBoard(out, order.subList(0, k), gl));
            if (b.length <= Net.MAX_BLOB || n == 0) return b;
            n = n * 3 / 4;
        }
    }

    byte[] encodeLibrary() {
        List<HqData.Note> all = new ArrayList<>(notes.values());
        Collections.sort(all, (a, b) -> Long.compare(b.updated, a.updated));
        int n = all.size();
        while (true) {
            final int k = n;
            byte[] b = encode(out -> HqData.writeLibrary(out, all.subList(0, k)));
            if (b.length <= Net.MAX_BLOB || n == 0) return b;
            n = n * 3 / 4;
        }
    }

    private interface Writer {

        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] encode(Writer w) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            w.write(out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bos.toByteArray();
    }
}
