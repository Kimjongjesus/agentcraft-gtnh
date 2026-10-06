package dev.agentcraft.gtnh.client.panels;

import java.util.ArrayList;
import java.util.List;

import dev.agentcraft.gtnh.Config;
import dev.agentcraft.gtnh.client.BoardView;
import dev.agentcraft.gtnh.state.ClientAgentCache;
import dev.agentcraft.gtnh.state.ClientHq;
import dev.agentcraft.gtnh.state.HqData;
import dev.agentcraft.gtnh.ui.Theme;
import dev.agentcraft.gtnh.ui.Ui;
import dev.agentcraft.gtnh.ui.UiFont;
import dev.agentcraft.gtnh.ui.panel.PanelContext;
import dev.agentcraft.gtnh.ui.panel.PanelRenderer;

/**
 * "kanban": the Task Wall. Five segmented columns (To do, Doing, Review, Done, Blocked) whose counts
 * come from {@link BoardView} (one source of truth with the atrium; Done is labelled with its
 * window). Level of detail by viewer distance:
 * <ul>
 * <li>NEAR: cards with the full title wrapped to three lines (pixel width, ellipsized only on the
 * last line), the assignee in its agent colour and a priority pill;</li>
 * <li>MID: larger titles wrapped to two lines, larger column counts;</li>
 * <li>FAR: each column's name and a big count, nothing else.</li>
 * </ul>
 * A column with more cards than fit flips pages every wallPageSeconds ("1/3" in its header).
 */
public final class KanbanPanel implements PanelRenderer {

    @Override
    public String id() {
        return "kanban";
    }

    @Override
    public String source() {
        return "board";
    }

    @Override
    public String describe() {
        return "Task wall: five columns, card titles, assignees (binding: a board slug or all)";
    }

    @Override
    public void render(PanelContext c) {
        BoardView v = (BoardView) c.data;
        Theme th = c.theme;
        UiFont reg = UiFont.regular(), bold = UiFont.bold(), heavy = UiFont.heavy();
        boolean link = ClientAgentCache.linkUp;
        float pad = c.em(0.07);

        // ---- header: title, scope, open / need-you summary ---------------------------------
        float hs = c.em(c.lod == PanelContext.NEAR ? 0.10 : c.lod == PanelContext.MID ? 0.13 : 0.16);
        float y = pad;
        float titleW = bold.draw("Task wall", pad, y, hs, 0xFF000000 | th.text);
        if (c.lod != PanelContext.FAR) reg.draw(BoardView.scopeLabel(v.binding), pad + titleW + hs * 0.6F, y + hs * 0.12F, hs * 0.82F, 0xFF000000 | th.muted);
        String right;
        int rightColor;
        if (!link) {
            right = "Hermes adapter offline";
            rightColor = th.danger;
        } else if (!ClientHq.haveBoard) {
            right = "waiting for the board\u2026";
            rightColor = th.muted;
        } else if (v.goal.openDecisions > 0) {
            right = v.goal.openDecisions + " need you";
            rightColor = Theme.mix(th.accent, th.text, 0.35F * PanelText.pulse(c.now));
        } else {
            right = v.openCount() + " open";
            rightColor = th.muted;
        }
        float rw = bold.width(right, hs);
        if (link && ClientHq.haveBoard && v.goal.openDecisions > 0 && c.lod != PanelContext.FAR) {
            String open = v.openCount() + " open  \u00b7  ";
            reg.drawRight(open, c.w - pad - rw, y + hs * 0.12F, hs * 0.82F, 0xFF000000 | th.muted);
        }
        bold.drawRight(right, c.w - pad, y, hs, 0xFF000000 | rightColor);
        float top = y + bold.lineHeight(hs) + pad * 0.6F;

        // ---- columns ---------------------------------------------------------------------
        int n = HqData.COLUMNS.length;
        float gap = c.em(0.045);
        float colW = (c.w - 2 * pad - gap * (n - 1)) / n;
        float bottom = c.h - pad;
        long pageTick = c.now / (Math.max(2, Config.wallPageSeconds) * 1000L);
        for (int col = 0; col < n; col++) {
            float x0 = pad + col * (colW + gap), x1 = x0 + colW;
            column(c, v, col, x0, top, x1, bottom, pageTick);
        }
    }

    private void column(PanelContext c, BoardView v, int col, float x0, float y0, float x1, float y1, long pageTick) {
        Theme th = c.theme;
        UiFont reg = UiFont.regular(), bold = UiFont.bold(), heavy = UiFont.heavy();
        List<HqData.Task> cards = v.columns.get(col);
        int count = v.count(col);
        float r = c.em(0.035);
        float hsz = c.em(c.lod == PanelContext.NEAR ? 0.085 : 0.15);
        Ui.round(x0, y0, x1, y1, r, 0xFF000000 | th.surface);
        float headH = bold.lineHeight(hsz) + hsz * 0.45F;
        int fill = Theme.COLUMN[col];
        Ui.round(x0, y0, x1, y0 + headH, r, 0xFF000000 | fill);
        Ui.rect(x0, y0 + headH - r, x1, y0 + headH, 0xFF000000 | fill);
        float hp = hsz * 0.5F;
        int ink = 0xFF000000 | Theme.onColumn(col);
        if (c.lod == PanelContext.FAR) {
            bold.drawFit(Theme.COLUMN_NAMES[col], x0 + hp, y0 + hsz * 0.2F, hsz, x1 - x0 - 2 * hp, ink);
            float big = Math.min(c.em(0.5), (x1 - x0) * 0.42F);
            String num = String.valueOf(count);
            float midY = y0 + headH + (y1 - y0 - headH) * 0.5F - heavy.lineHeight(big) * 0.62F;
            heavy.drawCentered(num, (x0 + x1) / 2, midY, big, 0xFF000000 | th.text);
            String cap = col == 3 ? v.windowLabel() : col == 4 && count > 0 ? "blocked" : "open";
            reg.drawCentered(cap, (x0 + x1) / 2, midY + heavy.lineHeight(big) * 0.95F, c.em(0.12), 0xFF000000 | th.muted);
            return;
        }
        // header: name left, count right (Done: count of the window, labelled below)
        String num = String.valueOf(count);
        float nw = heavy.width(num, hsz);
        bold.drawFit(Theme.COLUMN_NAMES[col], x0 + hp, y0 + hsz * 0.2F, hsz, x1 - x0 - 3 * hp - nw, ink);
        heavy.drawRight(num, x1 - hp, y0 + hsz * 0.2F, hsz, ink);
        float y = y0 + headH + c.em(0.03);
        float inner = c.em(0.035);
        if (col == 3) {
            // the window, said out loud: "last 3 days · 44 all time"
            float cs = c.em(c.lod == PanelContext.NEAR ? 0.058 : 0.085);
            String cap = c.lod == PanelContext.NEAR ? v.windowLabel() + "  \u00b7  " + v.doneAllTime() + " all time" : v.windowLabel();
            reg.drawFit(cap, x0 + inner, y, cs, x1 - x0 - 2 * inner, 0xFF000000 | th.muted);
            y += reg.lineHeight(cs) + c.em(0.015);
        }
        if (cards.isEmpty()) {
            float es = c.em(c.lod == PanelContext.NEAR ? 0.065 : 0.09);
            reg.drawCentered(count > 0 ? count + " not on this wall" : "nothing here", (x0 + x1) / 2, y + es * 0.6F, es, 0xFF000000 | th.muted);
            return;
        }
        // lay out cards into pages (variable heights)
        float cx0 = x0 + inner, cx1 = x1 - inner, avail = y1 - inner - y;
        List<float[]> heights = new ArrayList<>();
        for (HqData.Task t : cards) heights.add(new float[] { cardHeight(c, t, cx1 - cx0) });
        float cg = c.em(0.025);
        List<int[]> pages = new ArrayList<>(); // [from, to)
        int from = 0;
        float used = 0;
        for (int i = 0; i < cards.size(); i++) {
            float hh = heights.get(i)[0];
            if (used > 0 && used + hh > avail) {
                pages.add(new int[] { from, i });
                from = i;
                used = 0;
            }
            used += hh + cg;
        }
        pages.add(new int[] { from, cards.size() });
        int page = (int) (pageTick % pages.size());
        if (pages.size() > 1) {
            // page marker under the header, right
            String pg = (page + 1) + "/" + pages.size();
            float ps = c.em(c.lod == PanelContext.NEAR ? 0.055 : 0.08);
            reg.drawRight(pg, x1 - inner, y1 - reg.lineHeight(ps) - c.em(0.005), ps, 0xFF000000 | th.muted);
            avail -= reg.lineHeight(ps);
        }
        int[] p = pages.get(page);
        for (int i = p[0]; i < p[1]; i++) {
            float hh = heights.get(i)[0];
            if (y + hh > y1 - inner + 0.5F && i > p[0]) break;
            card(c, cards.get(i), col, cx0, y, cx1, y + hh);
            y += hh + cg;
        }
        if (cards.size() < count && page == pages.size() - 1 && c.lod == PanelContext.NEAR) {
            float ms = c.em(0.055);
            if (y + reg.lineHeight(ms) <= y1 - inner) reg.drawCentered("+" + (count - cards.size()) + " more (see the screen)", (x0 + x1) / 2, y, ms, 0xFF000000 | th.muted);
        }
    }

    /** Title lines per card: three up close, two at mid distance (wrapped by pixel width, the last one ellipsized). */
    private static int titleLines(PanelContext c) {
        return c.lod == PanelContext.NEAR ? 3 : 2;
    }

    private static float titleSize(PanelContext c) {
        return c.em(c.lod == PanelContext.NEAR ? 0.078 : 0.12);
    }

    private float cardHeight(PanelContext c, HqData.Task t, float w) {
        UiFont bold = UiFont.bold(), reg = UiFont.regular();
        float ts = titleSize(c), pad = c.em(0.03);
        float stripe = c.em(0.02);
        int lines = Math.max(1, PanelText.wrap(bold, t.title, w - stripe - 2 * pad, ts, titleLines(c)).size());
        if (c.lod == PanelContext.MID) return lines * bold.lineHeight(ts) + 2 * pad;
        float ms = c.em(0.06);
        return lines * bold.lineHeight(ts) + Ui.pillHeight(reg, ms) + pad * 2.6F;
    }

    private void card(PanelContext c, HqData.Task t, int col, float x0, float y0, float x1, float y1) {
        Theme th = c.theme;
        UiFont bold = UiFont.bold(), reg = UiFont.regular();
        float r = c.em(0.025), pad = c.em(0.03), stripe = c.em(0.02);
        int bg = col == 4 ? Theme.mix(th.card, Theme.COLUMN[4], 0.18F) : col == 3 ? Theme.mix(th.card, Theme.COLUMN[3], 0.16F) : th.card;
        Ui.round(x0, y0, x1, y1, r, 0xFF000000 | bg);
        int agent = t.assignee.isEmpty() ? 0x9C9488 : BoardView.agentColor(t.assignee);
        Ui.round(x0, y0, x0 + stripe * 2, y1, r, 0xFF000000 | agent);
        Ui.rect(x0 + stripe, y0, x0 + stripe * 2, y1, 0xFF000000 | bg);
        float tx = x0 + stripe + pad, tw = x1 - tx - pad;
        float ts = titleSize(c);
        List<String> lines = PanelText.wrap(bold, t.title, tw, ts, titleLines(c));
        float y = y0 + pad;
        for (String l : lines) {
            bold.draw(l, tx, y, ts, 0xFF000000 | th.cardText);
            y += bold.lineHeight(ts);
        }
        if (c.lod == PanelContext.MID) return;
        // meta row: assignee (agent colour, readable on the card) | priority pill, deps
        float ms = c.em(0.06);
        float my = y1 - pad - Ui.pillHeight(reg, ms);
        float right = x1 - pad;
        if (t.priority > 0) {
            int pf = t.priority >= 80 ? 0xD97757 : t.priority >= 50 ? 0xC9A227 : 0xCFC4B3;
            right -= Ui.pillRight(reg, "P" + t.priority, right, my, ms, pf) + ms * 0.3F;
        }
        if (!t.deps.isEmpty()) {
            String d = "\u2192" + t.deps.size();
            right -= reg.drawRight(d, right, my + ms * 0.08F, ms, 0xFF000000 | th.cardMuted) + ms * 0.3F;
        }
        String who = t.assignee.isEmpty() ? "unassigned" : BoardView.agentName(t.assignee);
        int wc = t.assignee.isEmpty() ? th.cardMuted : Theme.readable(agent, bg);
        UiFont wf = t.assignee.isEmpty() ? reg : bold;
        wf.drawFit(who, tx, my + ms * 0.08F, ms, right - tx - ms * 0.2F, 0xFF000000 | wc);
    }
}
