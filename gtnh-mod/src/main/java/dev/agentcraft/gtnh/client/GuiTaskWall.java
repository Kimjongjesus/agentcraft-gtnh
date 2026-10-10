package dev.agentcraft.gtnh.client;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import dev.agentcraft.gtnh.state.ClientAgentCache;
import dev.agentcraft.gtnh.state.ClientHq;
import dev.agentcraft.gtnh.state.HqData;
import dev.agentcraft.gtnh.ui.TextLayout;
import dev.agentcraft.gtnh.ui.Theme;
import dev.agentcraft.gtnh.ui.Ui;
import dev.agentcraft.gtnh.ui.UiFont;
import dev.agentcraft.gtnh.ui.Widgets;

/**
 * Read-only task wall screen (right-click a Task Wall or Goal Atrium), on the card-4 toolkit:
 * column tabs with the same counts as the wall (Done labelled with its window), a scrolling card
 * list (titles wrapped by pixel width, two lines, full title in the tooltip) and the selected card's
 * detail with the FULL title and an explanation of what each count means. Nothing here sends anything
 * to the server; tab, scroll and selection are local state.
 */
public class GuiTaskWall extends GuiScreen {

    private static final int ALL = 5;
    private static final int[] TAB_COL = { -1, 0, 1, 2, 3, 4 };

    private final String binding;
    private String selectedId;
    private final Widgets.Tabs tabs = new Widgets.Tabs("All", "To do", "Doing", "Review", "Done", "Blocked");
    private final Widgets.ScrollList list = new Widgets.ScrollList(), detail = new Widgets.ScrollList();
    private final SimpleDateFormat when = new SimpleDateFormat("MMM d HH:mm");
    private final Theme th = Theme.DARK;
    private float listTop, split, bottom;
    /** rows of the current frame: [task or null for a section header, y0, y1] */
    private final List<Object[]> rows = new ArrayList<>();

    public GuiTaskWall(String binding, String selectId) {
        this.binding = binding == null ? "" : binding;
        this.selectedId = selectId;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        BoardView v = BoardView.of(binding);
        if (selectedId == null) {
            for (int c : new int[] { 1, 4, 2, 0, 3 }) {
                if (!v.columns.get(c)
                    .isEmpty()) {
                    selectedId = v.columns.get(c)
                        .get(0).id;
                    break;
                }
            }
        } else {
            HqData.Task t = ClientHq.task(selectedId);
            if (t != null && HqData.column(t.status) >= 0) tabs.selected = 0;
        }
        layout();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    private void layout() {
        BoardView v = BoardView.of(binding);
        for (int i = 0; i < tabs.tabs.size(); i++) {
            int col = TAB_COL[i];
            String base = i == 0 ? "All" : Theme.COLUMN_NAMES[col];
            int n = i == 0 ? v.openCount() + v.count(3) : v.count(col);
            tabs.tabs.get(i).label = base + "  " + n + (col == 3 ? " \u00b7 " + v.windowShort() : "");
        }
        float pad = 8;
        float y = pad + UiFont.bold()
            .lineHeight(Widgets.H1) + 4;
        float tabsBottom = tabs.layout(pad, y, width - 2 * pad, 13);
        listTop = tabsBottom + 6;
        bottom = height - pad;
        split = Math.max(170, Math.min(width * 0.46F, 300));
        list.bounds(pad, listTop, split, bottom);
        detail.bounds(split + 8, listTop, width - pad, bottom - footerH());
    }

    /** Add-on footer strip under the detail pane (0 without an add-on: the screen stays read-only). */
    private static float footerH() {
        dev.agentcraft.gtnh.api.Extensions.ClientHooks h = dev.agentcraft.gtnh.api.Extensions.client;
        return h == null ? 0 : Math.max(0, Math.min(h.footerHeight("taskwall"), 60));
    }

    private List<HqData.Task> visible(BoardView v) {
        List<HqData.Task> out = new ArrayList<>();
        int col = TAB_COL[tabs.selected];
        if (col >= 0) return v.columns.get(col);
        for (int c : new int[] { 1, 4, 2, 0, 3 }) out.addAll(v.columns.get(c));
        return out;
    }

    @Override
    public void drawScreen(int mx, int my, float partial) {
        drawRect(0, 0, width, height, 0xE8000000 | (th.bg & 0xFFFFFF));
        BoardView v = BoardView.of(binding);
        layout();
        UiFont reg = UiFont.regular(), bold = UiFont.bold(), heavy = UiFont.heavy();
        float pad = 8;
        // title row
        float tw = heavy.draw("Task wall", pad, pad, Widgets.H1, 0xFF000000 | th.text);
        dev.agentcraft.gtnh.api.Extensions.ClientHooks hooks = dev.agentcraft.gtnh.api.Extensions.client;
        String hookNote = hooks == null ? null : hooks.statusLine("taskwall");
        reg.draw(BoardView.scopeLabel(binding) + "  \u00b7  " + (hookNote != null ? hookNote : "read-only"), pad + tw + 8, pad + 3, Widgets.BODY, 0xFF000000 | th.muted);
        float right = width - pad;
        if (!ClientAgentCache.linkUp) {
            right -= Ui.pillRight(reg, "adapter offline \u00b7 last data", right, pad + 1, Widgets.SMALL, th.danger) + 4;
        } else if (v.goal.openDecisions > 0) {
            right -= Ui.pillRight(bold, v.goal.openDecisions + " need you", right, pad + 1, Widgets.SMALL + 0.6F, th.accent) + 4;
        }
        String sum = v.openCount() + " open  \u00b7  " + v.doneAllTime() + " / " + v.goal.total + " done all time (" + Math.round(v.goal.progress * 100) + "%)";
        reg.drawRight(sum, right, pad + 3, Widgets.SMALL + 0.4F, 0xFF000000 | th.muted);
        tabs.draw(th, mx, my);

        // ---- list --------------------------------------------------------------------------
        List<HqData.Task> cards = visible(v);
        rows.clear();
        float y = 0, cw = list.x1 - list.x0 - 6;
        int lastCol = -2;
        boolean all = TAB_COL[tabs.selected] < 0;
        for (HqData.Task t : cards) {
            int col = HqData.column(t.status);
            if (all && col != lastCol) {
                rows.add(new Object[] { Integer.valueOf(col), y, y + 13 });
                y += 13;
                lastCol = col;
            }
            float h = cardHeight(t, cw);
            rows.add(new Object[] { t, y, y + h });
            y += h + 3;
        }
        if (TAB_COL[tabs.selected] == 3 || all) {
            rows.add(new Object[] { "window", y, y + 24 });
            y += 24;
        }
        list.setContent(y);
        Widgets.clip(this, list.x0, list.y0, list.x1, list.y1);
        HqData.Task hover = null;
        for (Object[] r : rows) {
            float y0 = list.y0 + (Float) r[1] - list.offset, y1 = list.y0 + (Float) r[2] - list.offset;
            if (y1 < list.y0 || y0 > list.y1) continue;
            if (r[0] instanceof Integer) {
                int col = (Integer) r[0];
                Ui.dot(list.x0 + 4, y0 + 6.5F, 2.6, 0xFF000000 | Theme.COLUMN[col]);
                String lab = Theme.COLUMN_NAMES[col] + "  " + v.count(col) + (col == 3 ? "  \u00b7  " + v.windowLabel() : "");
                bold.draw(lab, list.x0 + 10, y0 + 2.5F, Widgets.SMALL + 0.6F, 0xFF000000 | th.muted);
            } else if (r[0] instanceof String) {
                List<String> l = TextLayout.wrap(
                    "Done shows the " + v.windowLabel() + " (" + v.count(3) + "); " + v.doneAllTime() + " finished all time.",
                    cw,
                    2,
                    reg.measure(Widgets.SMALL));
                for (int i = 0; i < l.size(); i++) reg.draw(l.get(i), list.x0 + 2, y0 + 3 + i * reg.lineHeight(Widgets.SMALL), Widgets.SMALL, 0xFF000000 | th.muted);
            } else {
                HqData.Task t = (HqData.Task) r[0];
                boolean h = Widgets.inside(mx, my, list.x0, Math.max(y0, list.y0), list.x0 + cw, Math.min(y1, list.y1));
                if (h) hover = t;
                drawCard(t, list.x0, y0, list.x0 + cw, y1, t.id.equals(selectedId), h);
            }
        }
        Widgets.unclip();
        list.drawBar(th);
        if (cards.isEmpty()) reg.draw(ClientHq.haveBoard ? "No cards in this column." : "Waiting for the board\u2026", list.x0 + 4, list.y0 + 4, Widgets.BODY, 0xFF000000 | th.muted);

        drawDetail(v);
        float fh = footerH();
        if (fh > 0) {
            dev.agentcraft.gtnh.api.Extensions.client.drawFooter("taskwall", selectedId == null ? null : ClientHq.task(selectedId), detail.x0, detail.y1 + 2, detail.x1, detail.y1 + fh, mx, my);
        }
        if (hover != null) {
            List<String> tip = new ArrayList<>();
            tip.add(hover.title);
            tip.add((hover.assignee.isEmpty() ? "unassigned" : BoardView.agentName(hover.assignee)) + (hover.priority > 0 ? "  \u00b7  P" + hover.priority : ""));
            Widgets.tooltip(th, tip, mx, my, width, height);
        }
    }

    private float cardHeight(HqData.Task t, float w) {
        UiFont bold = UiFont.bold(), reg = UiFont.regular();
        int lines = Math.max(1, TextLayout.wrap(t.title, w - 12, 2, bold.measure(Widgets.BODY)).size());
        return 5 + lines * bold.lineHeight(Widgets.BODY) + reg.lineHeight(Widgets.SMALL) + 4;
    }

    private void drawCard(HqData.Task t, float x0, float y0, float x1, float y1, boolean sel, boolean hover) {
        UiFont bold = UiFont.bold(), reg = UiFont.regular();
        int col = HqData.column(t.status);
        int bg = sel ? th.cardSelected : hover ? Theme.mix(th.card, 0xFFFFFF, 0.5F) : th.card;
        Ui.round(x0, y0, x1, y1, 3, 0xFF000000 | bg);
        if (sel) Ui.roundOutline(x0, y0, x1, y1, 3, 1.2, 0xFF000000 | th.accent);
        int agent = t.assignee.isEmpty() ? 0x9C9488 : BoardView.agentColor(t.assignee);
        Ui.round(x0, y0, x0 + 6, y1, 3, 0xFF000000 | agent);
        Ui.rect(x0 + 3, y0, x0 + 6, y1, 0xFF000000 | bg);
        float tx = x0 + 8, tw = x1 - tx - 4;
        float y = y0 + 4;
        for (String l : TextLayout.wrap(t.title, tw, 2, bold.measure(Widgets.BODY))) {
            bold.draw(l, tx, y, Widgets.BODY, 0xFF000000 | th.cardText);
            y += bold.lineHeight(Widgets.BODY);
        }
        float my = y1 - reg.lineHeight(Widgets.SMALL) - 3;
        float right = x1 - 4;
        if (col >= 0 && TAB_COL[tabs.selected] < 0) {
            // column chip in the All tab
            Ui.dot(right - 2.5F, my + 4, 2.5, 0xFF000000 | Theme.COLUMN[col]);
            right -= 8;
        }
        if (t.priority > 0) right -= reg.drawRight("P" + t.priority, right, my, Widgets.SMALL, 0xFF000000 | (t.priority >= 80 ? Theme.readable(0xD97757, bg) : th.cardMuted)) + 4;
        if (!t.deps.isEmpty()) right -= reg.drawRight("\u2192" + t.deps.size(), right, my, Widgets.SMALL, 0xFF000000 | th.cardMuted) + 4;
        String who = t.assignee.isEmpty() ? "unassigned" : BoardView.agentName(t.assignee);
        (t.assignee.isEmpty() ? reg : bold).drawFit(who, tx, my, Widgets.SMALL, right - tx, 0xFF000000 | (t.assignee.isEmpty() ? th.cardMuted : Theme.readable(agent, bg)));
    }

    private void drawDetail(BoardView v) {
        UiFont reg = UiFont.regular(), bold = UiFont.bold(), heavy = UiFont.heavy(), mono = UiFont.mono();
        float x0 = detail.x0, x1 = detail.x1;
        Ui.round(x0, detail.y0, x1, detail.y1, 4, 0xFF000000 | th.surface);
        HqData.Task t = selectedId == null ? null : ClientHq.task(selectedId);
        float pad = 7, w = x1 - x0 - 2 * pad - 4;
        if (t == null) {
            reg.draw(ClientHq.haveBoard ? "Click a card for its details." : "Waiting for the board\u2026", x0 + pad, detail.y0 + pad, Widgets.BODY, 0xFF000000 | th.muted);
            return;
        }
        // build the detail as [font, size, colour, text] lines, then scroll them
        List<Object[]> lines = new ArrayList<>();
        for (String l : TextLayout.wrap(t.title, w, 0, bold.measure(Widgets.TITLE))) lines.add(new Object[] { bold, Widgets.TITLE, th.text, l });
        lines.add(new Object[] { mono, Widgets.SMALL, th.muted, t.id + (t.board.isEmpty() ? "" : "  \u00b7  board " + t.board) });
        lines.add(new Object[] { "pills", 0F, 0, "" });
        int col = HqData.column(t.status);
        String who = t.assignee.isEmpty() ? "unassigned" : BoardView.agentName(t.assignee) + "  (" + t.assignee + ")";
        addPara(lines, "Assignee", who, t.assignee.isEmpty() ? th.muted : Theme.readable(BoardView.agentColor(t.assignee), th.surface), w);
        addPara(lines, "Priority", t.priority + (t.updatedAt > 0 ? "   \u00b7   updated " + when.format(new Date(t.updatedAt)) : ""), th.text, w);
        if (!t.branch.isEmpty()) addPara(lines, "Branch", t.branch, th.text, w);
        if (!t.deps.isEmpty()) {
            lines.add(new Object[] { bold, Widgets.SMALL + 0.4F, th.muted, "Depends on" });
            for (String d : t.deps) {
                HqData.Task dt = ClientHq.task(d);
                for (String l : TextLayout.wrap(d + (dt == null ? "" : "  \u00b7  " + dt.status + ": " + dt.title), w - 6, 0, reg.measure(Widgets.SMALL + 0.6F)))
                    lines.add(new Object[] { reg, Widgets.SMALL + 0.6F, th.text, "  " + l });
            }
        }
        if (!t.blockedReason.isEmpty()) addPara(lines, "Blocked", t.blockedReason, th.accent, w);
        if (!t.description.isEmpty()) addPara(lines, "Description", t.description, th.text, w);
        if (!t.summary.isEmpty()) addPara(lines, "Summary", t.summary, Theme.readable(0x9CC59A, th.surface), w);
        lines.add(new Object[] { reg, Widgets.SMALL, 0, "" });
        addPara(lines, "About the counts", v.countsExplained(), th.muted, w);

        float total = 0;
        for (Object[] l : lines) total += lineH(l);
        detail.setContent(total + 2 * pad);
        Widgets.clip(this, x0, detail.y0, x1, detail.y1);
        float y = detail.y0 + pad - detail.offset;
        for (Object[] l : lines) {
            float h = lineH(l);
            if (y + h >= detail.y0 && y <= detail.y1) {
                if ("pills".equals(l[0])) {
                    float px = x0 + pad;
                    px += Ui.pill(bold, col < 0 ? t.status : Theme.COLUMN_NAMES[col], px, y + 1, Widgets.SMALL + 0.6F, col < 0 ? 0x9C9488 : Theme.COLUMN[col]) + 4;
                    if (col == 3) Ui.pill(reg, "done in the " + v.windowLabel(), px, y + 1, Widgets.SMALL + 0.6F, th.raised);
                } else {
                    ((UiFont) l[0]).draw((String) l[3], x0 + pad, y, (Float) l[1], 0xFF000000 | (Integer) l[2]);
                }
            }
            y += h;
        }
        Widgets.unclip();
        detail.drawBar(th);
    }

    private static float lineH(Object[] l) {
        if ("pills".equals(l[0])) return Ui.pillHeight(UiFont.bold(), Widgets.SMALL + 0.6F) + 5;
        return ((UiFont) l[0]).lineHeight((Float) l[1]);
    }

    private void addPara(List<Object[]> lines, String label, String text, int color, float w) {
        UiFont reg = UiFont.regular(), bold = UiFont.bold();
        lines.add(new Object[] { reg, 3F, 0, "" });
        lines.add(new Object[] { bold, Widgets.SMALL + 0.4F, th.muted, label });
        for (String l : TextLayout.wrap(text, w, 0, reg.measure(Widgets.BODY))) lines.add(new Object[] { reg, Widgets.BODY, color, l });
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d == 0) return;
        int mx = Mouse.getEventX() * width / mc.displayWidth;
        if (mx >= detail.x0) detail.wheel(d, 18);
        else list.wheel(d, 18);
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) {
        super.mouseClicked(mx, my, button);
        float fh = footerH();
        if (fh > 0 && mx >= detail.x0 && mx <= detail.x1 && my >= detail.y1 + 2 && my <= detail.y1 + fh) {
            if (dev.agentcraft.gtnh.api.Extensions.client.footerClick("taskwall", selectedId == null ? null : ClientHq.task(selectedId), detail.x0, detail.y1 + 2, detail.x1,
                detail.y1 + fh, mx, my, button)) return;
        }
        if (tabs.click(mx, my)) {
            list.offset = 0;
            return;
        }
        if (!list.contains(mx, my)) return;
        for (Object[] r : rows) {
            if (!(r[0] instanceof HqData.Task)) continue;
            float y0 = list.y0 + (Float) r[1] - list.offset, y1 = list.y0 + (Float) r[2] - list.offset;
            if (my >= y0 && my < y1) {
                selectedId = ((HqData.Task) r[0]).id;
                detail.offset = 0;
                return;
            }
        }
    }

    @Override
    protected void keyTyped(char ch, int key) {
        super.keyTyped(ch, key);
        if (key == Keyboard.KEY_DOWN) detail.wheel(-1, 10);
        if (key == Keyboard.KEY_UP) detail.wheel(1, 10);
        if (key == Keyboard.KEY_TAB) {
            tabs.selected = (tabs.selected + 1) % tabs.tabs.size();
            list.offset = 0;
        }
    }
}
