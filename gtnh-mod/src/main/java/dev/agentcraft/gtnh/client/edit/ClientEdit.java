package dev.agentcraft.gtnh.client.edit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import dev.agentcraft.gtnh.edit.Json;
import dev.agentcraft.gtnh.net.Net;

/**
 * Card 6, client mirror of the edit tool: the last editor view the server sent (lock, undo/redo,
 * anchors, snapshots, presets, imports, last result), the inspector's live resize preview and the
 * "move this panel" mode. Pure state (no client-only classes), filled by {@link Net.EditView} on
 * the netty thread; requests go out with {@link #send}. The server re-validates every request.
 */
public final class ClientEdit {

    public static volatile Map<String, Object> view = Collections.emptyMap();
    public static volatile long viewAt, viewSeq;
    public static volatile Map<String, Object> result;
    public static volatile long resultSeq;

    /** Inspector live preview: the panel at previewPos is drawn at this size / binding while it is open. */
    public static volatile int[] previewPos;
    public static volatile int previewW, previewH;
    public static volatile String previewBinding;

    /** Move mode: the panel at this position follows the next right-click on a block face. */
    public static volatile int[] moving;
    public static volatile String movingName = "";

    /** The anchor under the crosshair this frame (set by the overlay), or null. */
    public static volatile String targetAnchor;

    private ClientEdit() {}

    public static void acceptView(String json) {
        try {
            Map<String, Object> m = Json.parseObject(json);
            view = m;
            viewAt = System.currentTimeMillis();
            viewSeq++;
            Map<String, Object> r = Json.obj(m, "result");
            if (r != null) {
                result = r;
                resultSeq++;
            }
        } catch (Json.ParseException ignored) {}
    }

    public static void reset() {
        view = Collections.emptyMap();
        viewAt = 0;
        result = null;
        previewPos = null;
        moving = null;
        targetAnchor = null;
    }

    public static void send(Object... kv) {
        if (Net.CHANNEL == null) return;
        Net.CHANNEL.sendToServer(new Net.EditCmd(Json.write(Json.map(kv))));
    }

    public static boolean locked() {
        return Json.bool(view, "locked", false);
    }

    public static String str(String k) {
        return Json.str(view, k, "");
    }

    public static int num(String k) {
        return Json.integer(view, k, 0);
    }

    @SuppressWarnings("unchecked")
    public static List<String> strings(String k) {
        List<Object> l = Json.arr(view, k);
        List<String> out = new ArrayList<>();
        if (l != null) for (Object o : l) if (o instanceof String) out.add((String) o);
        return out;
    }

    /** Anchors as {name, x, y, z, yaw}. */
    public static List<Object[]> anchors() {
        List<Object> l = Json.arr(view, "anchors");
        List<Object[]> out = new ArrayList<>();
        if (l == null) return out;
        for (Object o : l) {
            if (!(o instanceof List)) continue;
            List<?> e = (List<?>) o;
            if (e.size() < 5 || !(e.get(0) instanceof String)) continue;
            out.add(new Object[] { e.get(0), d(e.get(1)), d(e.get(2)), d(e.get(3)), d(e.get(4)) });
        }
        return out;
    }

    /** Presets as {id, name, description, panels, anchors}. */
    public static List<String[]> presets() {
        List<Object> l = Json.arr(view, "presets");
        List<String[]> out = new ArrayList<>();
        if (l == null) return out;
        for (Object o : l) {
            if (!(o instanceof List)) continue;
            List<?> e = (List<?>) o;
            if (e.size() < 5) continue;
            out.add(new String[] { String.valueOf(e.get(0)), String.valueOf(e.get(1)), String.valueOf(e.get(2)), String.valueOf(Math.round(d(e.get(3)))),
                String.valueOf(Math.round(d(e.get(4)))) });
        }
        return out;
    }

    private static double d(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0;
    }

    public static int[] sizeOverride(int x, int y, int z) {
        int[] p = previewPos;
        if (p == null || p[0] != x || p[1] != y || p[2] != z) return null;
        return new int[] { previewW, previewH };
    }

    public static String bindingOverride(int x, int y, int z) {
        int[] p = previewPos;
        if (p == null || p[0] != x || p[1] != y || p[2] != z) return null;
        return previewBinding;
    }
}
