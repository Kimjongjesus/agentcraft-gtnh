package dev.agentcraft.gtnh.client.panels;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import dev.agentcraft.gtnh.state.AgentInfo;
import dev.agentcraft.gtnh.state.ClientAgentCache;
import dev.agentcraft.gtnh.state.LogLine;
import dev.agentcraft.gtnh.ui.Theme;
import dev.agentcraft.gtnh.ui.Ui;
import dev.agentcraft.gtnh.ui.UiFont;
import dev.agentcraft.gtnh.ui.panel.PanelContext;
import dev.agentcraft.gtnh.ui.panel.PanelRenderer;

/**
 * "agent-monitor": a desk monitor bound to one agent. NEAR: a header strip in the agent colour
 * (name, state pill), the activity wrapped to two lines, then the log tail in JetBrains Mono (as large as
 * lets the newest few entries fit), each entry wrapped by pixel width and coloured by kind (newest at the bottom). MID: name, state and
 * activity in large type. FAR: the name and the state word only, big.
 */
public final class MonitorPanel implements PanelRenderer {

    private final SimpleDateFormat hhmm = new SimpleDateFormat("HH:mm");

    /** Log type size range (blocks per em) and how many newest entries must fit at the chosen size. */
    static final double LOG_MAX = 0.11, LOG_MIN = 0.058, LOG_STEP = 0.008;
    static final int LOG_FIT_ENTRIES = 4;

    /**
     * The wrapped log rows of the newest {@code maxEntries} entries (oldest first), each as
     * [colour, text, time prefix or ""]. The time prefix column is always reserved.
     */
    private List<Object[]> logLines(List<LogLine> logs, UiFont mono, float ls, float width, Theme th, int maxEntries) {
        List<Object[]> lines = new ArrayList<>();
        float tw = mono.width("00:00 ", ls);
        for (int i = Math.max(0, logs.size() - maxEntries); i < logs.size(); i++) {
            LogLine l = logs.get(i);
            int col = kindColor(l.kind, th);
            String prefix = l.ts > 0 ? hhmm.format(new Date(l.ts)) + " " : "";
            boolean first = true;
            for (String para : l.text.split("\n")) {
                if (para.trim()
                    .isEmpty()) continue;
                for (String w : PanelText.wrap(mono, para, width - tw, ls, 0)) {
                    lines.add(new Object[] { col, w, first ? prefix : "" });
                    first = false;
                }
            }
        }
        return lines;
    }

    @Override
    public String id() {
        return "agent-monitor";
    }

    @Override
    public String source() {
        return "agent";
    }

    @Override
    public String describe() {
        return "Desk monitor: agent name, state, activity, log tail (binding: an agent id)";
    }

    static int kindColor(String kind, Theme th) {
        int c;
        switch (kind) {
            case "tool":
                c = 0x5FC4C0;
                break;
            case "result":
                c = 0x9CC59A;
                break;
            case "error":
                c = 0xF07A70;
                break;
            case "diff":
                c = 0xD9B23A;
                break;
            default:
                c = th.text;
        }
        return Theme.readable(c, th.bg);
    }

    @Override
    public void render(PanelContext c) {
        Theme th = c.theme;
        UiFont reg = UiFont.regular(), bold = UiFont.bold(), heavy = UiFont.heavy(), mono = UiFont.mono();
        AgentInfo a = (AgentInfo) c.data;
        float pad = c.em(0.07);
        if (a == null) {
            float s = c.em(0.1);
            String id = c.binding;
            bold.drawFit(id.isEmpty() || "fleet".equals(id) ? "Agent monitor (unbound)" : "No agent \u201c" + id + "\u201d", pad, pad, s, c.w - 2 * pad, 0xFF000000 | th.text);
            reg.drawFit("Look at it: /agentcraft bind <agent>", pad, pad + bold.lineHeight(s), s * 0.8F, c.w - 2 * pad, 0xFF000000 | th.muted);
            return;
        }
        boolean link = ClientAgentCache.linkUp;
        String fam = link ? a.family() : "offline";
        int status = AgentInfo.familyColor(fam);
        String state = link ? a.state.replace('_', ' ') + (a.waiting ? " \u00b7 needs you" : "") : "adapter offline";
        int nameColor = Theme.readable(a.color, th.bg);

        if (c.lod == PanelContext.FAR) {
            float ns = Math.min(c.em(0.24), c.h * 0.2F);
            bold.drawFit(a.name, pad, c.h * 0.18F, ns, c.w - 2 * pad, 0xFF000000 | nameColor);
            float ss = Math.min(c.em(0.36), c.h * 0.3F);
            heavy.drawFit(state, pad, c.h * 0.18F + bold.lineHeight(ns) * 1.05F, ss, c.w - 2 * pad, 0xFF000000 | Theme.readable(status, th.bg, 3.0));
            return;
        }
        boolean near = c.lod == PanelContext.NEAR;
        // header strip in a dark tone of the agent colour
        float hs = c.em(near ? 0.1 : 0.15);
        float headH = bold.lineHeight(hs) + hs * 0.55F;
        int strip = Theme.mix(a.color, 0x000000, 0.55F);
        Ui.rect(0, 0, c.w, headH, 0xFF000000 | strip);
        float dr = hs * 0.22F;
        Ui.dot(pad + dr, headH / 2, dr, 0xFF000000 | status);
        float ps = hs * 0.72F;
        float pillW = reg.width(state, ps) + 2 * ps * 0.55F;
        bold.drawFit(a.name, pad + dr * 3, (headH - bold.lineHeight(hs)) / 2, hs, c.w - 3 * pad - dr * 3 - pillW, 0xFF000000 | Theme.readable(a.color, strip));
        Ui.pillRight(reg, state, c.w - pad, (headH - Ui.pillHeight(reg, ps)) / 2, ps, status);
        float y = headH + pad * 0.6F;
        float as = c.em(near ? 0.085 : 0.11);
        String act = link ? (a.activity.isEmpty() ? "no current activity" : a.activity) : "Hermes adapter offline";
        for (String l : PanelText.wrap(reg, act, c.w - 2 * pad, as, near ? 2 : 3)) {
            reg.draw(l, pad, y, as, 0xFF000000 | th.text);
            y += reg.lineHeight(as);
        }
        if (!near) return;
        y += pad * 0.3F;
        Ui.rect(pad, y, c.w - pad, y + c.em(0.006), 0xFF000000 | th.line);
        y += pad * 0.5F;
        // Log size adapts to the space: the largest size (down to the old fixed minimum) at which
        // the newest few entries fit completely, so a short log fills the screen in big type and
        // a long one still shows its tail.
        List<LogLine> logs = ClientAgentCache.logs(a.id);
        float avail = c.h - pad * 0.6F - y;
        float ls = c.em(LOG_MIN);
        for (double s = LOG_MAX; s > LOG_MIN; s -= LOG_STEP) {
            float cand = c.em(s);
            if (logLines(logs, mono, cand, c.w - 2 * pad, th, LOG_FIT_ENTRIES).size() * mono.lineHeight(cand) <= avail) {
                ls = cand;
                break;
            }
        }
        float lh = mono.lineHeight(ls);
        int rows = Math.max(1, (int) (avail / lh));
        float tw = mono.width("00:00 ", ls);
        List<Object[]> lines = logLines(logs, mono, ls, c.w - 2 * pad, th, Integer.MAX_VALUE);
        if (lines.isEmpty()) {
            reg.draw("(no recent log lines)", pad, y, c.em(0.07), 0xFF000000 | th.muted);
            return;
        }
        int from = Math.max(0, lines.size() - rows);
        for (int i = from; i < lines.size(); i++) {
            Object[] l = lines.get(i);
            if (!((String) l[2]).isEmpty()) mono.draw((String) l[2], pad, y, ls, 0xFF000000 | th.muted);
            mono.draw((String) l[1], pad + tw, y, ls, 0xFF000000 | (Integer) l[0]);
            y += lh;
        }
    }
}
