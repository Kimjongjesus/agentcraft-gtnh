package dev.agentcraft.gtnh.state;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.agentcraft.gtnh.AgentCraftGTNH;
import dev.agentcraft.gtnh.net.Net;

/**
 * Client mirror of the card 3 data (tasks, goals, library notes), rebuilt from blobs the server
 * sends (Net.Blob). Lists are immutable snapshots swapped atomically; renderers and screens read
 * them without locks. {@link #version} bumps on every swap (renderers cache layouts by it).
 */
public final class ClientHq {

    public static volatile List<HqData.Task> tasks = Collections.emptyList();
    public static volatile List<HqData.Goal> goals = Collections.emptyList();
    public static volatile List<HqData.Note> notes = Collections.emptyList();
    public static volatile long version, boardUpdates, libraryUpdates, lastBoardAt, lastLibraryAt;
    public static volatile boolean haveBoard, haveLibrary;

    private static final Object LOCK = new Object();
    private static final Assembly BOARD = new Assembly(), LIBRARY = new Assembly();

    private ClientHq() {}

    private static final class Assembly {

        int gen = Integer.MIN_VALUE;
        byte[][] parts;
        int got;
    }

    public static void acceptPart(byte kind, int gen, int index, int count, byte[] data) {
        if (count <= 0 || count > Net.Blob.MAX_PARTS || index < 0 || index >= count) return;
        Assembly a = kind == Net.Blob.BOARD ? BOARD : kind == Net.Blob.LIBRARY ? LIBRARY : null;
        if (a == null) return;
        byte[] whole = null;
        synchronized (LOCK) {
            if (a.gen != gen || a.parts == null || a.parts.length != count) {
                a.gen = gen;
                a.parts = new byte[count][];
                a.got = 0;
            }
            if (a.parts[index] == null) {
                a.parts[index] = data;
                a.got++;
            }
            if (a.got == count) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                for (byte[] p : a.parts) bos.write(p, 0, p.length);
                whole = bos.toByteArray();
                a.parts = null;
                a.got = 0;
            }
        }
        if (whole == null) return;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(whole))) {
            if (kind == Net.Blob.BOARD) {
                List<HqData.Task> t = new ArrayList<>();
                List<HqData.Goal> g = new ArrayList<>();
                HqData.readBoard(in, t, g);
                tasks = Collections.unmodifiableList(t);
                goals = Collections.unmodifiableList(g);
                haveBoard = true;
                boardUpdates++;
                lastBoardAt = System.currentTimeMillis();
            } else {
                List<HqData.Note> n = new ArrayList<>();
                HqData.readLibrary(in, n);
                notes = Collections.unmodifiableList(n);
                haveLibrary = true;
                libraryUpdates++;
                lastLibraryAt = System.currentTimeMillis();
            }
            version++;
        } catch (IOException | RuntimeException e) {
            AgentCraftGTNH.LOG.warn("ignoring malformed {} blob: {}", kind == Net.Blob.BOARD ? "board" : "library", e.toString());
        }
    }

    /** Left the server: forget its board and library (a different server must not show them). */
    public static void reset() {
        synchronized (LOCK) {
            BOARD.parts = LIBRARY.parts = null;
            BOARD.got = LIBRARY.got = 0;
            BOARD.gen = LIBRARY.gen = Integer.MIN_VALUE;
        }
        tasks = Collections.emptyList();
        goals = Collections.emptyList();
        notes = Collections.emptyList();
        haveBoard = haveLibrary = false;
        version++;
    }

    /** "" or "all" = every board. */
    public static boolean onBoard(String binding, String board) {
        return binding == null || binding.isEmpty() || "all".equals(binding) || binding.equals(board);
    }

    public static List<HqData.Task> tasksFor(String binding) {
        List<HqData.Task> out = new ArrayList<>();
        for (HqData.Task t : tasks) if (onBoard(binding, t.board) && HqData.column(t.status) >= 0) out.add(t);
        return out;
    }

    /** Atrium / beacon summary for a board binding (sum over all boards for "" / "all"). */
    public static HqData.Goal summary(String binding) {
        List<HqData.Goal> pick = new ArrayList<>();
        for (HqData.Goal g : goals) if (onBoard(binding, g.board)) pick.add(g);
        if (pick.size() == 1) return pick.get(0);
        HqData.Goal s = HqData.Goal.sum(pick);
        if (!onBoard(binding, "") && pick.isEmpty()) s.text = "Board " + binding;
        return s;
    }

    public static HqData.Task task(String id) {
        for (HqData.Task t : tasks) if (t.id.equals(id)) return t;
        return null;
    }
}
