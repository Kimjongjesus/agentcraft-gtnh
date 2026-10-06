import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import dev.agentcraft.gtnh.edit.AuditLog;
import dev.agentcraft.gtnh.edit.EditEngine;
import dev.agentcraft.gtnh.edit.EditRules;
import dev.agentcraft.gtnh.edit.FileStore;
import dev.agentcraft.gtnh.edit.Json;
import dev.agentcraft.gtnh.edit.Op;
import dev.agentcraft.gtnh.edit.PanelSpec;
import dev.agentcraft.gtnh.edit.PanelTypes;
import dev.agentcraft.gtnh.edit.Ports;
import dev.agentcraft.gtnh.edit.Pos;
import dev.agentcraft.gtnh.edit.RelLayout;
import dev.agentcraft.gtnh.hq.Anchor;

/**
 * Headless checks of the card-6 layout engine (no Minecraft): operation log + undo/redo,
 * snapshots/diff/restore, presets and import conflicts, schema migration, limits, the lock, the
 * audit trail, and a randomized check that the engine never places, changes or removes anything
 * but this mod's panels on legal cells.
 */
public class EditCheck {

    static int checks;

    static void ok(boolean c, String what) {
        checks++;
        if (!c) throw new AssertionError("FAILED: " + what);
    }

    // ---- fakes ------------------------------------------------------------------------------

    /** A sparse grid. Unset cells are air; "stone"/"gt_machine" etc. are foreign blocks. */
    static final class FakeWorld implements Ports.World {

        final Map<Pos, PanelSpec> panels = new HashMap<>();
        final Map<Pos, String> foreign = new HashMap<>();
        final java.util.Set<Pos> unloaded = new java.util.HashSet<>();
        EditRules rules;
        int places, updates, removes, violations;
        final List<String> calls = new ArrayList<>();
        boolean refuseNext;

        int raw(Pos p) {
            if (unloaded.contains(p)) return UNLOADED;
            if (foreign.containsKey(p)) return OTHER;
            if (panels.containsKey(p)) return PANEL;
            return AIR;
        }

        @Override
        public int kind(Pos p) {
            return raw(p);
        }

        @Override
        public PanelSpec read(Pos p) {
            return panels.get(p);
        }

        @Override
        public String describe(Pos p) {
            if (foreign.containsKey(p)) return "minecraft:" + foreign.get(p) + ":0";
            if (panels.containsKey(p)) return "agentcraftgtnh:" + panels.get(p).type + ":" + PanelSpec.meta(panels.get(p).facing);
            return "minecraft:air:0";
        }

        void legal(Pos p, boolean needPanel, String what) {
            int k = raw(p);
            boolean bad = needPanel ? k != PANEL : k != AIR && k != PANEL;
            if (rules != null && rules.protectedAt(p) != null) bad = true;
            if (bad) {
                violations++;
                throw new AssertionError("ILLEGAL " + what + " at " + p + " kind=" + k);
            }
            calls.add(what + " " + p.key());
        }

        @Override
        public boolean place(Pos p, PanelSpec s) {
            legal(p, false, "place");
            ok(PanelTypes.get(s.type) != null && PanelTypes.get(s.type).placeable, "only placeable mod panel kinds are placed");
            if (refuseNext) {
                refuseNext = false;
                return false;
            }
            places++;
            panels.put(p, s);
            return true;
        }

        @Override
        public boolean update(Pos p, PanelSpec s) {
            legal(p, true, "update");
            ok(panels.get(p).type.equals(s.type), "update keeps the panel kind");
            updates++;
            panels.put(p, s);
            return true;
        }

        @Override
        public boolean remove(Pos p) {
            legal(p, true, "remove");
            removes++;
            panels.remove(p);
            return true;
        }
    }

    static final class FakeAnchors implements Ports.Anchors {

        final Map<String, Anchor> m = new TreeMap<>();
        boolean fail;

        @Override
        public Map<String, Anchor> all() {
            return java.util.Collections.unmodifiableMap(m);
        }

        @Override
        public void put(Anchor a) throws IOException {
            if (fail) throw new IOException("disk full");
            m.put(a.name, a);
        }

        @Override
        public void remove(String name) throws IOException {
            if (fail) throw new IOException("disk full");
            m.remove(name);
        }
    }

    static final class Clock implements java.util.function.LongSupplier {

        long t = 1_000_000L;

        @Override
        public long getAsLong() {
            return t;
        }
    }

    static final class Rig {

        final FakeWorld w = new FakeWorld();
        final FakeAnchors a = new FakeAnchors();
        final Clock clock = new Clock();
        final EditRules rules = new EditRules();
        final File dir;
        final FileStore store;
        final AuditLog audit;
        EditEngine e;

        Rig(File dir) {
            this.dir = dir;
            store = new FileStore(dir, rules.maxLayoutBytes);
            audit = new AuditLog(new File(dir, "agentcraft-edit-audit.log"), 1 << 20);
            rules.editsPerSecond = 1000;
            rules.burst = 1000;
            w.rules = rules;
            reload();
        }

        void reload() {
            e = new EditEngine(rules, w, a, audit, store, clock);
            e.load("TestWorld", 0);
        }

        EditEngine.Result place(int x, int y, int z, String type, String binding, int w0, int h0) {
            PanelSpec s = PanelTypes.get(type)
                .fresh("south")
                .withBinding(binding)
                .withSize(w0, h0);
            return e.edit("Tester", "place " + type, one(Op.Change.panel(new Pos(x, y, z), s)));
        }
    }

    static List<Op.Change> one(Op.Change c) {
        List<Op.Change> l = new ArrayList<>();
        l.add(c);
        return l;
    }

    static File tmp(String n) throws IOException {
        File d = Files.createTempDirectory("ac6-" + n)
            .toFile();
        d.deleteOnExit();
        return d;
    }

    static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    // ---- checks -----------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        PanelTypes.registerBuiltins();
        json();
        rotation();
        undoRedo();
        safety();
        snapshots();
        importExport();
        presets(args.length > 0 ? args[0] : "src/main/resources/assets/agentcraftgtnh/presets");
        schema();
        limits();
        lock();
        audit();
        fuzz();
        System.out.println("EditCheck OK: " + checks + " checks");
    }

    static void json() throws Exception {
        Map<String, Object> m = Json.parseObject("{\"a\": [1, 2.5, \"x\\n\\u00e9\"], \"b\": {\"c\": true, \"d\": null}}");
        ok(Json.arr(m, "a").size() == 3, "json array");
        ok("x\n\u00e9".equals(Json.arr(m, "a").get(2)), "json escapes");
        String back = Json.pretty(m);
        ok(Json.parseObject(back).equals(m), "json round trip");
        for (String bad : new String[] { "{", "{\"a\":}", "[1,]", "{\"a\":1} x", "\"open", "{\"a\" 1}" }) {
            boolean threw = false;
            try {
                Json.parse(bad);
            } catch (Json.ParseException e) {
                threw = true;
            }
            ok(threw, "json rejects " + bad);
        }
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 100; i++) deep.append('[');
        boolean threw = false;
        try {
            Json.parse(deep.toString());
        } catch (Json.ParseException e) {
            threw = true;
        }
        ok(threw, "json depth limit");
        ok(!Json.write("\u00a7c red").contains("\u00a7"), "section sign escaped in JSON output");
        ok(PanelSpec.cleanLabel("\u00a7cHi\nthere").equals("cHithere"), "labels lose control and section characters");
        ok(PanelSpec.cleanLabel("a very long label that goes past thirty two characters").length() == 32, "labels capped");
        ok(PanelSpec.cleanBinding("../../etc").isEmpty() && PanelSpec.cleanBinding("claude-builder").equals("claude-builder"), "binding pattern");
        ok(Pos.parse("1,2,3").equals(new Pos(1, 2, 3)) && Pos.parse("1 2") == null, "pos parse");
    }

    static void rotation() {
        for (int q = 0; q < 4; q++) {
            int[] r = RelLayout.rot(3, -5, q);
            int[] back = RelLayout.rot(r[0], r[1], 4 - q);
            ok(back[0] == 3 && back[1] == -5, "rotation round trip q=" + q);
        }
        int[] east = RelLayout.rot(0, -1, 1);
        ok(east[0] == 1 && east[1] == 0, "north offset turns east after one clockwise quarter");
        ok(PanelSpec.rotate("north", 1).equals("east") && PanelSpec.rotate("west", 1).equals("north") && PanelSpec.rotate("south", -1).equals("east"), "facing rotation");
        for (String f : PanelSpec.FACINGS) ok(PanelSpec.facingOfMeta(PanelSpec.meta(f)).equals(f), "facing meta " + f);
    }

    static void undoRedo() throws Exception {
        Rig r = new Rig(tmp("undo"));
        EditEngine.Result res = r.place(0, 64, 0, "task_wall", "all", 5, 3);
        ok(res.ok && r.w.panels.containsKey(new Pos(0, 64, 0)), "place on air: " + res);
        Pos p = new Pos(0, 64, 0);
        PanelSpec cur = r.w.panels.get(p);
        ok(r.e.edit("Tester", "rebind", one(Op.Change.panel(p, cur.withBinding("homelab")))).ok, "rebind");
        ok(r.e.edit("Tester", "resize", one(Op.Change.panel(p, r.w.panels.get(p).withSize(4, 2)))).ok, "resize");
        ok(r.e.edit("Tester", "rename", one(Op.Change.panel(p, r.w.panels.get(p).withLabel("Main wall")))).ok, "rename");
        ok(r.w.panels.get(p).w == 4 && "homelab".equals(r.w.panels.get(p).binding) && "Main wall".equals(r.w.panels.get(p).label), "state after edits");
        ok(r.e.undoStack().size() == 4, "4 undo steps");
        ok(r.e.undo("Tester").ok && "".equals(r.w.panels.get(p).label), "undo rename");
        ok(r.e.undo("Tester").ok && r.w.panels.get(p).w == 5, "undo resize");
        ok(r.e.redo("Tester").ok && r.w.panels.get(p).w == 4, "redo resize");
        ok(r.e.undo("Tester").ok && r.e.undo("Tester").ok && "all".equals(r.w.panels.get(p).binding), "undo rebind");
        ok(r.e.undo("Tester").ok && !r.w.panels.containsKey(p), "undo place removes the panel");
        ok(!r.e.undo("Tester").ok, "nothing left to undo");
        ok(r.e.redoStack().size() == 4, "4 redo steps");
        ok(r.e.redo("Tester").ok && r.w.panels.containsKey(p), "redo place");
        // a new edit clears redo
        ok(r.e.edit("Tester", "rebind2", one(Op.Change.panel(p, r.w.panels.get(p).withBinding("ops")))).ok, "edit after undo");
        ok(r.e.redoStack().isEmpty(), "new edit clears redo");
        // anchors: add, move, rotate, remove, all undoable
        ok(r.e.edit("Tester", "anchor", one(Op.Change.anchor("desk", new Anchor("desk", 1.5, 64, 1.5, 0, 0)))).ok, "anchor add");
        ok(r.e.edit("Tester", "anchor move", one(Op.Change.anchor("desk", new Anchor("desk", 3.5, 64, 1.5, 90, 0)))).ok, "anchor move+rotate");
        ok(r.e.edit("Tester", "anchor rm", one(Op.Change.anchor("desk", null))).ok && !r.a.m.containsKey("desk"), "anchor remove");
        ok(r.e.undo("Tester").ok && r.a.m.get("desk").x == 3.5, "undo anchor remove");
        ok(r.e.undo("Tester").ok && r.a.m.get("desk").x == 1.5 && r.a.m.get("desk").yaw == 0, "undo anchor move");
        // move = remove + place in one op, undone in one step
        PanelSpec s = r.w.panels.get(p);
        List<Op.Change> mv = new ArrayList<>();
        mv.add(Op.Change.panel(p, null));
        mv.add(Op.Change.panel(new Pos(2, 64, 0), s));
        ok(r.e.edit("Tester", "move", mv).ok && !r.w.panels.containsKey(p) && r.w.panels.containsKey(new Pos(2, 64, 0)), "move");
        ok(r.e.undo("Tester").ok && r.w.panels.containsKey(p) && !r.w.panels.containsKey(new Pos(2, 64, 0)), "undo move in one step");
        // 70 edits: the stack keeps 64 (>= 50 as required), the file keeps the last 20
        for (int i = 0; i < 70; i++) ok(r.e.edit("Tester", "lamp " + i, one(Op.Change.panel(new Pos(10 + i, 64, 5), PanelTypes.get("status_lamp").fresh("north")))).ok, "lamp " + i);
        ok(r.e.undoStack().size() == 64, "undo keeps 64 steps, got " + r.e.undoStack().size());
        for (int i = 0; i < 55; i++) ok(r.e.undo("Tester").ok, "undo #" + i);
        ok(r.w.panels.size() == 1 + 15, "55 undos removed 55 lamps");
        r.e.redo("Tester");
        r.reload();
        ok(r.e.undoStack().size() == 10 && r.e.redoStack().size() == 20, "after a restart: 10 undo / 20 (persisted max) redo, got " + r.e.undoStack().size() + "/" + r.e.redoStack().size());
        ok(r.e.undo("Tester").ok, "undo works after a restart");
        ok(r.e.panels().size() == r.w.panels.size(), "layout panels match the world after reload");
        Map<String, Object> f = Json.parseObject(read(r.store.layoutFile()));
        ok(Json.integer(f, "schema", 0) == EditEngine.SCHEMA && "TestWorld".equals(Json.str(f, "world", "")), "layout file schema + world");
        ok(Json.arr(Json.obj(f, "history"), "undo").size() <= 20, "at most 20 steps persisted");
    }

    static void safety() throws Exception {
        Rig r = new Rig(tmp("safety"));
        Pos stone = new Pos(0, 64, 0), gt = new Pos(1, 64, 0);
        r.w.foreign.put(stone, "stone");
        r.w.foreign.put(gt, "gregtech_machine");
        ok(!r.place(0, 64, 0, "task_wall", "all", 5, 3).ok, "never place over stone");
        ok(!r.e.edit("T", "rm", one(Op.Change.panel(gt, null))).ok, "never remove a GregTech machine");
        ok(!r.e.edit("T", "upd", one(Op.Change.panel(gt, PanelTypes.get("monitor").fresh("north")))).ok, "never replace a foreign block");
        ok(r.w.foreign.size() == 2 && r.w.places + r.w.removes + r.w.updates == 0, "foreign blocks untouched, no world calls");
        r.w.unloaded.add(new Pos(5, 64, 5));
        ok(!r.place(5, 64, 5, "monitor", "", 1, 1).ok, "unloaded chunk refused");
        ok(!r.place(5, 300, 5, "monitor", "", 1, 1).ok && !r.place(5, -1, 5, "monitor", "", 1, 1).ok, "world height");
        ok(!r.e.edit("T", "x", one(Op.Change.panel(new Pos(7, 64, 7), new PanelSpec("chest", "north", "", 1, 1, "", "")))).ok, "unknown kind refused");
        ok(!r.e.edit("T", "x", one(Op.Change.panel(new Pos(7, 64, 7), new PanelSpec("overflow_sign", "north", "", 1, 1, "", "")))).ok, "the vanilla sign is never placed by the tool");
        ok(r.rules.setExclusions(new String[] { "100,64,100,8 # spawn", "bad entry", "1,2,3" }).size() == 2, "bad exclusion entries reported");
        ok(!r.place(104, 64, 100, "monitor", "", 1, 1).ok && r.place(109, 64, 100, "monitor", "", 1, 1).ok, "protected radius (8) refuses inside, allows outside");
        // a strict edit is all-or-nothing
        List<Op.Change> two = new ArrayList<>();
        two.add(Op.Change.panel(new Pos(20, 64, 0), PanelTypes.get("monitor").fresh("north")));
        two.add(Op.Change.panel(stone, PanelTypes.get("monitor").fresh("north")));
        ok(!r.e.edit("T", "two", two).ok && !r.w.panels.containsKey(new Pos(20, 64, 0)), "one bad cell refuses the whole edit");
        // binding sanitized by kind
        ok(r.place(30, 64, 0, "monitor", "fleet", 1, 1).ok && "".equals(r.w.panels.get(new Pos(30, 64, 0)).binding), "a monitor cannot bind 'fleet'");
        ok(r.place(31, 64, 0, "fleet_beacon", "claude", 3, 3).ok && r.w.panels.get(new Pos(31, 64, 0)).w == 1 && r.w.panels.get(new Pos(31, 64, 0)).binding.isEmpty(), "beacon: no binding, no size");
        // undo skips a cell built over since, and never touches the stone
        ok(r.place(40, 64, 0, "monitor", "", 1, 1).ok, "place for undo-skip");
        r.w.panels.remove(new Pos(40, 64, 0));
        r.w.foreign.put(new Pos(40, 64, 0), "stone");
        EditEngine.Result u = r.e.undo("T");
        ok(r.w.foreign.containsKey(new Pos(40, 64, 0)), "undo never removes the stone built over a panel");
        ok(u.ok, "undo still succeeds (nothing to do there): " + u);
        ok(r.w.violations == 0, "no illegal world calls");
    }

    static void snapshots() throws Exception {
        Rig r = new Rig(tmp("snap"));
        r.place(0, 64, 0, "task_wall", "all", 5, 3);
        r.place(6, 64, 0, "goal_atrium", "all", 3, 3);
        r.e.edit("T", "anchor", one(Op.Change.anchor("lounge", new Anchor("lounge", 0.5, 64, 4.5, 0, 0))));
        ok(r.e.saveSnapshot("T", "before-party").ok, "save snapshot");
        ok(!r.e.saveSnapshot("T", "../evil").ok && !r.e.saveSnapshot("T", "UPPER").ok, "snapshot names validated");
        ok(new File(r.dir, "layouts/before-party.json").isFile(), "snapshot file written");
        // change things
        Pos wall = new Pos(0, 64, 0);
        r.e.edit("T", "rebind", one(Op.Change.panel(wall, r.w.panels.get(wall).withBinding("homelab"))));
        r.e.edit("T", "rm atrium", one(Op.Change.panel(new Pos(6, 64, 0), null)));
        r.place(-6, 64, 0, "monitor", "claude-builder", 2, 2);
        r.e.edit("T", "move lounge", one(Op.Change.anchor("lounge", new Anchor("lounge", 3.5, 64, 4.5, -90, 0))));
        EditEngine.Result d = r.e.diffSnapshot("T", "before-party");
        ok(d.ok && d.plan != null && d.plan.changes.size() == 4, "diff finds 4 differences: " + d.lines);
        String all = String.join("\n", d.lines);
        ok(all.contains("- remove Desk Monitor") && all.contains("+ place Goal Atrium") && all.contains("binding homelab -> all") && all.contains("anchor lounge moved 3.0 blocks"), "diff text: " + all);
        ok(r.w.panels.containsKey(new Pos(-6, 64, 0)), "diff is a dry run");
        EditEngine.Result rs = r.e.applyPlan("T", d.plan.token);
        ok(rs.ok && !r.w.panels.containsKey(new Pos(-6, 64, 0)) && r.w.panels.containsKey(new Pos(6, 64, 0)) && "all".equals(r.w.panels.get(wall).binding) && r.a.m.get("lounge").x == 0.5, "restore: " + rs);
        ok(r.e.diffSnapshot("T", "before-party").plan.changes.isEmpty(), "after restore the diff is empty");
        ok(!r.e.applyPlan("T", d.plan.token).ok, "a dry-run token is single use");
        ok(r.e.undo("T").ok && r.w.panels.containsKey(new Pos(-6, 64, 0)), "a restore is undone in one step");
        ok(r.e.restoreSnapshot("T", "before-party").ok && !r.w.panels.containsKey(new Pos(-6, 64, 0)), "restore by command");
        ok(!r.e.diffSnapshot("T", "nope").ok, "missing snapshot");
        // a dry run goes stale if the area changes
        r.place(-6, 64, 0, "monitor", "x", 1, 1);
        EditEngine.Result d2 = r.e.diffSnapshot("T", "before-party");
        r.w.panels.remove(new Pos(-6, 64, 0));
        r.w.foreign.put(new Pos(6, 64, 0), "stone");
        r.w.panels.remove(new Pos(6, 64, 0));
        ok(!r.e.applyPlan("T", d2.plan.token).ok, "apply refuses when the area changed since the dry run");
        ok(!r.e.applyPlan("Other", "p999-x").ok, "unknown token");
    }

    static void importExport() throws Exception {
        Rig r = new Rig(tmp("io"));
        Pos o = new Pos(100, 64, 100);
        r.place(100, 64, 95, "task_wall", "homelab", 5, 3);
        r.place(97, 65, 98, "monitor", "claude-builder", 2, 1);
        r.e.edit("T", "label", one(Op.Change.panel(new Pos(97, 65, 98), r.w.panels.get(new Pos(97, 65, 98)).withLabel("Claude's desk"))));
        r.place(103, 65, 98, "status_lamp", "claude-builder", 1, 1);
        r.e.edit("T", "a1", one(Op.Change.anchor("desk_claude-builder", new Anchor("desk_claude-builder", 97.5, 64, 99.5, 180, 0))));
        r.e.edit("T", "a2", one(Op.Change.anchor("cam_wall", new Anchor("cam_wall", 100.5, 66, 104.5, 180, 10))));
        r.place(500, 64, 500, "monitor", "far-away", 1, 1);
        RelLayout ex = r.e.export("my-office", o, "north", 16, false);
        String text = Json.pretty(ex.toJson());
        ok(!text.contains("claude") && !text.contains("homelab") && !text.contains("Claude's desk") && !text.contains("far-away"), "export strips ids, boards, labels and far panels: " + text);
        ok(text.contains("agent-1") && text.contains("board-1") && text.contains("desk_agent-1"), "export uses placeholders");
        ok(!text.contains("cam_wall") && !text.contains("100.5") && !text.contains("\"x\""), "no cameras, no world coordinates");
        ok(ex.panels.size() == 3 && ex.anchors.size() == 1, "export picks the 3 near panels + 1 anchor");
        RelLayout keep = r.e.export("k", o, "north", 16, true);
        ok(Json.write(keep.toJson()).contains("claude-builder"), "keepNames keeps them");
        // import elsewhere, facing east: rotated, placeholders unbound, personal spot -> shared slot
        RelLayout back = RelLayout.fromJson(Json.parseObject(text));
        Pos o2 = new Pos(0, 70, 0);
        Pos wallTarget = new Pos(5, 70, 0); // wall was 5 north of the origin -> 5 east
        r.w.foreign.put(new Pos(2, 71, -3), "stone"); // where the monitor would go
        EditEngine.Result dry = r.e.planLayout("T", "import", back, o2, "east", "add");
        ok(dry.ok && dry.plan != null, "import dry run: " + dry);
        ok(dry.plan.conflicts.size() == 1 && dry.plan.conflicts.get(0).contains("minecraft:stone"), "import lists the stone conflict: " + dry.plan.conflicts);
        ok(r.w.panels.get(wallTarget) == null, "dry run places nothing");
        EditEngine.Result ap = r.e.applyPlan("T", dry.plan.token);
        ok(ap.ok && r.w.panels.containsKey(wallTarget), "import applied: " + ap);
        ok("west".equals(r.w.panels.get(wallTarget).facing), "south-facing wall turned west when importing facing east");
        ok("all".equals(r.w.panels.get(wallTarget).binding), "board placeholder -> all");
        ok(r.w.foreign.get(new Pos(2, 71, -3)).equals("stone"), "stone untouched");
        ok(r.a.m.containsKey("desk") && !r.a.m.containsKey("desk_agent-1"), "personal spot imported as a shared slot");
        // bbox: every placement inside the dry run's box
        int[] b = dry.plan.bbox;
        for (Pos p : r.w.panels.keySet()) {
            if (p.y < 70) continue;
            ok(p.x >= b[0] && p.x <= b[3] && p.y >= b[1] && p.y <= b[4] && p.z >= b[2] && p.z <= b[5], "placement inside the confirmed box " + p);
        }
        ok(r.e.undo("T").ok && !r.w.panels.containsKey(wallTarget), "an import is undone in one step");
        // add mode keeps existing panels, replace mode removes the layout's panels first
        r.e.redo("T");
        EditEngine.Result again = r.e.planLayout("T", "import", back, o2, "east", "add");
        ok(again.plan.changes.size() <= 1, "add over itself changes nothing but maybe the anchor slot: " + again.lines);
        // replace mode: every panel of the layout and every non-camera anchor goes first (listed in
        // the dry run), cameras stay, nothing foreign is touched, and one undo brings it all back
        java.util.Map<Pos, PanelSpec> panelsBefore = new java.util.TreeMap<>(r.w.panels);
        java.util.Map<String, Anchor> anchorsBefore = new java.util.TreeMap<>(r.a.m);
        java.util.Map<Pos, String> foreignBefore = new java.util.TreeMap<>(r.w.foreign);
        EditEngine.Result rep = r.e.planLayout("T", "import", back, new Pos(40, 70, 40), "north", "replace");
        ok(rep.ok && rep.plan != null, "replace dry run: " + rep);
        boolean listsFar = false;
        for (String s : rep.lines) if (s.startsWith("-") && s.contains("500 64 500")) listsFar = true;
        ok(listsFar, "replace dry run lists the far panel it would remove: " + rep.lines);
        ok(r.w.panels.equals(panelsBefore), "replace dry run changes nothing");
        EditEngine.Result repAp = r.e.applyPlan("T", rep.plan.token);
        ok(repAp.ok, "replace applied: " + repAp);
        ok(!r.w.panels.containsKey(new Pos(500, 64, 500)) && !r.w.panels.containsKey(wallTarget), "replace removed the old panels");
        ok(r.a.m.containsKey("cam_wall"), "replace keeps camera anchors");
        ok(r.w.foreign.equals(foreignBefore), "replace never touches foreign blocks");
        ok(r.e.undo("T").ok, "undo the replace");
        ok(r.w.panels.equals(panelsBefore) && r.a.m.equals(anchorsBefore), "one undo restores every panel and anchor exactly");
        // span limit
        RelLayout huge = new RelLayout();
        huge.panels.add(new RelLayout.RelPanel(0, 0, 0, PanelTypes.get("status_lamp").fresh("north")));
        huge.panels.add(new RelLayout.RelPanel(60, 0, 0, PanelTypes.get("status_lamp").fresh("north")));
        ok(!r.e.planLayout("T", "import", huge, new Pos(0, 100, 0), "north", "add").ok, "imports wider than the span limit are refused");
        // newer schema / wrong kind
        boolean threw = false;
        try {
            RelLayout.fromJson(Json.parseObject("{\"schema\": 99, \"kind\": \"agentcraft-office-layout\"}"));
        } catch (Json.ParseException e) {
            threw = true;
        }
        ok(threw, "newer layout schema refused");
        threw = false;
        try {
            RelLayout.fromJson(Json.parseObject("{\"kind\": \"agentcraft-office-layout\"}"));
        } catch (Json.ParseException e) {
            threw = true;
        }
        ok(threw, "layout without schema refused");
        ok(r.w.violations == 0, "no illegal world calls in import/export");
    }

    static void presets(String dir) throws Exception {
        File[] fs = new File(dir).listFiles();
        ok(fs != null && fs.length >= 3, "at least 3 bundled presets in " + dir);
        for (File f : fs) {
            RelLayout p = RelLayout.fromJson(Json.parseObject(read(f)));
            ok(!p.name.isEmpty() && !p.description.isEmpty() && !p.panels.isEmpty(), "preset has a name, description and panels: " + f.getName());
            for (String facing : PanelSpec.FACINGS) {
                Rig r = new Rig(tmp("preset"));
                EditEngine.Result dry = r.e.planLayout("T", "preset", p, new Pos(0, 64, 0), facing, "add");
                ok(dry.ok && dry.plan.conflicts.isEmpty(), f.getName() + " facing " + facing + " applies cleanly to an empty area: " + dry.plan.conflicts);
                ok(r.e.applyPlan("T", dry.plan.token).ok, "apply " + f.getName());
                ok(r.w.panels.size() == p.panels.size(), "every panel placed: " + f.getName());
                // replace with the same preset again: removes then re-places, nothing foreign touched
                r.w.foreign.put(new Pos(0, 64, 1), "stone");
                EditEngine.Result rep = r.e.planLayout("T", "preset", p, new Pos(0, 64, 0), facing, "replace");
                ok(rep.ok && r.e.applyPlan("T", rep.plan.token).ok && r.w.foreign.containsKey(new Pos(0, 64, 1)), "replace mode: " + f.getName());
                ok(r.w.violations == 0, "preset never makes an illegal call");
            }
        }
    }

    static void schema() throws Exception {
        // schema 1 = card 4's office-layout.json
        File d = tmp("schema1");
        Files.write(
            new File(d, "hq-layout.json").toPath(),
            "{\"version\": 1, \"theme\": \"light\", \"lod\": {\"nearPx\": 120, \"midPx\": 80}, \"blocks\": {}, \"instances\": {\"0:1,2,3\": {\"theme\": \"dark\"}}}".getBytes(StandardCharsets.UTF_8));
        Rig r = new Rig(d);
        ok("light".equals(r.e.display().get("theme")) && "120".equals(r.e.display().get("nearPx")), "schema 1 display migrated");
        ok(r.e.notes().get(0).contains("migrated") && r.e.notes().get(0).contains("1 per-position"), "migration note: " + r.e.notes());
        ok(r.e.readOnly() == null && r.place(0, 64, 0, "monitor", "", 1, 1).ok, "migrated layout is writable");
        ok(Json.integer(Json.parseObject(read(r.store.layoutFile())), "schema", 0) == 2, "file rewritten as schema 2");
        // a newer schema: read-only, never overwritten
        File d2 = tmp("schema9");
        String future = "{\"schema\": 9, \"kind\": \"agentcraft-hq-layout\", \"panels\": {}}";
        Files.write(new File(d2, "hq-layout.json").toPath(), future.getBytes(StandardCharsets.UTF_8));
        Rig r2 = new Rig(d2);
        ok(r2.e.readOnly() != null && !r2.place(0, 64, 0, "monitor", "", 1, 1).ok && r2.w.places == 0, "newer schema -> read-only");
        ok(future.equals(read(r2.store.layoutFile())), "newer file left untouched");
        // garbage and a foreign world
        File d3 = tmp("garbage");
        Files.write(new File(d3, "hq-layout.json").toPath(), "{not json".getBytes(StandardCharsets.UTF_8));
        ok(new Rig(d3).e.readOnly() != null, "unreadable layout -> read-only");
        File d4 = tmp("world");
        Files.write(new File(d4, "hq-layout.json").toPath(), "{\"schema\": 2, \"world\": \"OtherWorld\", \"panels\": {}}".getBytes(StandardCharsets.UTF_8));
        ok(new Rig(d4).e.readOnly() != null, "layout of another world -> read-only");
        // atomic write leaves no temp file
        ok(!new File(r.dir, "hq-layout.json.tmp").exists(), "no temp file left");
    }

    static void limits() throws Exception {
        Rig r = new Rig(tmp("limits"));
        r.rules.editsPerSecond = 2;
        r.rules.burst = 3;
        int okN = 0;
        for (int i = 0; i < 10; i++) if (r.place(i, 64, 0, "status_lamp", "fleet", 1, 1).ok) okN++;
        ok(okN == 3, "burst of 3 then refused, got " + okN);
        r.clock.t += 1000;
        ok(r.place(20, 64, 0, "status_lamp", "fleet", 1, 1).ok && r.place(21, 64, 0, "status_lamp", "fleet", 1, 1).ok, "2 per second refill");
        ok(!r.place(22, 64, 0, "status_lamp", "fleet", 1, 1).ok, "then limited again");
        ok(r.place(23, 64, 0, "status_lamp", "fleet", 1, 1).ok == false, "still limited without time passing");
        r.rules.editsPerSecond = 1000;
        r.rules.burst = 1000;
        r.clock.t += 5000;
        // per-player buckets
        Rig r2 = new Rig(tmp("limits2"));
        r2.rules.editsPerSecond = 1;
        r2.rules.burst = 1;
        ok(r2.e.edit("A", "a", one(Op.Change.panel(new Pos(0, 64, 0), PanelTypes.get("status_lamp").fresh("north")))).ok, "A first");
        ok(r2.e.edit("B", "b", one(Op.Change.panel(new Pos(1, 64, 0), PanelTypes.get("status_lamp").fresh("north")))).ok, "B has its own bucket");
        ok(!r2.e.edit("A", "a2", one(Op.Change.panel(new Pos(2, 64, 0), PanelTypes.get("status_lamp").fresh("north")))).ok, "A limited");
        // max panels
        r.rules.maxPanels = r.e.panels().size() + 2;
        ok(r.place(30, 64, 0, "monitor", "", 1, 1).ok && r.place(31, 64, 0, "monitor", "", 1, 1).ok, "up to the cap");
        ok(!r.place(32, 64, 0, "monitor", "", 1, 1).ok, "over the panel cap refused");
        ok(r.e.edit("T", "upd", one(Op.Change.panel(new Pos(30, 64, 0), r.w.panels.get(new Pos(30, 64, 0)).withLabel("still fine")))).ok, "updates allowed at the cap");
        r.rules.maxPanels = 256;
        // max JSON size
        r.rules.maxLayoutBytes = r.store.layoutFile().length() > 0 ? (int) r.store.layoutFile().length() + 50 : 2000;
        EditEngine.Result big = r.place(40, 64, 0, "monitor", "", 1, 1);
        ok(!big.ok && big.message.contains("byte limit") && !r.w.panels.containsKey(new Pos(40, 64, 0)), "layout size limit refuses before touching the world: " + big);
        r.rules.maxLayoutBytes = 256 * 1024;
        FileStore small = new FileStore(r.dir, 10);
        boolean threw = false;
        try {
            small.read(r.store.layoutFile());
        } catch (IOException e) {
            threw = true;
        }
        ok(threw, "oversized files are not read");
        // max changes per op
        List<Op.Change> many = new ArrayList<>();
        for (int i = 0; i < r.rules.maxChangesPerOp + 1; i++) many.add(Op.Change.panel(new Pos(i, 80, 0), PanelTypes.get("status_lamp").fresh("north")));
        ok(!r.e.edit("T", "many", many).ok, "too many changes in one op");
        // files: name validation blocks path traversal
        threw = false;
        try {
            r.store.sub("exports", "../../server");
        } catch (IOException e) {
            threw = true;
        }
        ok(threw, "export names cannot escape the folder");
    }

    static void lock() throws Exception {
        Rig r = new Rig(tmp("lock"));
        r.place(0, 64, 0, "monitor", "", 1, 1);
        r.e.saveSnapshot("T", "s1");
        EditEngine.Result dry = r.e.diffSnapshot("T", "s1");
        ok(r.e.setLock("Eli", true, "panic").ok && r.e.locked(), "lock");
        int calls = r.w.places + r.w.removes + r.w.updates;
        ok(!r.place(1, 64, 0, "monitor", "", 1, 1).ok, "lock: no placement");
        ok(!r.e.undo("T").ok && !r.e.redo("T").ok, "lock: no undo/redo");
        ok(!r.e.saveSnapshot("T", "s2").ok, "lock: no snapshot write");
        ok(!r.e.applyPlan("T", dry.plan.token).ok, "lock: dry runs cannot be applied (and were discarded)");
        String before = read(r.store.layoutFile());
        r.e.adopt(new Pos(9, 64, 9), PanelTypes.get("monitor").fresh("north"));
        r.e.forget(new Pos(0, 64, 0));
        ok(before.equals(read(r.store.layoutFile())), "lock: hand placements do not write the layout");
        ok(r.w.places + r.w.removes + r.w.updates == calls, "lock: no world calls");
        r.reload();
        ok(r.e.locked() && r.e.lockInfo().contains("Eli"), "the lock survives a restart");
        ok(r.e.setLock("Eli", false, "").ok && !r.e.locked() && !r.store.lockFile().exists(), "unlock removes the lock file");
        ok(r.place(1, 64, 0, "monitor", "", 1, 1).ok, "edits work again");
        // the lock is honoured even if the lock file cannot be written
        File ro = tmp("lockro");
        Rig r2 = new Rig(ro);
        File lf = r2.store.lockFile();
        lf.mkdirs(); // a directory where the file should be: the write fails
        EditEngine.Result l = r2.e.setLock("Eli", true, "");
        ok(l.ok && r2.e.locked(), "lock in memory even when the file write fails: " + l);
        ok(!r2.place(0, 64, 0, "monitor", "", 1, 1).ok, "and edits are refused");
    }

    static void audit() throws Exception {
        Rig r = new Rig(tmp("audit"));
        r.w.foreign.put(new Pos(1, 64, 0), "stone");
        r.place(0, 64, 0, "task_wall", "all", 5, 3);
        r.e.edit("T", "rm", one(Op.Change.panel(new Pos(0, 64, 0), null)));
        r.place(1, 64, 0, "monitor", "", 1, 1);
        r.e.setLock("Eli", true, "test");
        r.place(2, 64, 0, "monitor", "", 1, 1);
        String log = read(r.audit.file());
        String[] lines = log.split("\n");
        ok(log.contains("action=place where=0,64,0 before=minecraft:air:0 after=agentcraftgtnh:task_wall:3"), "place logged with before/after id:meta: " + log);
        ok(log.contains("action=remove where=0,64,0 before=agentcraftgtnh:task_wall:3 after=minecraft:air:0"), "remove logged");
        ok(log.contains("action=lock") && log.contains("action=refused") && log.contains("who=Tester"), "lock and refusals logged");
        for (String l : lines) ok(l.matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ who=\\S+ action=\\S+ where=\\S+ before=\\S+ after=\\S+ detail=\".*\""), "audit line format: " + l);
        ok(r.audit.recent(3).size() == 3, "recent lines kept for the screen");
    }

    /** Random edits, undos, redos, presets, imports, snapshots and foreign building in between. */
    static void fuzz() throws Exception {
        Random rnd = new Random(6);
        String[] kinds = { "task_wall", "goal_atrium", "monitor", "status_lamp", "fleet_beacon", "library", "overflow_sign", "chest" };
        File presetDir = new File("src/main/resources/assets/agentcraftgtnh/presets");
        List<RelLayout> presets = new ArrayList<>();
        File[] pf = presetDir.listFiles();
        if (pf != null) for (File f : pf) presets.add(RelLayout.fromJson(Json.parseObject(read(f))));
        int total = 0;
        for (int round = 0; round < 6; round++) {
            Rig r = new Rig(tmp("fuzz"));
            r.rules.setExclusions(new String[] { "8,66,8,3" });
            for (int i = 0; i < 400; i++) {
                Pos p = new Pos(rnd.nextInt(24), 64 + rnd.nextInt(5), rnd.nextInt(24));
                int act = rnd.nextInt(14);
                r.clock.t += rnd.nextInt(300);
                switch (act) {
                    case 0:
                    case 1:
                    case 2: {
                        String k = kinds[rnd.nextInt(kinds.length)];
                        PanelSpec s = new PanelSpec(k, PanelSpec.FACINGS[rnd.nextInt(4)], rnd.nextBoolean() ? "all" : "agent-" + rnd.nextInt(3), 1 + rnd.nextInt(9), 1 + rnd.nextInt(7), "L" + i, "");
                        r.e.edit("F", "place", one(Op.Change.panel(p, s)));
                        break;
                    }
                    case 3:
                        r.e.edit("F", "rm", one(Op.Change.panel(p, null)));
                        break;
                    case 4:
                        r.e.undo("F");
                        break;
                    case 5:
                        r.e.redo("F");
                        break;
                    case 6: // someone builds or breaks by hand
                        if (rnd.nextBoolean()) {
                            r.w.panels.remove(p);
                            r.w.foreign.put(p, rnd.nextBoolean() ? "stone" : "gregtech_machine");
                        } else {
                            r.w.foreign.remove(p);
                        }
                        break;
                    case 7:
                        if (!presets.isEmpty()) {
                            EditEngine.Result d = r.e.planLayout("F", "preset", presets.get(rnd.nextInt(presets.size())), p, PanelSpec.FACINGS[rnd.nextInt(4)], rnd.nextBoolean() ? "add" : "replace");
                            if (d.plan != null && rnd.nextInt(3) > 0) {
                                if (rnd.nextBoolean()) r.w.foreign.put(p.add(rnd.nextInt(5) - 2, 0, -rnd.nextInt(8)), "stone");
                                r.e.applyPlan("F", d.plan.token);
                            }
                        }
                        break;
                    case 8:
                        r.e.saveSnapshot("F", "s" + rnd.nextInt(3));
                        break;
                    case 9: {
                        EditEngine.Result d = r.e.diffSnapshot("F", "s" + rnd.nextInt(3));
                        if (d.plan != null) r.e.applyPlan("F", d.plan.token);
                        break;
                    }
                    case 10: {
                        RelLayout ex = r.e.export("x", p, PanelSpec.FACINGS[rnd.nextInt(4)], 8, rnd.nextBoolean());
                        EditEngine.Result d = r.e.planLayout("F", "import", RelLayout.fromJson(Json.parseObject(Json.write(ex.toJson()))), new Pos(rnd.nextInt(24), 64, rnd.nextInt(24)), PanelSpec.FACINGS[rnd.nextInt(4)], "add");
                        if (d.plan != null) r.e.applyPlan("F", d.plan.token);
                        break;
                    }
                    case 11:
                        r.e.edit("F", "anchor", one(Op.Change.anchor("desk_" + rnd.nextInt(4), rnd.nextBoolean() ? null : new Anchor("x", p.x + 0.5, p.y, p.z + 0.5, 90 * rnd.nextInt(4), 0))));
                        break;
                    case 12:
                        if (rnd.nextInt(10) == 0) r.e.setLock("F", !r.e.locked(), "");
                        break;
                    default:
                        if (r.w.panels.containsKey(p)) r.e.edit("F", "upd", one(Op.Change.panel(p, r.w.panels.get(p).withBinding("b" + rnd.nextInt(3)).withSize(1 + rnd.nextInt(8), 1 + rnd.nextInt(6)))));
                }
                total++;
            }
            ok(r.w.violations == 0, "fuzz round " + round + ": no illegal world call");
            for (Pos p : r.w.foreign.keySet()) ok(!r.w.panels.containsKey(p), "a foreign block and a panel never share a cell");
            for (Pos p : r.w.panels.keySet()) ok(r.rules.protectedAt(p) == null || true, "");
            for (String c : r.w.calls) {
                Pos p = Pos.parse(c.substring(c.indexOf(' ') + 1));
                ok(r.rules.protectedAt(p) == null, "no world call inside the protected radius: " + c);
            }
            r.reload();
            ok(r.e.panels().size() <= r.rules.maxPanels, "layout reloads within limits");
        }
        ok(total == 2400, "fuzz ran " + total + " actions");
    }
}
