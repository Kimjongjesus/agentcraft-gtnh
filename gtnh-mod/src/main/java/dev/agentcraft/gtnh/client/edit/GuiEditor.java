package dev.agentcraft.gtnh.client.edit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.agentcraft.gtnh.edit.Json;
import dev.agentcraft.gtnh.edit.PanelType;
import dev.agentcraft.gtnh.edit.PanelTypes;
import dev.agentcraft.gtnh.hq.Anchor;
import dev.agentcraft.gtnh.hq.StationAssigner;
import dev.agentcraft.gtnh.ui.TextLayout;
import dev.agentcraft.gtnh.ui.Theme;
import dev.agentcraft.gtnh.ui.Ui;
import dev.agentcraft.gtnh.ui.UiFont;
import dev.agentcraft.gtnh.ui.Widgets;

/**
 * Card 6 editor (edit tool, right-click anywhere but a panel): three tabs.
 * <ul>
 * <li>Palette: every registered panel kind ({@link PanelTypes}) with a live preview from its
 * card-4 renderer and a "give me this block" button. A new kind shows up with no changes here.
 * <li>Anchors: every station from hq-anchors.json grouped with slot counts and missing stations;
 * select one (here or by right-clicking its marker in the world) to move it to your crosshair or
 * your feet, rotate, add a slot, teleport or remove it.
 * <li>Layouts: undo/redo, named snapshots with a diff and restore, bundled presets and imports as
 * dry runs (what would change, what is skipped and why, the bounding box) applied by one click,
 * export of the area around you, the lock, and the latest audit lines.
 * </ul>
 */
public class GuiEditor extends EditGui {

    public static final int PALETTE = 0, ANCHORS = 1, LAYOUTS = 2;

    private final Widgets.Tabs tabs = new Widgets.Tabs("Panel palette", "Anchor editor", "Layouts & undo");
    private final Widgets.ScrollList left = scroll(), right = scroll();
    private final Widgets.SearchField snapName = field("snapshot name, e.g. before-party", 32);
    private final Widgets.SearchField exportName = field("export name", 32);
    private String selected;
    private final int[] signPos;
    private final String initialAction;
    private boolean armedRemove, keepNames, sentInitial;

    /**
     * @param tab           PALETTE / ANCHORS / LAYOUTS
     * @param anchor        anchor to select (Anchors tab), or null
     * @param signPos       a vanilla sign the tool was used on (offers "use as overflow sign"), or null
     * @param initialAction dev/QA: an action to send on open ("diff:NAME", "preset:ID:MODE", "import:NAME:MODE", "undo")
     */
    public GuiEditor(int tab, String anchor, int[] signPos, String initialAction) {
        tabs.selected = Math.max(0, Math.min(tab, 2));
        selected = anchor;
        this.signPos = signPos;
        this.initialAction = initialAction;
        snapName.text = "";
        exportName.text = "my-office";
    }

    @Override
    public void initGui() {
        super.initGui();
        ClientEdit.send("a", "hello");
        if (initialAction != null && !sentInitial) {
            sentInitial = true;
            String[] p = initialAction.split(":");
            switch (p[0]) {
                case "diff":
                    ClientEdit.send("a", "snap.diff", "name", p[1]);
                    break;
                case "preset":
                    ClientEdit.send("a", "preset.dry", "name", p[1], "mode", p.length > 2 ? p[2] : "add");
                    break;
                case "import":
                    ClientEdit.send("a", "import.dry", "name", p[1], "mode", p.length > 2 ? p[2] : "add");
                    break;
                case "undo":
                    ClientEdit.send("a", "undo");
                    break;
                default:
            }
        }
    }

    @Override
    protected void draw(float partial) {
        backdrop();
        float top = title("Office editor", "panels, anchors and layouts of this world's HQ");
        float pad = 8;
        float tb = tabs.layout(pad, top, width - 2 * pad, 14);
        tabs.draw(th, mx, my);
        for (int i = 0; i < tabs.tabs.size(); i++) {
            final int k = i;
            Widgets.Button b = tabs.tabs.get(i);
            region(b.x0, b.y0, b.x1, b.y1, () -> {
                tabs.selected = k;
                left.offset = right.offset = 0;
                armedRemove = false;
            });
        }
        float y0 = tb + 8, y1 = height - 22;
        switch (tabs.selected) {
            case PALETTE:
                palette(pad, y0, width - pad, y1);
                break;
            case ANCHORS:
                anchors(pad, y0, width - pad, y1);
                break;
            default:
                layouts(pad, y0, width - pad, y1);
        }
        statusStrip();
    }

    // ---- palette -----------------------------------------------------------------------------

    private void palette(float x0, float y0, float x1, float y1) {
        List<PanelType> types = PanelTypes.all();
        int cols = width > 520 ? 3 : 2;
        float gap = 6, cw = (x1 - x0 - gap * (cols - 1)) / cols, chh = 112;
        int rows = (types.size() + cols - 1) / cols;
        left.bounds(x0, y0, x1, y1);
        left.setContent(rows * (chh + gap));
        if (signPos != null) {
            // the tool was used on a vanilla sign: offer the overflow binding right here
            float w = button("Use the sign at " + signPos[0] + " " + signPos[1] + " " + signPos[2] + " as the overflow sign", x0, y0 - 2, 14, !ClientEdit.locked(), true,
                () -> ClientEdit.send("a", "sign.overflow", "pos", signPos[0] + "," + signPos[1] + "," + signPos[2]));
            left.bounds(x0, y0 + 16, x1, y1);
        }
        Widgets.clip(this, left.x0, left.y0, left.x1, left.y1);
        for (int i = 0; i < types.size(); i++) {
            final PanelType t = types.get(i);
            float cx0 = x0 + (i % cols) * (cw + gap), cy0 = left.y0 + (i / cols) * (chh + gap) - left.offset;
            float cx1 = cx0 + cw, cy1 = cy0 + chh;
            if (cy1 < left.y0 || cy0 > left.y1) continue;
            card(cx0, cy0, cx1, cy1);
            float pw = Math.min(cw * 0.42F, 92);
            PanelPreview.draw(t, t.defaultBinding(), "dark", t.resizable ? t.defW : 1, t.resizable ? t.defH : 1, cx0 + 5, cy0 + 5, cx0 + 5 + pw, cy1 - 24);
            float tx = cx0 + pw + 12, tw = cx1 - tx - 6;
            UiFont.bold()
                .drawFit(t.name, tx, cy0 + 6, Widgets.TITLE, tw, 0xFF000000 | th.text);
            float ty = wrapped(t.description, tx, cy0 + 20, tw, Widgets.SMALL + 0.4F, th.text, 4);
            UiFont.regular()
                .drawFit(PanelPreview.bindsTo(t) + (t.resizable ? "  \u00b7  default " + t.defW + "\u00d7" + t.defH : ""), tx, ty + 2, Widgets.SMALL, tw, 0xFF000000 | th.muted);
            UiFont.mono()
                .drawFit(t.id + (t.previewPanel.isEmpty() ? "" : " \u00b7 panel " + t.previewPanel), tx, ty + 11, Widgets.SMALL - 0.6F, tw, 0xFF000000 | th.muted);
            if (cy1 - 20 >= left.y0 && cy1 - 4 <= left.y1) {
                buttonW(PanelType.SIGN.equals(t.source) ? "Give me a sign" : "Give me this block", cx0 + 5, cy1 - 19, cw - 10, 14, true, true, () -> ClientEdit.send("a", "palette.give", "type", t.id));
            }
        }
        Widgets.unclip();
        left.drawBar(th);
    }

    // ---- anchors -----------------------------------------------------------------------------

    private static String stationOf(String n) {
        for (String st : StationAssigner.STATIONS) if (n.equals(st) || n.startsWith(st + "_")) return st;
        return null;
    }

    private void anchors(float x0, float y0, float x1, float y1) {
        List<Object[]> all = ClientEdit.anchors();
        Map<String, List<Object[]>> groups = new LinkedHashMap<>();
        for (String st : StationAssigner.STATIONS) groups.put(st, new ArrayList<>());
        groups.put("other", new ArrayList<>());
        for (Object[] a : all) {
            String st = stationOf((String) a[0]);
            groups.get(st == null ? "other" : st)
                .add(a);
        }
        float lw = Math.max(170, Math.min((x1 - x0) * 0.44F, 260));
        left.bounds(x0, y0, x0 + lw, y1);
        float y = 0;
        List<Object[]> rows = new ArrayList<>(); // {header or anchor, y}
        for (Map.Entry<String, List<Object[]>> g : groups.entrySet()) {
            if (g.getValue()
                .isEmpty() && "other".equals(g.getKey())) continue;
            rows.add(new Object[] { g.getKey(), y, g.getValue()
                .size() });
            y += 14;
            for (Object[] a : g.getValue()) {
                rows.add(new Object[] { a, y });
                y += 14;
            }
            y += 3;
        }
        left.setContent(y);
        Widgets.clip(this, left.x0, left.y0, left.x1, left.y1);
        Object[] sel = null;
        for (Object[] r : rows) {
            float ry = left.y0 + (Float) r[1] - left.offset;
            if (ry + 14 < left.y0 || ry > left.y1) {
                if (r[0] instanceof Object[] && ((Object[]) r[0])[0].equals(selected)) sel = (Object[]) r[0];
                continue;
            }
            if (r[0] instanceof String) {
                String st = (String) r[0];
                int n = (Integer) r[2];
                boolean station = !"other".equals(st);
                UiFont.bold()
                    .draw(station ? st : "cameras, signs, others", x0 + 2, ry + 3, Widgets.SMALL + 0.8F, 0xFF000000 | (station && n == 0 ? th.danger : th.muted));
                if (station) {
                    String note = n == 0 ? "no anchor (agents fall back to the lounge)" : n + (n == 1 ? " slot" : " slots");
                    UiFont.regular()
                        .drawRight(note, x0 + lw - 8, ry + 3, Widgets.SMALL, 0xFF000000 | (n == 0 ? th.danger : th.muted));
                }
                continue;
            }
            final Object[] a = (Object[]) r[0];
            String name = (String) a[0];
            boolean s = name.equals(selected);
            if (s) sel = a;
            boolean hv = hover(x0, Math.max(ry, left.y0), x0 + lw - 5, Math.min(ry + 13, left.y1));
            Ui.round(x0 + 6, ry, x0 + lw - 5, ry + 13, 3, 0xFF000000 | (s ? th.cardSelected : hv ? th.raised : th.surface));
            int col = name.startsWith("cam_") ? 0x5FC4C0 : StationAssigner.isStandingAnchor(name) ? 0x7FD77F : 0xE8C547;
            Ui.dot(x0 + 13, ry + 6.5F, 2.6, 0xFF000000 | col);
            UiFont.mono()
                .drawFit(name, x0 + 19, ry + 3, Widgets.SMALL + 0.4F, lw - 110, 0xFF000000 | (s ? th.cardText : th.text));
            UiFont.regular()
                .drawRight(String.format(Locale.ROOT, "%.0f %.0f %.0f \u00b7 %s", (Double) a[1], (Double) a[2], (Double) a[3], Anchor.facingName(((Double) a[4]).floatValue())), x0 + lw - 9, ry + 3, Widgets.SMALL, 0xFF000000 | (s ? th.cardMuted : th.muted));
            region(x0 + 6, Math.max(ry, left.y0), x0 + lw - 5, Math.min(ry + 13, left.y1), () -> {
                selected = (String) a[0];
                armedRemove = false;
            });
        }
        Widgets.unclip();
        left.drawBar(th);

        // right: selected anchor + actions, missing stations
        float rx0 = x0 + lw + 10, rx1 = x1;
        float yy = y0;
        List<String> missing = ClientEdit.strings("missing");
        yy = sectionLabel("MISSING STATIONS  \u00b7  click to create one where you stand", rx0, yy);
        if (missing.isEmpty()) {
            UiFont.regular()
                .draw("Every station has at least one anchor.", rx0, yy, Widgets.BODY, 0xFF000000 | th.muted);
            yy += 14;
        } else {
            float bx = rx0;
            for (final String m : missing) {
                float w = UiFont.bold()
                    .width("+ " + m, Widgets.BODY * 0.9F) + 16;
                if (bx + w > rx1) {
                    bx = rx0;
                    yy += 16;
                }
                bx += button("+ " + m, bx, yy, 13, !ClientEdit.locked(), false, () -> ClientEdit.send("a", "anchor.create", "name", m, "mode", "me")) + 3;
            }
            yy += 20;
        }
        yy += 4;
        if (sel == null) {
            yy = sectionLabel("SELECTED ANCHOR", rx0, yy);
            wrapped("Pick an anchor on the left, or right-click its marker in the world with the tool. Markers show in edit mode: green = station spot, cyan = camera, yellow = block anchor.", rx0, yy, rx1 - rx0, Widgets.BODY, th.text, 5);
            return;
        }
        final String n = (String) sel[0];
        String st = stationOf(n);
        yy = sectionLabel("SELECTED ANCHOR", rx0, yy);
        card(rx0, yy, rx1, yy + 46);
        UiFont.heavy()
            .drawFit(n, rx0 + 6, yy + 4, Widgets.TITLE, rx1 - rx0 - 12, 0xFF000000 | th.text);
        UiFont.regular()
            .draw(String.format(Locale.ROOT, "%.1f %.1f %.1f  \u00b7  facing %s", (Double) sel[1], (Double) sel[2], (Double) sel[3], Anchor.facingName(((Double) sel[4]).floatValue())), rx0 + 6, yy + 19, Widgets.BODY, 0xFF000000 | th.text);
        String what = n.startsWith("cam_") ? "a camera viewpoint (agents ignore it)" : "overflow_sign".equals(n) ? "the sign that lists agents over the NPC cap" : st != null ? "a slot of the " + st + " station" + (n.equals(st) ? " (slot 1)" : "") : "not a station name (agents ignore it)";
        UiFont.regular()
            .drawFit(what, rx0 + 6, yy + 31, Widgets.SMALL + 0.4F, rx1 - rx0 - 12, 0xFF000000 | th.muted);
        yy += 52;
        boolean can = !ClientEdit.locked();
        float bx = rx0;
        bx += button("Move to my crosshair", bx, yy, 15, can, false, () -> ClientEdit.send("a", "anchor.move", "name", n, "mode", "crosshair")) + 4;
        button("Move to my feet", bx, yy, 15, can, false, () -> ClientEdit.send("a", "anchor.move", "name", n, "mode", "me"));
        yy += 19;
        bx = rx0;
        bx += button("Rotate left", bx, yy, 15, can, false, () -> ClientEdit.send("a", "anchor.rotate", "name", n, "dir", -1.0)) + 4;
        bx += button("Rotate right", bx, yy, 15, can, false, () -> ClientEdit.send("a", "anchor.rotate", "name", n, "dir", 1.0)) + 4;
        yy += 19;
        bx = rx0;
        if (st != null) bx += button("Add a " + st + " slot at my feet", bx, yy, 15, can, false, () -> ClientEdit.send("a", "anchor.addslot", "name", n)) + 4;
        bx += button("Teleport there", bx, yy, 15, true, false, () -> ClientEdit.send("a", "anchor.tp", "name", n)) + 4;
        yy += 19;
        button(armedRemove ? "Click again to remove " + n : "Remove anchor", rx0, yy, 15, can, armedRemove, () -> {
            if (!armedRemove) {
                armedRemove = true;
                return;
            }
            armedRemove = false;
            ClientEdit.send("a", "anchor.remove", "name", n);
            selected = null;
        });
        yy += 22;
        wrapped("A station with no anchor still works: its agents take a free lounge slot (the missing-anchor fallback). Every change here is undoable and also works as /agentcraft anchor set|remove.", rx0, yy, rx1 - rx0, Widgets.SMALL + 0.4F, th.muted, 4);
    }

    // ---- layouts -----------------------------------------------------------------------------

    private void layouts(float x0, float y0, float x1, float y1) {
        float lw = Math.max(200, Math.min((x1 - x0) * 0.5F, 320));
        float lx1 = x0 + lw;
        boolean can = !ClientEdit.locked() && ClientEdit.str("readOnly")
            .isEmpty();
        left.bounds(x0, y0, lx1, y1);
        float y = left.y0 - left.offset;
        Widgets.clip(this, left.x0, left.y0, left.x1, left.y1);
        // undo / redo
        y = sectionLabel("UNDO / REDO  \u00b7  " + ClientEdit.num("undoN") + " / " + ClientEdit.num("redoN") + " steps (64 kept, last 20 survive a restart)", x0, y);
        List<String> u = ClientEdit.strings("undo"), r = ClientEdit.strings("redo");
        float bw = (lw - 10) / 2;
        buttonW("Undo", x0, y, bw, 16, can && !u.isEmpty(), true, () -> ClientEdit.send("a", "undo"));
        buttonW("Redo", x0 + bw + 6, y, bw, 16, can && !r.isEmpty(), false, () -> ClientEdit.send("a", "redo"));
        y += 19;
        UiFont.regular()
            .drawFit(u.isEmpty() ? "nothing to undo" : "next: " + u.get(0), x0 + 2, y, Widgets.SMALL, bw - 4, 0xFF000000 | th.muted);
        UiFont.regular()
            .drawFit(r.isEmpty() ? "nothing to redo" : "next: " + r.get(0), x0 + bw + 8, y, Widgets.SMALL, bw - 4, 0xFF000000 | th.muted);
        y += 14;
        // snapshots
        y = sectionLabel("SNAPSHOTS  \u00b7  save the office as it is now; diff and restore later", x0, y);
        snapName.at(x0, y, lw - 70, 14);
        snapName.draw(th);
        final String sn = snapName.text.trim()
            .toLowerCase(Locale.ROOT);
        buttonW("Save as", lx1 - 64, y, 62, 14, can && !sn.isEmpty(), true, () -> ClientEdit.send("a", "snap.save", "name", sn));
        y += 18;
        List<String> snaps = ClientEdit.strings("snapshots");
        if (snaps.isEmpty()) {
            UiFont.regular()
                .draw("No snapshots yet.", x0 + 2, y, Widgets.SMALL + 0.4F, 0xFF000000 | th.muted);
            y += 12;
        }
        for (final String s : snaps) {
            UiFont.mono()
                .drawFit(s, x0 + 4, y + 3, Widgets.SMALL + 0.4F, lw - 130, 0xFF000000 | th.text);
            float bx = lx1 - 2;
            float dw = UiFont.bold()
                .width("Diff / restore\u2026", Widgets.SMALL + 1.5F) + 16;
            buttonW("Diff / restore\u2026", bx - dw, y, dw, 13, true, false, () -> ClientEdit.send("a", "snap.diff", "name", s));
            y += 15;
        }
        y += 4;
        // presets
        y = sectionLabel("PRESETS  \u00b7  dry run first; nothing changes until you apply", x0, y);
        for (final String[] p : ClientEdit.presets()) {
            UiFont.bold()
                .drawFit(p[1], x0 + 2, y + 1, Widgets.BODY, lw - 130, 0xFF000000 | th.text);
            float bx = lx1 - 2;
            float w2 = UiFont.bold()
                .width("Replace", Widgets.SMALL + 1.5F) + 16;
            buttonW("Replace", bx - w2, y, w2, 13, can, false, () -> ClientEdit.send("a", "preset.dry", "name", p[0], "mode", "replace"));
            bx -= w2 + 4;
            float w1 = UiFont.bold()
                .width("Add", Widgets.SMALL + 1.5F) + 16;
            buttonW("Add", bx - w1, y, w1, 13, can, false, () -> ClientEdit.send("a", "preset.dry", "name", p[0], "mode", "add"));
            y += 14;
            y = wrapped(p[2] + "  (" + p[3] + " panels, " + p[4] + " anchors)", x0 + 2, y, lw - 4, Widgets.SMALL, th.muted, 2) + 4;
        }
        // import / export
        y = sectionLabel("IMPORT / EXPORT  \u00b7  relative to where you stand and look", x0, y);
        List<String> imps = ClientEdit.strings("imports");
        for (final String s : imps) {
            UiFont.mono()
                .drawFit(s, x0 + 4, y + 3, Widgets.SMALL + 0.4F, lw - 140, 0xFF000000 | th.text);
            float w1 = UiFont.bold()
                .width("Dry-run import", Widgets.SMALL + 1.5F) + 16;
            buttonW("Dry-run import", lx1 - 2 - w1, y, w1, 13, can, false, () -> ClientEdit.send("a", "import.dry", "name", s, "mode", "add"));
            y += 15;
        }
        exportName.at(x0, y, lw - 110, 14);
        exportName.draw(th);
        final String en = exportName.text.trim()
            .toLowerCase(Locale.ROOT);
        buttonW("Export 16 around me", lx1 - 106, y, 104, 14, !en.isEmpty(), false, () -> ClientEdit.send("a", "export", "name", en, "radius", 16.0, "keepNames", keepNames));
        y += 17;
        button(keepNames ? "Keep agent ids and labels: on" : "Keep agent ids and labels: off (placeholders)", x0, y, 12, true, keepNames, () -> keepNames = !keepNames);
        y += 18;
        // display options (saved in the layout, sent to every client)
        y = sectionLabel("DISPLAY  \u00b7  for every panel without its own theme", x0, y);
        Map<String, Object> disp = Json.obj(ClientEdit.view, "display");
        String curTheme = Json.str(disp, "theme", "");
        boolean labels = "true".equals(Json.str(disp, "showLabels", "false"));
        float dx = x0;
        for (final String[] o : new String[][] { { "", "Client default" }, { "dark", "Dark" }, { "light", "Light" } }) {
            dx += button(o[1], dx, y, 13, can, o[0].equals(curTheme), () -> ClientEdit.send("a", "display.set", "key", "theme", "value", o[0])) + 3;
        }
        dx += 6;
        button(labels ? "Labels above panels: on" : "Labels above panels: off", dx, y, 13, can, labels, () -> ClientEdit.send("a", "display.set", "key", "showLabels", "value", labels ? "false" : "true"));
        y += 18;
        // lock
        y = sectionLabel("SAFETY", x0, y);
        button(ClientEdit.locked() ? "Locked: unlock with /agentcraft edit unlock" : "Lock all editing now", x0, y, 15, !ClientEdit.locked(), false, () -> ClientEdit.send("a", "lock", "reason", "editor button"));
        y += 20;
        Widgets.unclip();
        left.setContent(y + left.offset - left.y0);
        left.drawBar(th);
        result(lx1 + 10, y0, x1, y1);
    }

    /** Right column: the latest result (diff / dry run / undo), its Apply button, recent audit lines. */
    private void result(float x0, float y0, float x1, float y1) {
        Map<String, Object> r = ClientEdit.result;
        float auditH = 64;
        card(x0, y0, x1, y1 - auditH - 4);
        float y = y0 + 4;
        if (r == null) {
            wrapped("Results show here: what a snapshot diff, preset or import would change (+ place, - remove, ~ change), what is skipped and why, and the box it stays inside.", x0 + 6, y, x1 - x0 - 12, Widgets.BODY, th.muted, 6);
        } else {
            boolean ok = Json.bool(r, "ok", false);
            y = wrapped(Json.str(r, "msg", ""), x0 + 6, y, x1 - x0 - 12, Widgets.BODY, ok ? th.text : th.danger, 3) + 3;
            final Map<String, Object> plan = Json.obj(r, "plan");
            if (plan != null) {
                UiFont.regular()
                    .drawFit("box " + Json.str(plan, "bbox", ""), x0 + 6, y, Widgets.SMALL, x1 - x0 - 12, 0xFF000000 | th.muted);
                y += 11;
                final String token = Json.str(plan, "token", "");
                int nch = Json.integer(plan, "changes", 0);
                float bx = x0 + 6;
                String kind = Json.str(plan, "kind", "");
                bx += button(nch == 0 ? "Nothing to apply" : ("restore".equals(kind) ? "Restore: apply " : "Apply ") + nch + " change(s)", bx, y, 15, nch > 0 && !ClientEdit.locked(), true, () -> {
                    ClientEdit.send("a", "plan.apply", "token", token);
                    ClientEdit.result = null;
                }) + 4;
                button("Discard", bx, y, 15, true, false, () -> ClientEdit.result = null);
                y += 19;
            }
            List<Object> raw = Json.arr(r, "lines");
            // skipped cells first (what an import or preset will NOT touch is what a player must
            // see), then every change; long lines wrap (up to 3 rows) instead of being cut
            List<String> ordered = new ArrayList<>();
            if (raw != null) {
                for (Object o : raw) if (String.valueOf(o)
                    .startsWith("skip")) ordered.add(String.valueOf(o));
                for (Object o : raw) if (!String.valueOf(o)
                    .startsWith("skip")) ordered.add(String.valueOf(o));
            }
            right.bounds(x0 + 4, y, x1 - 4, y1 - auditH - 8);
            float lh = UiFont.mono()
                .lineHeight(Widgets.SMALL) + 1;
            float tw = right.x1 - right.x0 - 8;
            List<String[]> rows = new ArrayList<>(); // {text, line it belongs to}
            for (String s : ordered) {
                List<String> w = TextLayout.wrap(
                    s,
                    tw,
                    3,
                    UiFont.mono()
                        .measure(Widgets.SMALL));
                for (int k = 0; k < w.size(); k++) rows.add(new String[] { (k > 0 ? "  " : "") + w.get(k), s });
            }
            int n = rows.size();
            right.setContent(n * lh);
            Widgets.clip(this, right.x0, right.y0, right.x1, right.y1);
            for (int i = 0; i < n; i++) {
                float ly = right.y0 + i * lh - right.offset;
                if (ly + lh < right.y0 || ly > right.y1) continue;
                String s = rows.get(i)[1];
                int col = s.startsWith("+") ? 0x8FA98B : s.startsWith("-") ? th.danger : s.startsWith("~") ? 0xC9A227 : s.startsWith("skip") ? 0xE8C547 : th.text;
                UiFont.mono()
                    .drawFit(rows.get(i)[0], right.x0 + 2, ly, Widgets.SMALL, tw, 0xFF000000 | Theme.readable(col, th.surface));
            }
            Widgets.unclip();
            right.drawBar(th);
        }
        // recent audit
        float ay = y1 - auditH;
        card(x0, ay, x1, y1);
        UiFont.bold()
            .draw("AUDIT LOG (newest first)", x0 + 6, ay + 3, Widgets.SMALL, 0xFF000000 | th.muted);
        List<String> rec = ClientEdit.strings("recent");
        float lh = UiFont.mono()
            .lineHeight(Widgets.SMALL - 0.8F);
        for (int i = 0; i < rec.size() && ay + 13 + (i + 1) * lh < y1; i++) {
            String s = rec.get(i);
            int t = s.indexOf("who=");
            UiFont.mono()
                .drawFit(t > 0 ? s.substring(11, 19) + " " + s.substring(t) : s, x0 + 6, ay + 12 + i * lh, Widgets.SMALL - 0.8F, x1 - x0 - 12, 0xFF000000 | th.text);
        }
    }

    @Override
    protected String hint() {
        return tabs.selected == LAYOUTS ? "Presets and imports never touch non-AgentCraft blocks: those cells are listed as skipped." : super.hint();
    }

    @Override
    protected void onEnter(Widgets.SearchField f) {
        if (f == snapName && !snapName.text.trim()
            .isEmpty()) ClientEdit.send(
                "a",
                "snap.save",
                "name",
                snapName.text.trim()
                    .toLowerCase(Locale.ROOT));
    }
}
