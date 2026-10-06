package dev.agentcraft.gtnh.client.panels;

import dev.agentcraft.gtnh.client.BoardView;
import dev.agentcraft.gtnh.state.ClientAgentCache;
import dev.agentcraft.gtnh.ui.Theme;
import dev.agentcraft.gtnh.ui.Ui;
import dev.agentcraft.gtnh.ui.UiFont;
import dev.agentcraft.gtnh.ui.panel.PanelContext;
import dev.agentcraft.gtnh.ui.panel.PanelRenderer;

/**
 * "goal": the Goal Atrium. A progress ring (done / total, ALL TIME, labelled so), the open column
 * counts, the done count of the wall's window ("4 done in the last 3 days", the same number the
 * wall's Done column shows) and "N decisions need you" (a count; nothing in the game answers).
 * FAR shows only the big percentage and the decision count.
 */
public final class GoalPanel implements PanelRenderer {

    @Override
    public String id() {
        return "goal";
    }

    @Override
    public String source() {
        return "board";
    }

    @Override
    public String describe() {
        return "Goal atrium: progress ring (all time), open counts, done in the window, decisions waiting";
    }

    @Override
    public void render(PanelContext c) {
        BoardView v = (BoardView) c.data;
        Theme th = c.theme;
        UiFont reg = UiFont.regular(), bold = UiFont.bold(), heavy = UiFont.heavy();
        boolean link = ClientAgentCache.linkUp;
        float pad = c.em(0.08);
        float p = link ? v.goal.progress : 0.0F;
        String pct = Math.round(p * 100) + "%";
        int dec = link ? v.goal.openDecisions : 0;
        int decColor = Theme.mix(th.accent, th.text, 0.35F * PanelText.pulse(c.now));
        String decText = !link ? "Hermes adapter offline" : dec > 0 ? PanelText.plural(dec, "decision needs you", "decisions need you") : "no decisions waiting";

        if (c.lod == PanelContext.FAR) {
            float big = Math.min(c.em(0.85), c.w * 0.32F);
            float ty = c.h * 0.5F - heavy.lineHeight(big) * 0.75F;
            heavy.drawCentered(pct, c.w / 2, ty, big, 0xFF000000 | th.text);
            float ds = Math.min(c.em(0.22), c.w * 0.08F);
            String d = !link ? "offline" : dec > 0 ? dec + " need you" : "all clear";
            bold.drawCentered(d, c.w / 2, ty + heavy.lineHeight(big) * 0.95F, ds, 0xFF000000 | (!link ? th.danger : dec > 0 ? decColor : th.muted));
            return;
        }
        boolean near = c.lod == PanelContext.NEAR;
        float hs = c.em(near ? 0.1 : 0.13);
        bold.drawCentered("Goal atrium", c.w / 2, pad, hs, 0xFF000000 | th.text);
        float y = pad + bold.lineHeight(hs);
        if (near) {
            float ss = c.em(0.07);
            reg.drawCentered(BoardView.scopeLabel(v.binding), c.w / 2, y, ss, 0xFF000000 | th.muted);
            y += reg.lineHeight(ss);
        }
        // bottom block: counts row, window line, decisions line
        float cs = c.em(near ? 0.08 : 0.11), ds = c.em(near ? 0.095 : 0.12);
        float footer = (near ? reg.lineHeight(cs) * 2 + c.em(0.03) : reg.lineHeight(cs)) + bold.lineHeight(ds) + pad * 1.2F;
        float ringTop = y + c.em(0.04);
        float ringSpace = Math.min(c.w - 2 * pad, c.h - ringTop - footer);
        float cx = c.w / 2, cy = ringTop + ringSpace / 2;
        float rOut = ringSpace / 2, rIn = rOut * 0.78F;
        Ui.ring(cx, cy, rIn, rOut, 0, 1, 0xFF000000 | th.raised);
        if (p > 0) Ui.ring(cx, cy, rIn, rOut, 0, p, 0xFF000000 | Theme.COLUMN[3]);
        float ps = rIn * 0.62F;
        heavy.drawCentered(pct, cx, cy - heavy.lineHeight(ps) * (near ? 0.62F : 0.58F), ps, 0xFF000000 | th.text);
        if (!near) {
            // mid distance: keep the window label so the ring never reads as the wall's Done count
            float ls = rIn * 0.2F;
            reg.drawCentered("all time", cx, cy + heavy.lineHeight(ps) * 0.36F, ls, 0xFF000000 | th.muted);
        }
        if (near) {
            float ls = rIn * 0.17F;
            float ly = cy + heavy.lineHeight(ps) * 0.3F;
            bold.drawCentered(v.doneAllTime() + " of " + v.goal.total + " done", cx, ly, ls, 0xFF000000 | Theme.readable(Theme.COLUMN[3], th.bg));
            reg.drawCentered("all time", cx, ly + bold.lineHeight(ls) * 0.95F, ls * 0.9F, 0xFF000000 | th.muted);
        }
        // counts row: todo doing review blocked as coloured dots + numbers (+ labels when near)
        float rowY = c.h - footer + pad * 0.3F;
        int[] order = { 0, 1, 2, 4 };
        float cellW = (c.w - 2 * pad) / order.length;
        for (int i = 0; i < order.length; i++) {
            int col = order[i];
            float x0 = pad + i * cellW;
            float dr = cs * 0.28F;
            String num = String.valueOf(link ? v.count(col) : 0);
            String label = near ? " " + Theme.COLUMN_NAMES[col].toLowerCase() : "";
            float w = bold.width(num, cs) + reg.width(label, cs) + dr * 3;
            float sx = x0 + (cellW - w) / 2;
            Ui.dot(sx + dr, rowY + reg.lineHeight(cs) * 0.5F, dr, 0xFF000000 | Theme.COLUMN[col]);
            float tx = sx + dr * 3;
            tx += bold.draw(num, tx, rowY, cs, 0xFF000000 | th.text);
            if (near) reg.draw(label, tx, rowY, cs, 0xFF000000 | th.muted);
        }
        float wy = rowY + reg.lineHeight(cs);
        if (near) {
            // the wall's Done column, same number, same window label
            String win = PanelText.plural(link ? v.count(3) : 0, "card", "cards") + " done in the " + v.windowLabel();
            reg.drawCentered(win, c.w / 2, wy + c.em(0.01), cs, 0xFF000000 | th.muted);
            wy += reg.lineHeight(cs) + c.em(0.03);
        }
        bold.drawCentered(decText, c.w / 2, wy + c.em(0.02), ds, 0xFF000000 | (!link ? th.danger : dec > 0 ? decColor : th.muted));
    }
}
