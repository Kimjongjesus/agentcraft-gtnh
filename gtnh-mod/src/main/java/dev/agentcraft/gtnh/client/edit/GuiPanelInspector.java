package dev.agentcraft.gtnh.client.edit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import net.minecraft.tileentity.TileEntity;

import dev.agentcraft.gtnh.block.TileAgentCraft;
import dev.agentcraft.gtnh.client.BoardView;
import dev.agentcraft.gtnh.edit.PanelSpec;
import dev.agentcraft.gtnh.edit.PanelType;
import dev.agentcraft.gtnh.edit.PanelTypes;
import dev.agentcraft.gtnh.server.EditService;
import dev.agentcraft.gtnh.state.AgentInfo;
import dev.agentcraft.gtnh.state.ClientAgentCache;
import dev.agentcraft.gtnh.state.ClientHq;
import dev.agentcraft.gtnh.state.HqData;
import dev.agentcraft.gtnh.ui.Theme;
import dev.agentcraft.gtnh.ui.Ui;
import dev.agentcraft.gtnh.ui.UiFont;
import dev.agentcraft.gtnh.ui.Widgets;

/**
 * Card 6 Panel Inspector (edit tool, right-click a panel): rebind from a searchable list of the
 * agents / boards the client knows (no typing ids), resize with a live preview (the panel in the
 * world grows with it while this screen is open), rename, per-panel theme, duplicate, move,
 * delete. Apply / Duplicate / Delete are requests; the server re-checks and records them (undo).
 */
public class GuiPanelInspector extends EditGui {

    private final int x, y, z;
    private PanelType type;
    private String binding = "", theme = "", origBinding = "", origLabel = "", origTheme = "";
    private int w = 1, h = 1, origW = 1, origH = 1;
    private boolean armedDelete;
    private final Widgets.SearchField search = field("Search agents and boards\u2026", 40);
    private final Widgets.SearchField label = field("Label (optional: shown in edit mode, and above the panel when labels are on)", 32);
    private final Widgets.ScrollList list = scroll();

    public GuiPanelInspector(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    private TileAgentCraft tile() {
        TileEntity te = mc.theWorld == null ? null : mc.theWorld.getTileEntity(x, y, z);
        return te instanceof TileAgentCraft ? (TileAgentCraft) te : null;
    }

    @Override
    public void initGui() {
        super.initGui();
        TileAgentCraft t = tile();
        if (t != null && type == null) {
            type = PanelTypes.get(EditService.typeOf(mc.theWorld.getBlock(x, y, z)));
            binding = origBinding = t.binding;
            w = origW = t.screenW;
            h = origH = t.screenH;
            label.text = origLabel = t.label;
            theme = origTheme = t.theme;
            if (type != null && !type.resizable) w = h = origW = origH = 1;
        }
        updatePreview();
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        ClientEdit.previewPos = null;
    }

    private void updatePreview() {
        ClientEdit.previewW = w;
        ClientEdit.previewH = h;
        ClientEdit.previewBinding = binding;
        ClientEdit.previewPos = new int[] { x, y, z };
    }

    /** {id, label, colour} choices the panel kind accepts. */
    private List<Object[]> choices() {
        List<Object[]> out = new ArrayList<>();
        if (type == null) return out;
        String src = type.source;
        if (PanelType.BOARD.equals(src)) {
            out.add(new Object[] { "all", "All boards", 0xC9A227 });
            Set<String> boards = new TreeSet<>();
            for (HqData.Goal g : ClientHq.goals) if (!g.board.isEmpty()) boards.add(g.board);
            for (HqData.Task t : ClientHq.tasks) if (t.board != null && !t.board.isEmpty()) boards.add(t.board);
            for (String b : boards) out.add(new Object[] { b, "Board " + b, 0x2FA3A0 });
        }
        if (PanelType.LAMP.equals(src)) out.add(new Object[] { "fleet", "The whole fleet", 0xC9A227 });
        if (PanelType.AGENT.equals(src) || PanelType.LAMP.equals(src)) {
            List<AgentInfo> as = new ArrayList<>(ClientAgentCache.all());
            Collections.sort(as, (a, b) -> a.name.compareToIgnoreCase(b.name));
            for (AgentInfo a : as) out.add(new Object[] { a.id, a.name.isEmpty() ? a.id : a.name, a.color });
        }
        if (!PanelType.NONE.equals(src)) out.add(new Object[] { "", "Unbound", 0x9C9488 });
        String q = search.text.trim()
            .toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return out;
        List<Object[]> f = new ArrayList<>();
        for (Object[] c : out) if (((String) c[0]).toLowerCase(Locale.ROOT)
            .contains(q) || ((String) c[1]).toLowerCase(Locale.ROOT)
                .contains(q)) f.add(c);
        return f;
    }

    private boolean dirty() {
        return !binding.equals(origBinding) || w != origW || h != origH || !label.text.trim()
            .equals(origLabel) || !theme.equals(origTheme);
    }

    @Override
    protected void draw(float partial) {
        // translucent on the right so the panel in the world (live preview) stays visible
        drawRect(0, 0, width, height, 0xC0000000 | (th.bg & 0xFFFFFF));
        TileAgentCraft t = tile();
        if (t == null || type == null) {
            title("Panel Inspector", "no AgentCraft panel here any more");
            statusStrip();
            return;
        }
        String facing = type.faced ? PanelSpec.facingOfMeta(mc.theWorld.getBlockMetadata(x, y, z)) : "";
        float top = title("Panel Inspector", type.name + "  \u00b7  " + x + " " + y + " " + z + (facing.isEmpty() ? "" : "  \u00b7  facing " + facing));
        float pad = 8, gap = 10;
        float leftW = Math.max(150, Math.min(width * 0.36F, 230));
        float lx0 = pad, lx1 = pad + leftW;
        float rx0 = lx1 + gap, rx1 = width - pad;
        float bottom = height - 40;

        // ---- left: binding -----------------------------------------------------------------
        float yy = sectionLabel(PanelType.NONE.equals(type.source) ? "BINDING" : "BOUND TO  \u00b7  " + PanelPreview.bindsTo(type), lx0, top + 2);
        if (PanelType.NONE.equals(type.source)) {
            wrapped(type.name + " needs no binding: it always shows the whole fleet.", lx0, yy + 2, leftW, Widgets.BODY, th.text, 4);
        } else {
            search.at(lx0, yy, leftW, 14);
            search.draw(th);
            float ly0 = yy + 18;
            list.bounds(lx0, ly0, lx1, bottom);
            List<Object[]> cs = choices();
            float rowH = 15;
            list.setContent(cs.size() * (rowH + 2));
            Widgets.clip(this, list.x0, list.y0, list.x1, list.y1);
            float ry = ly0 - list.offset;
            for (Object[] c : cs) {
                final String id = (String) c[0];
                boolean sel = id.equals(binding);
                float r0 = ry, r1 = ry + rowH;
                ry += rowH + 2;
                if (r1 < list.y0 || r0 > list.y1) continue;
                boolean hv = hover(lx0, Math.max(r0, list.y0), lx1 - 4, Math.min(r1, list.y1));
                Ui.round(lx0, r0, lx1 - 4, r1, 3, 0xFF000000 | (sel ? th.cardSelected : hv ? th.raised : th.surface));
                if (sel) Ui.roundOutline(lx0, r0, lx1 - 4, r1, 3, 1.0, 0xFF000000 | th.accent);
                Ui.dot(lx0 + 7, r0 + rowH / 2, 3, 0xFF000000 | (Integer) c[2]);
                int fg = sel ? th.cardText : th.text;
                float nw = UiFont.bold()
                    .drawFit((String) c[1], lx0 + 14, r0 + 3, Widgets.BODY, leftW - 90, 0xFF000000 | fg);
                if (!id.isEmpty() && !id.equals(c[1])) UiFont.mono()
                    .drawFit(id, lx0 + 18 + nw, r0 + 4, Widgets.SMALL, leftW - 30 - nw - 18, 0xFF000000 | (sel ? th.cardMuted : th.muted));
                if (id.equals(origBinding)) UiFont.regular()
                    .drawRight("current", lx1 - 8, r0 + 4, Widgets.SMALL, 0xFF000000 | (sel ? th.cardMuted : th.muted));
                if (r1 > list.y0 && r0 < list.y1) region(lx0, Math.max(r0, list.y0), lx1 - 4, Math.min(r1, list.y1), () -> {
                    binding = id;
                    armedDelete = false;
                    updatePreview();
                });
            }
            Widgets.unclip();
            list.drawBar(th);
            if (cs.isEmpty()) UiFont.regular()
                .draw("Nothing matches \u201c" + search.text + "\u201d", lx0 + 4, ly0 + 4, Widgets.BODY, 0xFF000000 | th.muted);
        }

        // ---- right: preview, size, label, theme --------------------------------------------
        float py = sectionLabel("LIVE PREVIEW  \u00b7  the panel in the world follows while this is open", rx0, top + 2);
        float prevH = Math.max(60, Math.min((bottom - py) * 0.52F, 150));
        card(rx0, py, rx1, py + prevH);
        PanelPreview.draw(type, binding, theme.isEmpty() ? "dark" : theme, w, h, rx0 + 6, py + 6, rx1 - 6, py + prevH - 6);
        float cy = py + prevH + 8;
        if (type.resizable) {
            cy = sectionLabel("SIZE (BLOCKS)", rx0, cy);
            float bx = rx0;
            UiFont.heavy()
                .draw(w + " \u00d7 " + h, bx, cy, Widgets.TITLE, 0xFF000000 | th.text);
            bx += 46;
            bx += button("width \u2212", bx, cy - 1, 13, w > 1, false, () -> { w--; updatePreview(); }) + 3;
            bx += button("width +", bx, cy - 1, 13, w < 8, false, () -> { w++; updatePreview(); }) + 8;
            bx += button("height \u2212", bx, cy - 1, 13, h > 1, false, () -> { h--; updatePreview(); }) + 3;
            button("height +", bx, cy - 1, 13, h < 6, false, () -> { h++; updatePreview(); });
            cy += 18;
        } else {
            cy = sectionLabel("SIZE", rx0, cy);
            UiFont.regular()
                .draw("one block (this kind has no screen to resize)", rx0, cy, Widgets.BODY, 0xFF000000 | th.muted);
            cy += 14;
        }
        cy = sectionLabel("LABEL", rx0, cy);
        label.at(rx0, cy, Math.min(rx1 - rx0, 220), 14);
        label.draw(th);
        cy += 20;
        cy = sectionLabel("THEME", rx0, cy);
        float tx = rx0;
        for (final String[] o : new String[][] { { "", "Layout default" }, { "dark", "Dark" }, { "light", "Light" } }) {
            boolean sel = o[0].equals(theme);
            tx += button(o[1], tx, cy, 13, true, sel, () -> {
                theme = o[0];
                updatePreview();
            }) + 3;
        }

        // ---- bottom buttons ----------------------------------------------------------------
        float by = height - 34;
        float bx = pad;
        final String lbl = label.text.trim();
        bx += button(dirty() ? "Apply changes" : "No changes", bx, by, 16, dirty() && !ClientEdit.locked(), true, () -> {
            ClientEdit.send("a", "panel.set", "pos", x + "," + y + "," + z, "binding", binding, "w", (double) w, "h", (double) h, "label", lbl, "theme", theme);
            origBinding = binding;
            origW = w;
            origH = h;
            origLabel = lbl;
            origTheme = theme;
        }) + 6;
        bx += button("Duplicate beside it", bx, by, 16, !ClientEdit.locked(), false, () -> ClientEdit.send("a", "panel.duplicate", "pos", x + "," + y + "," + z)) + 6;
        bx += button("Move\u2026", bx, by, 16, !ClientEdit.locked(), false, () -> {
            ClientEdit.moving = new int[] { x, y, z };
            ClientEdit.movingName = type.name;
            mc.displayGuiScreen(null);
        }) + 6;
        bx += button(armedDelete ? "Click again to delete" : "Delete", bx, by, 16, !ClientEdit.locked(), armedDelete, () -> {
            if (!armedDelete) {
                armedDelete = true;
                return;
            }
            ClientEdit.send("a", "panel.delete", "pos", x + "," + y + "," + z);
            mc.displayGuiScreen(null);
        }) + 6;
        button("Close", width - pad - 50, by, 16, true, false, () -> mc.displayGuiScreen(null));
        String bindText = binding.isEmpty() ? "unbound" : BoardView.agentName(binding);
        if (PanelType.BOARD.equals(type.source)) bindText = BoardView.scopeLabel(binding);
        UiFont.regular()
            .drawFit("Now: " + (origBinding.isEmpty() ? "unbound" : origBinding) + ", new: " + bindText + (dirty() ? "  (not applied yet)" : ""), bx + 4, by + 4, Widgets.SMALL, width - bx - 70, 0xFF000000 | th.muted);
        statusStrip();
    }

    @Override
    protected String hint() {
        return "Pick a binding, size, label or theme, then Apply. Undo: Layouts tab or /agentcraft edit undo.";
    }

    @Override
    protected void onFieldChanged(Widgets.SearchField f) {
        if (f == search) list.offset = 0;
    }

    @Override
    protected void onEnter(Widgets.SearchField f) {
        if (f == search) {
            List<Object[]> cs = choices();
            if (!cs.isEmpty()) {
                binding = (String) cs.get(0)[0];
                updatePreview();
            }
        }
    }

    /** Dev/QA screenshots: preselect "binding:w:h" (any part may be empty) without applying it. */
    public void devPreset(String spec) {
        String[] p = spec.split(":", -1);
        if (p.length > 0 && !p[0].isEmpty()) binding = "-".equals(p[0]) ? "" : p[0];
        if (p.length > 2 && !p[1].isEmpty() && !p[2].isEmpty()) {
            w = Math.max(1, Math.min(8, Integer.parseInt(p[1])));
            h = Math.max(1, Math.min(6, Integer.parseInt(p[2])));
        }
        if (p.length > 3) label.text = p[3].replace('_', ' ');
        updatePreview();
    }

    /** Theme helper so colours of the choice list stay readable on both themes. */
    static int readable(int c, Theme th) {
        return Theme.readable(c, th.surface);
    }
}
