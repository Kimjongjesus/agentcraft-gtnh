package dev.agentcraft.gtnh.edit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import dev.agentcraft.gtnh.hq.Anchor;
import dev.agentcraft.gtnh.hq.StationAssigner;

/**
 * The office layout engine (card 6), pure Java: every change to the office is a recorded
 * {@link Op} on a per-world layout, with undo/redo, named snapshots with a diff, presets and
 * export/import, a server-wide lock, hard limits and an audit record of every placement and
 * removal. The game is reached only through {@link Ports}, so the headless checks drive the same
 * code with a fake world.
 *
 * <p>
 * Safety rules enforced here (and again by the Forge world port): a panel is only ever placed on
 * an AIR cell or over another AgentCraft panel; only AgentCraft panels are ever removed or
 * changed; nothing happens inside a protected radius, in an unloaded chunk or outside the bounding
 * box confirmed by an import/preset dry run; a non-mod block is never touched (it is listed as a
 * conflict and skipped). While locked, or while the layout file was written by a newer schema,
 * nothing is written at all.
 *
 * <p>
 * Server thread only (not thread-safe).
 */
public final class EditEngine {

    public static final int SCHEMA = 2;
    public static final Pattern ANCHOR_NAME = Pattern.compile("[a-z0-9][a-z0-9_.:-]{0,63}");
    private static final long PLAN_TTL_MS = 5 * 60 * 1000L;

    // ---- results and plans -------------------------------------------------------------------

    public static final class Result {

        public final boolean ok;
        public final String message;
        public final List<String> lines = new ArrayList<>();
        public Plan plan;
        public Op op;

        Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }

        static Result ok(String m) {
            return new Result(true, m);
        }

        static Result no(String m) {
            return new Result(false, m);
        }

        /** A refusal made outside the engine (an add-on's lock). */
        public static Result refused(String m) {
            return new Result(false, m);
        }

        @Override
        public String toString() {
            return (ok ? "OK " : "REFUSED ") + message + (lines.isEmpty() ? "" : " " + lines);
        }
    }

    /** A dry run: what would change, what is skipped and why, inside which box. Applied by token. */
    public static final class Plan {

        public final String token, kind, title, who;
        public final List<Op.Change> changes;
        public final List<String> summary = new ArrayList<>(), conflicts = new ArrayList<>();
        /** min x, y, z, max x, y, z of every panel placement (null when nothing is placed) */
        public int[] bbox;
        final long expires;
        final Object[] source; // what to re-plan from at apply time

        Plan(String token, String kind, String title, String who, List<Op.Change> changes, long expires, Object[] source) {
            this.token = token;
            this.kind = kind;
            this.title = title;
            this.who = who;
            this.changes = changes;
            this.expires = expires;
            this.source = source;
        }

        public String bboxText() {
            return bbox == null ? "no blocks placed"
                : bbox[0] + " " + bbox[1] + " " + bbox[2] + " .. " + bbox[3] + " " + bbox[4] + " " + bbox[5];
        }
    }

    // ---- state -------------------------------------------------------------------------------

    public final EditRules rules;
    private final Ports.World world;
    private final Ports.Anchors anchors;
    private final Ports.Audit audit;
    private final Ports.Store store;
    private final LongSupplier clock;
    private final EditRules.RateLimiter limiter = new EditRules.RateLimiter();

    private final TreeMap<Pos, PanelSpec> panels = new TreeMap<>();
    private final TreeMap<String, String> display = new TreeMap<>();
    private final Deque<Op> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private final Map<String, Plan> plans = new HashMap<>();
    private boolean locked;
    private String lockInfo = "";
    private String readOnly; // non-null: the layout file is from a newer schema / unreadable; no writes
    private String worldName = "";
    private int dimension;
    private int revision, planSeq;
    private final List<String> notes = new ArrayList<>();

    public EditEngine(EditRules rules, Ports.World world, Ports.Anchors anchors, Ports.Audit audit, Ports.Store store, LongSupplier clock) {
        this.rules = rules;
        this.world = world;
        this.anchors = anchors;
        this.audit = audit;
        this.store = store;
        this.clock = clock;
    }

    public Map<Pos, PanelSpec> panels() {
        return Collections.unmodifiableMap(panels);
    }

    public Map<String, String> display() {
        return Collections.unmodifiableMap(display);
    }

    public List<Op> undoStack() {
        return new ArrayList<>(undo);
    }

    public List<Op> redoStack() {
        return new ArrayList<>(redo);
    }

    public boolean locked() {
        return locked;
    }

    public String lockInfo() {
        return lockInfo;
    }

    public String readOnly() {
        return readOnly;
    }

    public int revision() {
        return revision;
    }

    public int dimension() {
        return dimension;
    }

    /** Load notes (migration, unreadable files) since start; shown by /agentcraft edit status. */
    public List<String> notes() {
        return Collections.unmodifiableList(notes);
    }

    // ---- load / save -------------------------------------------------------------------------

    /** Read hq-layout.json (migrating older schemas) and the lock file. */
    public void load(String worldName, int dimension) {
        this.worldName = worldName == null ? "" : worldName;
        this.dimension = dimension;
        panels.clear();
        display.clear();
        undo.clear();
        redo.clear();
        plans.clear();
        readOnly = null;
        notes.clear();
        revision++;
        try {
            String lock = store.loadLock();
            if (lock != null) {
                locked = true;
                Map<String, Object> m = safeObject(lock);
                lockInfo = m == null ? "locked" : Json.str(m, "info", "locked");
            } else {
                locked = false;
                lockInfo = "";
            }
        } catch (IOException e) {
            locked = true; // fail closed: an unreadable lock file still locks
            lockInfo = "lock file unreadable: " + e.getMessage();
        }
        String text;
        try {
            text = store.loadLayout();
        } catch (IOException e) {
            readOnly = "hq-layout.json unreadable (" + e.getMessage() + "); fix or move it, then /agentcraft edit reload";
            notes.add(readOnly);
            return;
        }
        if (text == null) return;
        Map<String, Object> root;
        try {
            root = Json.parseObject(text);
        } catch (Json.ParseException e) {
            readOnly = "hq-layout.json is not valid JSON (" + e.getMessage() + "); fix or move it, then /agentcraft edit reload";
            notes.add(readOnly);
            return;
        }
        try {
            root = migrate(root, notes);
        } catch (Json.ParseException e) {
            readOnly = e.getMessage();
            notes.add(readOnly);
            return;
        }
        String fileWorld = Json.str(root, "world", "");
        if (!fileWorld.isEmpty() && !this.worldName.isEmpty() && !fileWorld.equals(this.worldName)) {
            readOnly = "hq-layout.json belongs to world '" + fileWorld + "', not '" + this.worldName + "'; move it away, then /agentcraft edit reload";
            notes.add(readOnly);
            return;
        }
        readState(root, panels, display, null);
        Map<String, Object> hist = Json.obj(root, "history");
        if (hist != null) {
            readOps(Json.arr(hist, "undo"), undo);
            readOps(Json.arr(hist, "redo"), redo);
        }
    }

    /**
     * Schema migration. Schema 2 (this card) is current. Schema 1 is card 4's client file
     * {@code office-layout.json} ("version": 1): its theme and detail thresholds become display
     * options; its per-position instance overrides cannot name a panel kind, so they are reported
     * and dropped. Newer schemas are refused (never overwritten).
     */
    public static Map<String, Object> migrate(Map<String, Object> root, List<String> notes) throws Json.ParseException {
        int schema = Json.integer(root, "schema", -1);
        if (schema < 0 && Json.integer(root, "version", -1) == 1) {
            Map<String, Object> out = Json.map("schema", (double) SCHEMA, "kind", "agentcraft-hq-layout");
            Map<String, Object> d = new LinkedHashMap<>();
            String th = Json.str(root, "theme", "");
            if (displayValueOk("theme", th)) d.put("theme", th);
            Map<String, Object> lod = Json.obj(root, "lod");
            if (lod != null) {
                for (String k : new String[] { "nearPx", "midPx" }) {
                    if (lod.containsKey(k)) {
                        String v = Json.num(Json.num(lod, k, 0));
                        if (displayValueOk(k, v)) d.put(k, v);
                    }
                }
            }
            out.put("display", d);
            out.put("panels", new LinkedHashMap<String, Object>());
            Map<String, Object> inst = Json.obj(root, "instances");
            int n = inst == null ? 0 : inst.size();
            notes.add("migrated a schema-1 (card 4 office-layout) file: display options kept" + (n > 0 ? ", " + n + " per-position override(s) dropped (no panel kind)" : ""));
            return out;
        }
        if (schema < 0) throw new Json.ParseException("hq-layout.json has no \"schema\"; not written by this mod. Move it away, then /agentcraft edit reload");
        if (schema > SCHEMA) throw new Json.ParseException("hq-layout.json is schema " + schema + ", newer than this mod (" + SCHEMA + "); editing is read-only until the mod is updated");
        return root;
    }

    @SuppressWarnings("unchecked")
    static void readState(Map<String, Object> root, Map<Pos, PanelSpec> panelsOut, Map<String, String> displayOut, Map<String, Anchor> anchorsOut) {
        Map<String, Object> ps = Json.obj(root, "panels");
        if (ps != null) for (Map.Entry<String, Object> e : ps.entrySet()) {
            Pos p = Pos.parse(e.getKey());
            if (p == null || !(e.getValue() instanceof Map)) continue;
            PanelSpec s = PanelSpec.fromJson((Map<String, Object>) e.getValue());
            if (s != null) panelsOut.put(p, s);
        }
        Map<String, Object> d = Json.obj(root, "display");
        if (d != null) for (Map.Entry<String, Object> e : d.entrySet()) {
            if (e.getValue() instanceof String && displayValueOk(e.getKey(), (String) e.getValue())) displayOut.put(e.getKey(), (String) e.getValue());
        }
        if (anchorsOut != null) {
            Map<String, Object> as = Json.obj(root, "anchors");
            if (as != null) for (Map.Entry<String, Object> e : as.entrySet()) {
                if (!(e.getValue() instanceof Map) || !ANCHOR_NAME.matcher(e.getKey())
                    .matches()) continue;
                Anchor a = Op.anchorFrom(e.getKey(), (Map<String, Object>) e.getValue());
                if (a != null) anchorsOut.put(a.name, a);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void readOps(List<Object> l, Deque<Op> into) {
        if (l == null) return;
        for (Object o : l) {
            if (!(o instanceof Map)) continue;
            Op op = Op.fromJson((Map<String, Object>) o);
            if (op != null && !op.changes.isEmpty()) into.addLast(op);
        }
    }

    private static Map<String, Object> safeObject(String s) {
        try {
            return Json.parseObject(s);
        } catch (Json.ParseException e) {
            return null;
        }
    }

    String layoutJson(Map<Pos, PanelSpec> ps, Deque<Op> u, Deque<Op> r) {
        Map<String, Object> root = Json.map("schema", (double) SCHEMA, "kind", "agentcraft-hq-layout", "world", worldName, "dimension", (double) dimension);
        root.put("display", new LinkedHashMap<String, Object>(display));
        Map<String, Object> pm = new LinkedHashMap<>();
        for (Map.Entry<Pos, PanelSpec> e : ps.entrySet()) pm.put(e.getKey()
            .key(),
            e.getValue()
                .toJson());
        root.put("panels", pm);
        root.put("history", Json.map("undo", opsJson(u), "redo", opsJson(r)));
        return Json.pretty(root);
    }

    private List<Object> opsJson(Deque<Op> d) {
        List<Object> out = new ArrayList<>();
        Iterator<Op> it = d.iterator(); // newest first
        while (it.hasNext() && out.size() < rules.persistSteps) out.add(it.next()
            .toJson());
        return out;
    }

    private String save() {
        try {
            store.saveLayout(layoutJson(panels, undo, redo));
            return null;
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    /** A whole-office state (absolute positions) for snapshots. */
    public String stateJson(String name) {
        Map<String, Object> root = Json.map("schema", (double) SCHEMA, "kind", "agentcraft-hq-snapshot", "name", name, "saved", (double) clock.getAsLong(), "dimension", (double) dimension);
        root.put("display", new LinkedHashMap<String, Object>(display));
        Map<String, Object> pm = new LinkedHashMap<>();
        for (Map.Entry<Pos, PanelSpec> e : panels.entrySet()) pm.put(e.getKey()
            .key(),
            e.getValue()
                .toJson());
        root.put("panels", pm);
        Map<String, Object> am = new LinkedHashMap<>();
        for (Anchor a : new TreeMap<>(anchors.all()).values()) am.put(a.name, Op.anchorJson(a));
        root.put("anchors", am);
        return Json.pretty(root);
    }

    // ---- gates -------------------------------------------------------------------------------

    private String writeBlocker() {
        if (locked) return "editing is locked (" + lockInfo + "); an op can run /agentcraft edit unlock";
        if (readOnly != null) return "editing is read-only: " + readOnly;
        return null;
    }

    private String gate(String who, boolean rate) {
        String b = writeBlocker();
        if (b != null) {
            audit.record(who, "refused", "-", "-", "-", b);
            return b;
        }
        if (rate && !limiter.take(who, clock.getAsLong(), rules.editsPerSecond, rules.burst)) {
            audit.record(who, "refused", "-", "-", "-", "rate limit");
            return "too many edits; at most " + Json.num(rules.editsPerSecond) + " per second";
        }
        return null;
    }

    public static boolean displayValueOk(String key, String v) {
        if (v == null) return true;
        switch (key) {
            case "theme":
                return "dark".equals(v) || "light".equals(v);
            case "showLabels":
                return "true".equals(v) || "false".equals(v);
            case "nearPx":
            case "midPx":
                try {
                    double d = Double.parseDouble(v);
                    return d >= 10 && d <= 2000;
                } catch (NumberFormatException e) {
                    return false;
                }
            default:
                return false;
        }
    }

    /**
     * Simulated view of the cells while a plan is being built: the world, overlaid with what the
     * plan has already decided for earlier changes.
     */
    private final class Sim {

        final Map<Pos, PanelSpec> cells = new HashMap<>(); // decided panels (null value = now air)
        final Map<String, Anchor> anch = new HashMap<>();
        final Map<String, Boolean> anchTouched = new HashMap<>();
        final Map<String, String> disp = new HashMap<>(display);
        final Map<Pos, PanelSpec> layout = new TreeMap<>(panels);

        int kind(Pos p) {
            if (cells.containsKey(p)) return cells.get(p) == null ? Ports.World.AIR : Ports.World.PANEL;
            return world.kind(p);
        }

        PanelSpec read(Pos p) {
            if (cells.containsKey(p)) return cells.get(p);
            return world.kind(p) == Ports.World.PANEL ? world.read(p) : null;
        }

        Anchor anchor(String n) {
            if (anchTouched.containsKey(n)) return anch.get(n);
            return anchors.all()
                .get(n);
        }
    }

    /**
     * Check one requested change against the simulated state. Returns null when accepted (and
     * records it into the sim), "" when it is a no-op, or the conflict reason.
     */
    private String check(Sim sim, Op.Change c, int[] box, List<Op.Change> accepted) {
        switch (c.kind) {
            case Op.PANEL: {
                Pos p = c.pos;
                if (p.y < 0 || p.y > 255) return "outside the world height";
                EditRules.Exclusion ex = rules.protectedAt(p);
                if (ex != null) return "inside the protected area '" + ex.text + "' (radius " + ex.r + ")";
                if (c.after != null && outside(box, p)) return "outside the confirmed bounding box";
                int k = sim.kind(p);
                if (k == Ports.World.UNLOADED) return "chunk not loaded";
                if (k == Ports.World.OTHER) return "occupied by " + world.describe(p) + " (not an AgentCraft panel; never touched)";
                PanelSpec cur = sim.read(p);
                PanelSpec after = (PanelSpec) c.after;
                if (after == null) {
                    if (k != Ports.World.PANEL) {
                        sim.layout.remove(p);
                        return "";
                    }
                    sim.cells.put(p, null);
                    sim.layout.remove(p);
                    accepted.add(Op.Change.panel(p, cur, null));
                    return null;
                }
                PanelType t = PanelTypes.get(after.type);
                if (t == null) return "unknown panel kind '" + after.type + "'";
                if (!t.placeable) return t.name + " is not placed by the tool (place it by hand)";
                after = t.normalize(after);
                if (after.equals(cur)) {
                    sim.layout.put(p, after);
                    return "";
                }
                if (!sim.layout.containsKey(p) && sim.layout.size() >= rules.maxPanels) return "over the layout's panel limit (" + rules.maxPanels + ")";
                sim.cells.put(p, after);
                sim.layout.put(p, after);
                accepted.add(Op.Change.panel(p, cur, after));
                return null;
            }
            case Op.ANCHOR: {
                if (!ANCHOR_NAME.matcher(c.name)
                    .matches()) return "bad anchor name '" + c.name + "'";
                Anchor cur = sim.anchor(c.name);
                Anchor after = (Anchor) c.after;
                if (after != null && (Math.abs(after.y) > 4096 || Math.abs(after.x) > 3.0e7 || Math.abs(after.z) > 3.0e7)) return "anchor outside the world";
                if (Op.sameAnchor(cur, after)) return "";
                sim.anch.put(c.name, after);
                sim.anchTouched.put(c.name, Boolean.TRUE);
                accepted.add(Op.Change.anchor(c.name, cur, after));
                return null;
            }
            case Op.DISPLAY: {
                String after = (String) c.after;
                if (!displayValueOk(c.name, after) || !DISPLAY_KEYS.contains(c.name)) return "bad display option " + c.name + "=" + after;
                String cur = sim.disp.get(c.name);
                if (after == null ? cur == null : after.equals(cur)) return "";
                sim.disp.put(c.name, after);
                accepted.add(Op.Change.display(c.name, cur, after));
                return null;
            }
            default:
                return "unknown change";
        }
    }

    /** Plan a list of requested changes. strict: any conflict fails the whole plan. */
    private Plan plan(String who, String kind, String title, List<Op.Change> requests, boolean strict, int[] box, Object[] source) {
        Sim sim = new Sim();
        List<Op.Change> accepted = new ArrayList<>();
        Plan pl = new Plan(nextToken(), kind, title, who, accepted, clock.getAsLong() + PLAN_TTL_MS, source);
        for (Op.Change c : requests) {
            String why = check(sim, c, box, accepted);
            if (why != null && !why.isEmpty()) {
                pl.conflicts.add("skip " + describeTarget(c) + ": " + why);
                if (strict) break;
            }
        }
        int[] bb = null;
        for (Op.Change c : accepted) {
            if (!Op.PANEL.equals(c.kind) || c.after == null) continue;
            if (bb == null) bb = new int[] { c.pos.x, c.pos.y, c.pos.z, c.pos.x, c.pos.y, c.pos.z };
            bb[0] = Math.min(bb[0], c.pos.x);
            bb[1] = Math.min(bb[1], c.pos.y);
            bb[2] = Math.min(bb[2], c.pos.z);
            bb[3] = Math.max(bb[3], c.pos.x);
            bb[4] = Math.max(bb[4], c.pos.y);
            bb[5] = Math.max(bb[5], c.pos.z);
        }
        pl.bbox = bb;
        for (Op.Change c : accepted) pl.summary.add(describe(c));
        return pl;
    }

    private String nextToken() {
        return "p" + (++planSeq) + "-" + Long.toString(clock.getAsLong() % 1000000L, 36);
    }

    // ---- execution ---------------------------------------------------------------------------

    /**
     * Execute accepted changes against the real world, re-checking every cell (a cell that changed
     * since planning is skipped and reported). Returns the op of what actually happened.
     */
    private Op execute(String who, String label, List<Op.Change> accepted, int[] box, List<String> skipped) {
        List<Op.Change> done = new ArrayList<>();
        for (Op.Change c : accepted) {
            switch (c.kind) {
                case Op.PANEL: {
                    Pos p = c.pos;
                    PanelSpec after = (PanelSpec) c.after;
                    int k = world.kind(p);
                    if (rules.protectedAt(p) != null || k == Ports.World.OTHER || k == Ports.World.UNLOADED || after != null && outside(box, p)) {
                        skipped.add("skip " + p + ": changed since the check (" + world.describe(p) + ")");
                        audit.record(who, "refused", p.key(), world.describe(p), "-", "changed since the check");
                        continue;
                    }
                    PanelSpec cur = k == Ports.World.PANEL ? world.read(p) : null;
                    String before = world.describe(p);
                    boolean ok;
                    String action;
                    if (after == null) {
                        if (k != Ports.World.PANEL) {
                            panels.remove(p);
                            continue;
                        }
                        action = "remove";
                        ok = world.remove(p);
                    } else if (cur != null && cur.type.equals(after.type)) {
                        action = "update";
                        ok = world.update(p, after);
                    } else {
                        action = cur == null ? "place" : "replace";
                        ok = world.place(p, after);
                    }
                    audit.record(who, ok ? action : action + "-failed", p.key(), before, world.describe(p), (cur == null ? "" : cur.describe() + " -> ") + (after == null ? "(none)" : after.describe()));
                    if (!ok) {
                        skipped.add("skip " + p + ": the world refused the " + action);
                        continue;
                    }
                    if (after == null) panels.remove(p);
                    else panels.put(p, world.read(p) != null ? world.read(p) : after);
                    done.add(Op.Change.panel(p, cur, after));
                    break;
                }
                case Op.ANCHOR: {
                    Anchor cur = anchors.all()
                        .get(c.name);
                    Anchor after = (Anchor) c.after;
                    if (Op.sameAnchor(cur, after)) continue;
                    try {
                        if (after == null) anchors.remove(c.name);
                        else anchors.put(after);
                    } catch (IOException e) {
                        skipped.add("skip anchor " + c.name + ": " + e.getMessage());
                        audit.record(who, "anchor-failed", c.name, aText(cur), aText(after), e.getMessage());
                        continue;
                    }
                    audit.record(who, "anchor", c.name, aText(cur), aText(after), "");
                    done.add(Op.Change.anchor(c.name, cur, after));
                    break;
                }
                case Op.DISPLAY: {
                    String cur = display.get(c.name), after = (String) c.after;
                    if (after == null) display.remove(c.name);
                    else display.put(c.name, after);
                    audit.record(who, "display", c.name, cur == null ? "-" : cur, after == null ? "-" : after, "");
                    done.add(Op.Change.display(c.name, cur, after));
                    break;
                }
                default:
            }
        }
        revision++;
        return new Op(label, who, clock.getAsLong(), done);
    }

    private static String aText(Anchor a) {
        return a == null ? "-" : String.format(Locale.ROOT, "%.2f,%.2f,%.2f@%s", a.x, a.y, a.z, Anchor.facingName(a.yaw));
    }

    private String sizeCheck(List<Op.Change> accepted, Op pending) {
        TreeMap<Pos, PanelSpec> after = new TreeMap<>(panels);
        for (Op.Change c : accepted) {
            if (!Op.PANEL.equals(c.kind)) continue;
            if (c.after == null) after.remove(c.pos);
            else after.put(c.pos, (PanelSpec) c.after);
        }
        Deque<Op> u = new ArrayDeque<>(undo);
        if (pending != null) u.addFirst(pending);
        int n = layoutJson(after, u, redo).getBytes(StandardCharsets.UTF_8).length;
        return n > rules.maxLayoutBytes ? "the layout would be " + n + " bytes, over the " + rules.maxLayoutBytes + "-byte limit" : null;
    }

    private void pushUndo(Op op) {
        undo.addFirst(op);
        while (undo.size() > rules.undoSteps) undo.removeLast();
    }

    private Result finish(Op op, List<String> skipped, String okText) {
        String err = save();
        Result r = err == null ? Result.ok(okText) : Result.no(okText + " (applied, but the layout file was not written: " + err + ")");
        r.op = op;
        r.lines.addAll(skipped);
        return r;
    }

    // ---- public edits ------------------------------------------------------------------------

    /** One edit (inspector, palette, anchor editor, commands): all its changes, or none. */
    public Result edit(String who, String label, List<Op.Change> requests) {
        String g = gate(who, true);
        if (g != null) return Result.no(g);
        if (requests.size() > rules.maxChangesPerOp) return Result.no("too many changes in one edit (" + requests.size() + " > " + rules.maxChangesPerOp + ")");
        Plan pl = plan(who, "edit", label, requests, true, null, null);
        if (!pl.conflicts.isEmpty()) {
            Result r = Result.no("refused: " + pl.conflicts.get(0)
                .replaceFirst("^skip ", ""));
            r.lines.addAll(pl.conflicts);
            return r;
        }
        if (pl.changes.isEmpty()) return Result.ok("nothing to change");
        String sz = sizeCheck(pl.changes, new Op(label, who, 0, pl.changes));
        if (sz != null) return Result.no(sz);
        List<String> skipped = new ArrayList<>();
        Op op = execute(who, label, pl.changes, null, skipped);
        if (!op.changes.isEmpty()) {
            pushUndo(op);
            redo.clear();
        }
        Result r = finish(op, skipped, label + (skipped.isEmpty() ? "" : " (partly)"));
        r.lines.addAll(0, pl.summary);
        return r;
    }

    public Result undo(String who) {
        return step(who, undo, redo, "undo");
    }

    public Result redo(String who) {
        return step(who, redo, undo, "redo");
    }

    private Result step(String who, Deque<Op> from, Deque<Op> to, String what) {
        String g = gate(who, true);
        if (g != null) return Result.no(g);
        Op top = from.peekFirst();
        if (top == null) return Result.no("nothing to " + what);
        // lenient: a change whose cell was built over since is skipped, the rest still happens
        boolean undoing = "undo".equals(what);
        // undo entries are stored forward (apply the inverse); redo entries are stored forward too (apply as is)
        Plan pl = plan(who, what, top.label, undoing ? top.inverse(top.label).changes : top.changes, false, null, null);
        String sz = sizeCheck(pl.changes, null);
        if (sz != null) return Result.no(sz);
        from.removeFirst();
        List<String> skipped = new ArrayList<>(pl.conflicts);
        Op done = execute(who, top.label, pl.changes, null, skipped);
        if (!done.changes.isEmpty()) {
            to.addFirst(undoing ? done.inverse(top.label) : done);
            while (to.size() > rules.undoSteps) to.removeLast();
        }
        Result r = finish(done, skipped, what + " \"" + top.label + "\": " + done.changes.size() + " change(s)" + (skipped.isEmpty() ? "" : ", " + skipped.size() + " skipped"));
        for (Op.Change c : done.changes) r.lines.add(describe(c));
        return r;
    }

    public Result setLock(String who, boolean on, String reason) {
        if (on == locked) return Result.ok(on ? "already locked (" + lockInfo + ")" : "already unlocked");
        try {
            if (on) {
                String info = "by " + who + (reason == null || reason.isEmpty() ? "" : ": " + RelLayout.clean(reason, 80));
                store.saveLock(Json.pretty(Json.map("locked", Boolean.TRUE, "info", info, "time", (double) clock.getAsLong())));
                locked = true;
                lockInfo = info;
            } else {
                store.saveLock(null);
                locked = false;
                lockInfo = "";
            }
        } catch (IOException e) {
            if (on) {
                locked = true; // still lock in memory: the panic switch must work even if the disk does not
                lockInfo = "by " + who + " (lock file not written: " + e.getMessage() + ")";
            }
            audit.record(who, on ? "lock" : "unlock-failed", "-", "-", "-", e.getMessage());
            return on ? Result.ok("locked in memory only: " + e.getMessage()) : Result.no("unlock failed: " + e.getMessage());
        }
        plans.clear();
        audit.record(who, on ? "lock" : "unlock", "-", "-", "-", lockInfo);
        return Result.ok(on ? "editing LOCKED server-wide: the tool and every layout write are off until /agentcraft edit unlock" : "editing unlocked");
    }

    // ---- hand placements (no history) ---------------------------------------------------------

    /** A player placed a panel block by hand: the layout now knows it (not an undoable edit). */
    public void adopt(Pos p, PanelSpec s) {
        if (writeBlocker() != null || s == null) return;
        if (!panels.containsKey(p) && panels.size() >= rules.maxPanels) return;
        panels.put(p, s);
        revision++;
        save();
    }

    /** A panel block was broken by hand. */
    public void forget(Pos p) {
        if (writeBlocker() != null) return;
        if (panels.remove(p) != null) {
            revision++;
            save();
        }
    }

    /** Re-read every known panel from the world: drop the ones that are gone, refresh the rest. */
    public int reconcile() {
        int changed = 0;
        Iterator<Map.Entry<Pos, PanelSpec>> it = panels.entrySet()
            .iterator();
        while (it.hasNext()) {
            Map.Entry<Pos, PanelSpec> e = it.next();
            int k = world.kind(e.getKey());
            if (k == Ports.World.UNLOADED) continue;
            if (k != Ports.World.PANEL) {
                it.remove();
                changed++;
                continue;
            }
            PanelSpec s = world.read(e.getKey());
            if (s != null && !s.equals(e.getValue())) {
                e.setValue(s);
                changed++;
            }
        }
        if (changed > 0) {
            revision++;
            if (writeBlocker() == null) save();
        }
        return changed;
    }

    /** Add panels found by a scan (positions the caller found holding this mod's blocks). */
    public int adoptAll(Map<Pos, PanelSpec> found) {
        if (writeBlocker() != null) return 0;
        int n = 0;
        for (Map.Entry<Pos, PanelSpec> e : found.entrySet()) {
            if (panels.containsKey(e.getKey())) continue;
            if (panels.size() >= rules.maxPanels) break;
            panels.put(e.getKey(), e.getValue());
            n++;
        }
        if (n > 0) {
            revision++;
            save();
        }
        return n;
    }

    // ---- snapshots and diff -------------------------------------------------------------------

    public Result saveSnapshot(String who, String name) {
        String g = gate(who, true);
        if (g != null) return Result.no(g);
        if (!FileStore.validName(name)) return Result.no("snapshot names: a-z 0-9 _ - (max 32)");
        if (!store.snapshots()
            .contains(name) && store.snapshots()
                .size() >= rules.maxSnapshots) return Result.no("too many snapshots (" + rules.maxSnapshots + "); delete one by hand first");
        reconcile();
        try {
            store.saveSnapshot(name, stateJson(name));
        } catch (IOException e) {
            return Result.no("snapshot not saved: " + e.getMessage());
        }
        audit.record(who, "snapshot", name, "-", "-", panels.size() + " panels, " + anchors.all()
            .size() + " anchors");
        return Result.ok("saved snapshot '" + name + "' (" + panels.size() + " panels, " + anchors.all()
            .size() + " anchors)");
    }

    /** Changes that turn the current office into the target state (removals first). */
    List<Op.Change> diffChanges(Map<Pos, PanelSpec> tp, Map<String, Anchor> ta, Map<String, String> td) {
        List<Op.Change> rm = new ArrayList<>(), up = new ArrayList<>();
        for (Map.Entry<Pos, PanelSpec> e : panels.entrySet()) if (!tp.containsKey(e.getKey())) rm.add(Op.Change.panel(e.getKey(), null));
        for (Map.Entry<Pos, PanelSpec> e : tp.entrySet()) {
            if (!e.getValue()
                .equals(panels.get(e.getKey()))) up.add(Op.Change.panel(e.getKey(), e.getValue()));
        }
        Map<String, Anchor> cur = anchors.all();
        for (String n : new TreeMap<>(cur).keySet()) if (!ta.containsKey(n)) rm.add(Op.Change.anchor(n, null));
        for (Anchor a : new TreeMap<>(ta).values()) if (!Op.sameAnchor(a, cur.get(a.name))) up.add(Op.Change.anchor(a.name, a));
        for (String k : new String[] { "theme", "showLabels", "nearPx", "midPx" }) {
            String a = display.get(k), b = td.get(k);
            if (a == null ? b != null : !a.equals(b)) up.add(Op.Change.display(k, b));
        }
        rm.addAll(up);
        return rm;
    }

    private Object[] readSnapshot(String name, Map<Pos, PanelSpec> tp, Map<String, Anchor> ta, Map<String, String> td) throws IOException {
        if (!FileStore.validName(name)) throw new IOException("snapshot names: a-z 0-9 _ - (max 32)");
        String text = store.loadSnapshot(name);
        if (text == null) throw new IOException("no snapshot '" + name + "'");
        try {
            Map<String, Object> root = Json.parseObject(text);
            int schema = Json.integer(root, "schema", -1);
            if (schema < 1 || schema > SCHEMA) throw new IOException("snapshot '" + name + "' has schema " + schema + " (this mod reads 1.." + SCHEMA + ")");
            readState(root, tp, td, ta);
        } catch (Json.ParseException e) {
            throw new IOException("snapshot '" + name + "' is not valid JSON: " + e.getMessage());
        }
        return new Object[] { "snapshot", name };
    }

    /** Dry run of restoring a snapshot: the diff lines, conflicts, and a token to apply it. */
    public Result diffSnapshot(String who, String name) {
        reconcile();
        Map<Pos, PanelSpec> tp = new TreeMap<>();
        Map<String, Anchor> ta = new TreeMap<>();
        Map<String, String> td = new TreeMap<>();
        Object[] src;
        try {
            src = readSnapshot(name, tp, ta, td);
        } catch (IOException e) {
            return Result.no(e.getMessage());
        }
        Plan pl = plan(who, "restore", "restore snapshot '" + name + "'", diffChanges(tp, ta, td), false, null, src);
        return planResult(pl, pl.changes.isEmpty() ? "snapshot '" + name + "' matches the current office" : "restoring '" + name + "' would make " + pl.changes.size() + " change(s)");
    }

    public Result restoreSnapshot(String who, String name) {
        Result d = diffSnapshot(who, name);
        if (d.plan == null) return d;
        return applyPlan(who, d.plan.token);
    }

    // ---- presets and import ------------------------------------------------------------------

    /**
     * Dry run of applying a relative layout at origin, for someone looking {@code facing}.
     * mode "add": only empty cells, existing anchors keep their name (a new numbered slot is
     * used for stations). mode "replace": every panel in the layout and every non-camera anchor is
     * removed first. The plan's bounding box must fit within {@code maxImportSpan} per axis.
     */
    public Result planLayout(String who, String kind, RelLayout rel, Pos origin, String facing, String mode) {
        boolean replace = "replace".equals(mode);
        if (!replace && !"add".equals(mode)) return Result.no("mode is add or replace");
        if (rel.panels.size() + rel.anchors.size() > rules.maxChangesPerOp) return Result.no("layout too large (" + (rel.panels.size() + rel.anchors.size()) + " items > " + rules.maxChangesPerOp + ")");
        reconcile();
        int q = rel.quartersTo(facing);
        List<Op.Change> req = new ArrayList<>();
        Map<String, Anchor> cur = anchors.all();
        java.util.Set<String> taken = new java.util.HashSet<>(cur.keySet());
        if (replace) {
            for (Pos p : panels.keySet()) req.add(Op.Change.panel(p, null));
            for (String n : new TreeMap<>(cur).keySet()) {
                if (n.startsWith("cam_")) continue;
                req.add(Op.Change.anchor(n, null));
                taken.remove(n);
            }
        }
        List<String> pre = new ArrayList<>();
        int[] span = null;
        for (RelLayout.RelPanel rp : rel.panels) {
            Pos p = rel.panelPos(rp, origin, q);
            PanelSpec s = rel.panelSpec(rp, q);
            if (RelLayout.placeholder(s.binding)) {
                PanelType t = PanelTypes.get(s.type);
                s = s.withBinding(t == null ? "" : t.defaultBinding());
            }
            if (!replace && world.kind(p) == Ports.World.PANEL) {
                pre.add("skip " + p + ": already a panel there (add mode keeps it)");
                continue;
            }
            span = grow(span, p);
            req.add(Op.Change.panel(p, s));
        }
        for (RelLayout.RelAnchor ra : rel.anchors) {
            String n = ra.name;
            String station = stationOf(n);
            if (station != null && n.matches(Pattern.quote(station) + "_agent-\\d+")) n = station; // stripped personal spot -> a shared slot
            if (taken.contains(n)) {
                if (station == null) {
                    pre.add("skip anchor " + n + ": already set (add mode keeps it)");
                    continue;
                }
                n = freeSlot(station, taken);
                if (n == null) {
                    pre.add("skip anchor " + ra.name + ": station " + station + " has 32 slots");
                    continue;
                }
            }
            taken.add(n);
            req.add(Op.Change.anchor(n, rel.anchorAt(ra, n, origin, q)));
        }
        for (Map.Entry<String, String> e : rel.display.entrySet()) req.add(Op.Change.display(e.getKey(), e.getValue()));
        if (span != null) {
            int sx = span[3] - span[0] + 1, sy = span[4] - span[1] + 1, sz = span[5] - span[2] + 1;
            if (Math.max(sx, Math.max(sy, sz)) > rules.maxImportSpan) {
                return Result.no("the layout spans " + sx + "x" + sy + "x" + sz + " blocks, over the " + rules.maxImportSpan + "-block import limit");
            }
        }
        String title = kind + " '" + (rel.name.isEmpty() ? "unnamed" : rel.name) + "' (" + mode + ", facing " + facing + ")";
        Plan pl = plan(who, kind, title, req, false, span, new Object[] { kind, rel, origin, facing, mode });
        pl.conflicts.addAll(0, pre);
        return planResult(pl, title + ": " + pl.changes.size() + " change(s), " + pl.conflicts.size() + " skipped, box " + pl.bboxText());
    }

    static boolean outside(int[] box, Pos p) {
        return box != null && (p.x < box[0] || p.y < box[1] || p.z < box[2] || p.x > box[3] || p.y > box[4] || p.z > box[5]);
    }

    public static final java.util.Set<String> DISPLAY_KEYS = new java.util.HashSet<>(java.util.Arrays.asList("theme", "showLabels", "nearPx", "midPx"));

    private static int[] grow(int[] b, Pos p) {
        if (b == null) return new int[] { p.x, p.y, p.z, p.x, p.y, p.z };
        b[0] = Math.min(b[0], p.x);
        b[1] = Math.min(b[1], p.y);
        b[2] = Math.min(b[2], p.z);
        b[3] = Math.max(b[3], p.x);
        b[4] = Math.max(b[4], p.y);
        b[5] = Math.max(b[5], p.z);
        return b;
    }

    static String stationOf(String anchor) {
        for (String st : StationAssigner.STATIONS) if (anchor.equals(st) || anchor.startsWith(st + "_")) return st;
        return null;
    }

    static String freeSlot(String station, java.util.Set<String> taken) {
        if (!taken.contains(station)) return station;
        for (int i = 2; i <= 32; i++) if (!taken.contains(station + "_" + i)) return station + "_" + i;
        return null;
    }

    private Result planResult(Plan pl, String msg) {
        plans.values()
            .removeIf(x -> x.who.equals(pl.who) || x.expires < clock.getAsLong());
        plans.put(pl.token, pl);
        Result r = Result.ok(msg);
        r.plan = pl;
        r.lines.addAll(pl.summary);
        r.lines.addAll(pl.conflicts);
        return r;
    }

    public Plan plan(String token) {
        return plans.get(token);
    }

    /**
     * Apply a dry run by its token. The plan is rebuilt against the live world; if the outcome
     * differs from what the dry run showed (the area changed), nothing is applied and a new dry run
     * is needed. Placements are limited to the dry run's bounding box.
     */
    public Result applyPlan(String who, String token) {
        Plan pl = plans.get(token);
        if (pl == null || pl.expires < clock.getAsLong()) {
            plans.remove(token);
            return Result.no("no such dry run (expired or already used); run the dry run again");
        }
        if (!pl.who.equals(who)) return Result.no("that dry run belongs to " + pl.who);
        String g = gate(who, true);
        if (g != null) return Result.no(g);
        plans.remove(token);
        Plan again;
        if ("snapshot".equals(pl.source[0])) {
            Map<Pos, PanelSpec> tp = new TreeMap<>();
            Map<String, Anchor> ta = new TreeMap<>();
            Map<String, String> td = new TreeMap<>();
            try {
                readSnapshot((String) pl.source[1], tp, ta, td);
            } catch (IOException e) {
                return Result.no(e.getMessage());
            }
            again = plan(who, pl.kind, pl.title, diffChanges(tp, ta, td), false, null, pl.source);
        } else {
            Result r = planLayout(who, (String) pl.source[0], (RelLayout) pl.source[1], (Pos) pl.source[2], (String) pl.source[3], (String) pl.source[4]);
            if (r.plan == null) return r;
            plans.remove(r.plan.token);
            again = r.plan;
        }
        if (!again.summary.equals(pl.summary) || again.conflicts.size() != pl.conflicts.size()) {
            return Result.no("the area changed since the dry run; run the dry run again");
        }
        if (again.changes.size() > rules.maxChangesPerOp) return Result.no("too many changes (" + again.changes.size() + ")");
        String sz = sizeCheck(again.changes, new Op(pl.title, who, 0, again.changes));
        if (sz != null) return Result.no(sz);
        List<String> skipped = new ArrayList<>(again.conflicts);
        Op op = execute(who, pl.title, again.changes, pl.bbox == null ? null : pl.bbox.clone(), skipped);
        if (!op.changes.isEmpty()) {
            pushUndo(op);
            redo.clear();
        }
        audit.record(who, pl.kind, "-", "-", "-", pl.title + ": " + op.changes.size() + " applied, " + skipped.size() + " skipped, box " + pl.bboxText());
        Result r = finish(op, skipped, pl.title + ": " + op.changes.size() + " change(s) applied, " + skipped.size() + " skipped (undo reverts it in one step)");
        return r;
    }

    public RelLayout export(String name, Pos origin, String facing, int radius, boolean keepNames) {
        reconcile();
        return RelLayout.export(name, panels, new TreeMap<>(anchors.all()), display, origin, facing, Math.max(1, Math.min(radius, rules.maxImportSpan)), keepNames);
    }

    // ---- text --------------------------------------------------------------------------------

    public static String describe(Op.Change c) {
        switch (c.kind) {
            case Op.PANEL: {
                PanelSpec b = (PanelSpec) c.before, a = (PanelSpec) c.after;
                if (a == null) return "- remove " + (b == null ? "panel" : b.describe()) + " at " + c.pos;
                if (b == null) return "+ place " + a.describe() + " at " + c.pos;
                if (!a.type.equals(b.type)) return "~ replace " + b.describe() + " with " + a.describe() + " at " + c.pos;
                PanelType t = PanelTypes.get(a.type);
                return "~ " + (t == null ? a.type : t.name) + " at " + c.pos + ": " + a.changesFrom(b);
            }
            case Op.ANCHOR: {
                Anchor b = (Anchor) c.before, a = (Anchor) c.after;
                if (a == null) return "- anchor " + c.name;
                if (b == null) return String.format(Locale.ROOT, "+ anchor %s at %.1f %.1f %.1f facing %s", c.name, a.x, a.y, a.z, Anchor.facingName(a.yaw));
                double d = Math.sqrt(a.distSq(b.x, b.y, b.z));
                String f = Anchor.facingName(a.yaw)
                    .equals(Anchor.facingName(b.yaw)) ? "" : ", facing " + Anchor.facingName(b.yaw) + " -> " + Anchor.facingName(a.yaw);
                return String.format(Locale.ROOT, "~ anchor %s%s%s", c.name, d > 1e-6 ? String.format(Locale.ROOT, " moved %.1f blocks to %.1f %.1f %.1f", d, a.x, a.y, a.z) : "", f);
            }
            default:
                return "~ display " + c.name + ": " + (c.before == null ? "(default)" : c.before) + " -> " + (c.after == null ? "(default)" : c.after);
        }
    }

    private static String describeTarget(Op.Change c) {
        if (Op.PANEL.equals(c.kind)) {
            PanelSpec a = (PanelSpec) c.after;
            return (a == null ? "removal" : a.type) + " at " + c.pos;
        }
        return c.kind + " " + c.name;
    }
}
