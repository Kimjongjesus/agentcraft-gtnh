package dev.agentcraft.gtnh.client;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import dev.agentcraft.gtnh.ops.DecisionData;
import dev.agentcraft.gtnh.ops.OpsData;
import dev.agentcraft.gtnh.state.ClientOps;
import dev.agentcraft.gtnh.ui.TextLayout;
import dev.agentcraft.gtnh.ui.Theme;
import dev.agentcraft.gtnh.ui.Ui;
import dev.agentcraft.gtnh.ui.UiFont;
import dev.agentcraft.gtnh.ui.Widgets;

/**
 * Card 5b: read-only decision screen (from the toast, its key, or /agentcraft toast). Lists the open
 * decisions and approvals newest first; the selected one shows its question, the options the agent
 * offered (display only), agent, card, context and age. Nothing on this screen answers anything:
 * answering from the game is a later card. Local GUI state only; nothing is sent to the server.
 */
public class GuiDecisions extends GuiScreen {

    private final Widgets.ScrollList list = new Widgets.ScrollList(), reader = new Widgets.ScrollList();
    private final Theme th = Theme.DARK;
    private String selectedId;
    private final List<Object[]> rows = new ArrayList<>();
    private final SimpleDateFormat when = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    public GuiDecisions(String selectedId) {
        this.selectedId = selectedId;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void layout() {
        float pad = 8;
        float top = pad + UiFont.heavy()
            .lineHeight(Widgets.H1) + 8;
        float split = Math.max(170, Math.min(width * 0.42F, 280));
        list.bounds(pad, top + 10, split, height - pad - 12);
        reader.bounds(split + 8, top, width - pad, height - pad - 12 - footerH());
    }

    /** Add-on footer strip under the reader (0 without an add-on: the screen stays read-only). */
    private static float footerH() {
        dev.agentcraft.gtnh.api.Extensions.ClientHooks h = dev.agentcraft.gtnh.api.Extensions.client;
        return h == null ? 0 : Math.max(0, Math.min(h.footerHeight("decisions"), 60));
    }

    private DecisionData.Decision selectedDecision() {
        for (DecisionData.Decision d : ClientOps.decisions) if (d.id.equals(selectedId)) return d;
        return null;
    }

    private List<DecisionData.Decision> newestFirst() {
        List<DecisionData.Decision> out = new ArrayList<>(ClientOps.decisions);
        out.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return out;
    }

    @Override
    public void drawScreen(int mx, int my, float partial) {
        drawRect(0, 0, width, height, 0xE8000000 | (th.bg & 0xFFFFFF));
        layout();
        UiFont reg = UiFont.regular(), bold = UiFont.bold(), heavy = UiFont.heavy();
        float pad = 8;
        List<DecisionData.Decision> ds = newestFirst();
        float tw = heavy.draw("Waiting on you", pad, pad, Widgets.H1, 0xFF000000 | th.text);
        dev.agentcraft.gtnh.api.Extensions.ClientHooks hooks = dev.agentcraft.gtnh.api.Extensions.client;
        String hookNote = hooks == null ? null : hooks.statusLine("decisions");
        reg.draw(ds.size() + (ds.size() == 1 ? " open decision" : " open decisions") + "  \u00b7  " + (hookNote != null ? hookNote : "read-only: answer outside the game for now"), pad + tw + 8, pad + 3,
            Widgets.BODY, 0xFF000000 | th.muted);
        if (!ClientOps.decisionsLive) Ui.pillRight(reg, "adapter offline", width - pad, pad + 1, Widgets.SMALL, th.danger);
        reg.draw(hookNote != null ? "Esc closes." : "Answering from the game comes in a later card. Esc closes.", pad, height - pad - reg.lineHeight(Widgets.SMALL), Widgets.SMALL,
            0xFF000000 | th.muted);
        DecisionData.Decision sel = null;
        for (DecisionData.Decision d : ds) if (d.id.equals(selectedId)) sel = d;
        if (sel == null && !ds.isEmpty()) {
            sel = ds.get(0);
            selectedId = sel.id;
            reader.offset = 0;
        }
        rows.clear();
        float cw = list.x1 - list.x0 - 6, y = 0;
        for (DecisionData.Decision d : ds) {
            int lines = Math.max(1, TextLayout.wrap(d.question, cw - 12, 2, bold.measure(Widgets.BODY))
                .size());
            float h = 5 + reg.lineHeight(Widgets.SMALL) + lines * bold.lineHeight(Widgets.BODY) + reg.lineHeight(Widgets.SMALL) + 4;
            rows.add(new Object[] { d, y, y + h });
            y += h + 3;
        }
        list.setContent(y);
        Widgets.clip(this, list.x0, list.y0, list.x1, list.y1);
        long now = System.currentTimeMillis();
        for (Object[] r : rows) {
            DecisionData.Decision d = (DecisionData.Decision) r[0];
            float y0 = list.y0 + (Float) r[1] - list.offset, y1 = list.y0 + (Float) r[2] - list.offset;
            if (y1 < list.y0 || y0 > list.y1) continue;
            boolean h = Widgets.inside(mx, my, list.x0, Math.max(y0, list.y0), list.x0 + cw, Math.min(y1, list.y1));
            boolean isSel = d == sel;
            int bg = isSel ? th.cardSelected : h ? Theme.mix(th.card, 0xFFFFFF, 0.5F) : th.card;
            float x0 = list.x0, x1 = list.x0 + cw;
            Ui.round(x0, y0, x1, y1, 3, 0xFF000000 | bg);
            if (isSel) Ui.roundOutline(x0, y0, x1, y1, 3, 1.2, 0xFF000000 | th.accent);
            Ui.round(x0, y0, x0 + 6, y1, 3, 0xFF000000 | 0xD97757);
            Ui.rect(x0 + 3, y0, x0 + 6, y1, 0xFF000000 | bg);
            float tx = x0 + 8, ty = y0 + 4;
            reg.draw(d.label()
                .toUpperCase(java.util.Locale.ROOT), tx, ty, Widgets.SMALL, 0xFF000000 | th.cardMuted);
            reg.drawRight(OpsData.age(d.createdAt, now), x1 - 4, ty, Widgets.SMALL, 0xFF000000 | th.cardMuted);
            ty += reg.lineHeight(Widgets.SMALL);
            for (String l : TextLayout.wrap(d.question, x1 - tx - 4, 2, bold.measure(Widgets.BODY))) {
                bold.draw(l, tx, ty, Widgets.BODY, 0xFF000000 | th.cardText);
                ty += bold.lineHeight(Widgets.BODY);
            }
            bold.drawFit(d.agentName + (d.taskId.isEmpty() ? "" : "  \u00b7  " + d.taskId), tx, ty, Widgets.SMALL, x1 - tx - 4,
                0xFF000000 | Theme.readable(d.agentColor, bg));
        }
        Widgets.unclip();
        list.drawBar(th);
        if (ds.isEmpty()) reg.draw(ClientOps.decisionsLive ? "Nothing is waiting on you." : "No decision data (adapter offline).", list.x0 + 4, list.y0 + 4, Widgets.BODY,
            0xFF000000 | th.muted);
        Ui.round(reader.x0, reader.y0, reader.x1, reader.y1, 4, 0xFF000000 | th.surface);
        if (sel != null) drawReader(sel, now);
        float fh = footerH();
        if (fh > 0) {
            dev.agentcraft.gtnh.api.Extensions.client.drawFooter("decisions", sel, reader.x0, reader.y1 + 2, reader.x1, reader.y1 + fh, mx, my);
        }
    }

    private void drawReader(DecisionData.Decision d, long now) {
        UiFont reg = UiFont.regular(), bold = UiFont.bold();
        float pad = 8, x = reader.x0 + pad, w = reader.x1 - reader.x0 - 2 * pad - 4;
        List<Object[]> lines = new ArrayList<>(); // [font, size, colour, text]
        lines.add(new Object[] { bold, Widgets.SMALL, Theme.readable(0xD97757, th.surface), d.label()
            .toUpperCase(java.util.Locale.ROOT) + " NEEDED" });
        for (String l : TextLayout.wrap(d.question, w, 0, bold.measure(Widgets.TITLE))) lines.add(new Object[] { bold, Widgets.TITLE, th.text, l });
        lines.add(new Object[] { reg, 5F, 0, "" });
        if (!d.options.isEmpty()) {
            lines.add(new Object[] { bold, Widgets.BODY, th.muted, "Options the agent offered (display only):" });
            for (String o : d.options) lines.add(new Object[] { reg, Widgets.BODY, th.text, "  \u2022 " + o });
            lines.add(new Object[] { reg, 5F, 0, "" });
        }
        String[][] facts = { { "Agent", d.agentName + (d.agentId.isEmpty() || d.agentId.equals(d.agentName) ? "" : " (" + d.agentId + ")") },
            { "Card", d.taskId.isEmpty() ? "-" : d.taskId }, { "Kind", d.kind },
            { "Opened", d.createdAt > 0 ? when.format(new Date(d.createdAt)) + "  (" + OpsData.age(d.createdAt, now) + ")" : "-" } };
        for (String[] f : facts) {
            for (String l : TextLayout.wrap(f[0] + ": " + f[1], w, 0, reg.measure(Widgets.BODY))) lines.add(new Object[] { reg, Widgets.BODY, th.text, l });
        }
        if (!d.context.isEmpty()) {
            lines.add(new Object[] { reg, 5F, 0, "" });
            lines.add(new Object[] { bold, Widgets.BODY, th.muted, "Context" });
            for (String para : d.context.split("\n")) {
                for (String l : TextLayout.wrap(para, w, 0, reg.measure(Widgets.BODY))) lines.add(new Object[] { reg, Widgets.BODY, th.text, l });
            }
        }
        float total = 0;
        for (Object[] l : lines) total += ((UiFont) l[0]).lineHeight((Float) l[1]);
        reader.setContent(total + 2 * pad);
        Widgets.clip(this, reader.x0, reader.y0, reader.x1, reader.y1);
        float y = reader.y0 + pad - reader.offset;
        for (Object[] l : lines) {
            float h = ((UiFont) l[0]).lineHeight((Float) l[1]);
            if (y + h >= reader.y0 && y <= reader.y1) ((UiFont) l[0]).draw((String) l[3], x, y, (Float) l[1], 0xFF000000 | (Integer) l[2]);
            y += h;
        }
        Widgets.unclip();
        reader.drawBar(th);
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d == 0) return;
        int mx = Mouse.getEventX() * width / mc.displayWidth;
        if (mx >= reader.x0) reader.wheel(d, 18);
        else list.wheel(d, 18);
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) {
        super.mouseClicked(mx, my, button);
        float fh = footerH();
        if (fh > 0 && mx >= reader.x0 && mx <= reader.x1 && my >= reader.y1 + 2 && my <= reader.y1 + fh) {
            if (dev.agentcraft.gtnh.api.Extensions.client.footerClick("decisions", selectedDecision(), reader.x0, reader.y1 + 2, reader.x1, reader.y1 + fh, mx, my, button)) return;
        }
        if (!list.contains(mx, my)) return;
        for (Object[] r : rows) {
            float y0 = list.y0 + (Float) r[1] - list.offset, y1 = list.y0 + (Float) r[2] - list.offset;
            if (my >= y0 && my < y1) {
                selectedId = ((DecisionData.Decision) r[0]).id;
                reader.offset = 0;
                return;
            }
        }
    }

    @Override
    protected void keyTyped(char ch, int key) {
        if (key == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
            return;
        }
        if (key == Keyboard.KEY_DOWN) reader.wheel(-1, 10);
        if (key == Keyboard.KEY_UP) reader.wheel(1, 10);
    }
}
