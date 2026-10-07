package dev.agentcraft.gtnh.ui.panel;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.agentcraft.gtnh.AgentCraftGTNH;

/**
 * The office layout file, {@code config/agentcraftgtnh/office-layout.json} (client side; written
 * with the defaults on first start, re-read within a few seconds after a hand edit). It says which
 * panel each block kind shows, in which theme and resolution, the level-of-detail thresholds, and
 * optional per-block overrides by position. Card 6's edit tool keeps the server's hq-layout.json
 * instead; its display options and per-panel theme override this file, which stays the local
 * default. Renderers never hard-code which block shows what.
 *
 * <pre>
 * {
 *   "version": 1,
 *   "theme": "dark",                       // dark | light (default for every panel)
 *   "lod": {"nearPx": 110, "midPx": 85},   // on-screen pixels per block for full detail / headers
 *   "blocks": {
 *     "task_wall":   {"panel": "kanban",        "pxPerBlock": 128},
 *     "goal_atrium": {"panel": "goal",          "pxPerBlock": 128},
 *     "monitor":     {"panel": "agent-monitor", "pxPerBlock": 128}
 *   },
 *   "instances": {                         // optional, per block position "dim:x,y,z"
 *     "0:-72,4,-221": {"panel": "goal", "theme": "light"}
 *   }
 * }
 * </pre>
 */
public final class PanelLayout {

    public static final class Entry {

        public final String panel, theme;
        public final int pxPerBlock;

        Entry(String panel, String theme, int pxPerBlock) {
            this.panel = panel;
            this.theme = theme;
            this.pxPerBlock = pxPerBlock;
        }
    }

    private static final String[][] DEFAULTS = { { "task_wall", "kanban" }, { "goal_atrium", "goal" }, { "monitor", "agent-monitor" },
        // card 5b: ops panels
        { "fleet_board", "ops-fleet" }, { "cron_board", "ops-cron" }, { "usage_panel", "ops-usage" }, { "alert_feed", "ops-alerts" } };

    private static File file;
    private static long loadedMtime = Long.MIN_VALUE, lastCheck;
    private static String theme = "dark";
    public static double nearPx = 110, midPx = 85;
    private static final Map<String, Entry> BLOCKS = new HashMap<>();
    private static final Map<String, Entry> INSTANCES = new HashMap<>();

    private PanelLayout() {}

    public static void init(File configDir) {
        file = new File(new File(configDir, "agentcraftgtnh"), "office-layout.json");
        if (!file.exists()) writeDefaults();
        reload();
    }

    public static JsonObject defaults() {
        JsonObject o = new JsonObject();
        o.addProperty("version", 1);
        o.addProperty("theme", "dark");
        JsonObject lod = new JsonObject();
        lod.addProperty("nearPx", 110);
        lod.addProperty("midPx", 85);
        o.add("lod", lod);
        JsonObject blocks = new JsonObject();
        for (String[] d : DEFAULTS) {
            JsonObject e = new JsonObject();
            e.addProperty("panel", d[1]);
            e.addProperty("pxPerBlock", 128);
            blocks.add(d[0], e);
        }
        o.add("blocks", blocks);
        o.add("instances", new JsonObject());
        return o;
    }

    private static void writeDefaults() {
        try {
            file.getParentFile()
                .mkdirs();
            try (Writer w = new OutputStreamWriter(new java.io.FileOutputStream(file), StandardCharsets.UTF_8)) {
                w.write(
                    new GsonBuilder().setPrettyPrinting()
                        .create()
                        .toJson(defaults()));
            }
        } catch (Exception e) {
            AgentCraftGTNH.LOG.warn("could not write {}: {}", file, e.toString());
        }
    }

    /** Re-read the file if it changed (checked at most every 3 s; cheap enough to call per frame). */
    public static void poll() {
        long now = System.currentTimeMillis();
        if (file == null || now - lastCheck < 3000) return;
        lastCheck = now;
        if (file.lastModified() != loadedMtime) reload();
    }

    public static synchronized void reload() {
        BLOCKS.clear();
        INSTANCES.clear();
        theme = "dark";
        nearPx = 110;
        midPx = 85;
        JsonObject o = defaults();
        if (file != null && file.exists()) {
            loadedMtime = file.lastModified();
            try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                JsonElement e = new JsonParser().parse(r);
                if (e.isJsonObject()) o = e.getAsJsonObject();
            } catch (Exception ex) {
                AgentCraftGTNH.LOG.warn("ignoring unreadable {} ({}); using the default layout", file, ex.toString());
            }
        }
        try {
            if (o.has("theme")) theme = o.get("theme")
                .getAsString();
            if (o.has("lod") && o.get("lod")
                .isJsonObject()) {
                JsonObject l = o.getAsJsonObject("lod");
                if (l.has("nearPx")) nearPx = clamp(l.get("nearPx")
                    .getAsDouble(), 20, 2000);
                if (l.has("midPx")) midPx = clamp(l.get("midPx")
                    .getAsDouble(), 10, nearPx);
            }
            read(o, "blocks", BLOCKS);
            read(o, "instances", INSTANCES);
        } catch (RuntimeException ex) {
            AgentCraftGTNH.LOG.warn("bad value in {} ({}); some defaults used", file, ex.toString());
        }
        for (String[] d : DEFAULTS) if (!BLOCKS.containsKey(d[0])) BLOCKS.put(d[0], new Entry(d[1], theme, 128));
    }

    private static void read(JsonObject o, String key, Map<String, Entry> into) {
        if (!o.has(key) || !o.get(key)
            .isJsonObject()) return;
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject(key)
            .entrySet()) {
            if (!e.getValue()
                .isJsonObject()) continue;
            JsonObject b = e.getValue()
                .getAsJsonObject();
            String panel = b.has("panel") ? b.get("panel")
                .getAsString() : "";
            String th = b.has("theme") ? b.get("theme")
                .getAsString() : theme;
            int px = b.has("pxPerBlock") ? (int) clamp(b.get("pxPerBlock")
                .getAsDouble(), 32, 512) : 128;
            into.put(e.getKey(), new Entry(panel, th, px));
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Layout entry for a block kind ("task_wall", ...) at a position (instance override first). */
    public static synchronized Entry entry(String blockKind, int dim, int x, int y, int z) {
        Entry base = BLOCKS.get(blockKind);
        Entry inst = INSTANCES.isEmpty() ? null : INSTANCES.get(dim + ":" + x + "," + y + "," + z);
        if (inst == null) return base;
        return new Entry(
            inst.panel.isEmpty() ? base.panel : inst.panel,
            inst.theme,
            inst.pxPerBlock);
    }

    // ---- card 6: display options from the server's layout (edit tool) ----------------------

    /** "" = use this file's theme; else dark | light for every panel without its own theme. */
    public static volatile String serverTheme = "";
    public static volatile boolean showLabels;
    public static volatile double serverNear = -1, serverMid = -1;

    public static void serverDisplay(String json) {
        try {
            java.util.Map<String, Object> m = dev.agentcraft.gtnh.edit.Json.parseObject(json);
            String th = dev.agentcraft.gtnh.edit.Json.str(m, "theme", "");
            serverTheme = "dark".equals(th) || "light".equals(th) ? th : "";
            showLabels = "true".equals(dev.agentcraft.gtnh.edit.Json.str(m, "showLabels", "false"));
            serverNear = clamp(dev.agentcraft.gtnh.edit.Json.num(m, "nearPx", -1), -1, 2000);
            serverMid = clamp(dev.agentcraft.gtnh.edit.Json.num(m, "midPx", -1), -1, 2000);
        } catch (dev.agentcraft.gtnh.edit.Json.ParseException ignored) {}
    }

    public static double near() {
        return serverNear > 0 ? serverNear : nearPx;
    }

    public static double mid() {
        return serverMid > 0 ? Math.min(serverMid, near()) : midPx;
    }

    public static String file() {
        return file == null ? "(not loaded)" : file.getPath();
    }
}
