package dev.agentcraft.gtnh.client.panels;

import java.util.ArrayList;
import java.util.List;

import dev.agentcraft.gtnh.Config;
import dev.agentcraft.gtnh.ops.OpsData;
import dev.agentcraft.gtnh.ops.OpsHealth;
import dev.agentcraft.gtnh.state.ClientOps;
import dev.agentcraft.gtnh.ui.Theme;
import dev.agentcraft.gtnh.ui.Ui;
import dev.agentcraft.gtnh.ui.UiFont;
import dev.agentcraft.gtnh.ui.panel.PanelContext;
import dev.agentcraft.gtnh.ui.panel.PanelRenderer;

/**
 * Card 5b: the four ops panels (data source "ops" = the client's {@link ClientOps} view; binding =
 * "all" or a filter: a service group, an ops source id, a provider or an alert source). Read-only.
 * Level of detail like the other panels: FAR = one headline readable across the room, MID =
 * one line per entry, NEAR = everything (bars, details, schedules). Display rules from
 * docs/ops-protocol.md section 7, decided in {@link OpsHealth} (pure, checked): only confirmed good
 * data is green; unknown entries and good data from a source in error/stale are grey and keep
 * the headline off green; a failing source is named in the headline (FAR) and the footer (MID,
 * NEAR) of every panel it feeds, alerts included; a missing remaining % is "unknown" (never 0 %);
 * placeholders such as [ip] are shown as they are.
 */
public final class OpsPanelRenderers {

    private OpsPanelRenderers() {}

    static final int UP = 0x5DAA68, WARN = 0xE8A93A, DOWN = 0xC2413B, GREY = 0x9C9488, INFO = 0x7DA2F0, RUN = 0x2FA3A0;

    static int toneColor(String tone) {
        switch (tone) {
            case OpsHealth.BAD:
                return DOWN;
            case OpsHealth.WARN:
                return WARN;
            case OpsHealth.OK:
                return UP;
            case OpsHealth.RUN:
                return RUN;
            default:
                return GREY;
        }
    }

    static int severityColor(String sev) {
        return "critical".equals(sev) ? DOWN : "warn".equals(sev) ? WARN : INFO;
    }

    static int worstColor(String worst) {
        return "error".equals(worst) ? DOWN : "warn".equals(worst) ? WARN : "ok".equals(worst) ? UP : GREY;
    }

    static String scope(String binding) {
        return binding == null || binding.isEmpty() || "all".equals(binding) ? "all sources" : "filter: " + binding;
    }

    /** Header strip: title left, a status pill right. Returns the y below it. */
    static float header(PanelContext c, String title, String status, int statusFill) {
        Theme th = c.theme;
        UiFont bold = UiFont.bold(), reg = UiFont.regular();
        boolean near = c.lod == PanelContext.NEAR;
        float pad = c.em(0.07), hs = c.em(near ? 0.1 : 0.13);
        float pw = status == null ? 0 : Ui.pillRight(bold, status, c.w - pad, pad, hs * 0.8F, statusFill);
        bold.drawFit(title, pad, pad, hs, c.w - 3 * pad - pw, 0xFF000000 | th.text);
        float y = pad + bold.lineHeight(hs);
        if (near) {
            float ss = c.em(0.065);
            reg.drawFit(scope(c.binding), pad, y, ss, c.w - 2 * pad, 0xFF000000 | th.muted);
            y += reg.lineHeight(ss);
        }
        return y + c.em(0.03);
    }

    /** Big centred headline for FAR, with an optional second line. */
    static void headline(PanelContext c, String big, int bigColor, String small) {
        UiFont heavy = UiFont.heavy(), bold = UiFont.bold();
        float bs = Math.min(c.em(0.66), c.w * 0.17F);
        float ss = Math.min(c.em(0.28), c.w * 0.075F);
        float bw = heavy.width(big, bs);
        if (bw > c.w * 0.9F) bs *= c.w * 0.9F / bw; // long headline on a narrow panel: shrink to fit
        if (small != null && bold.width(small, ss) > c.w * 0.92F) ss *= c.w * 0.92F / bold.width(small, ss);
        float total = heavy.lineHeight(bs) + (small == null ? 0 : bold.lineHeight(ss));
        float y = (c.h - total) / 2;
        heavy.drawCentered(big, c.w / 2, y, bs, 0xFF000000 | bigColor);
        if (small != null) bold.drawCentered(small, c.w / 2, y + heavy.lineHeight(bs), ss, 0xFF000000 | c.theme.muted);
    }

    /** True (and draws a notice) if there is nothing to show: feed offline / not offered. */
    static boolean offline(PanelContext c, String title) {
        OpsData.View v = ClientOps.view;
        if (v.live) return false;
        if (c.lod == PanelContext.FAR) {
            headline(c, "ops offline", c.theme.danger, null);
            return true;
        }
        float y = header(c, title, "offline", c.theme.danger);
        UiFont reg = UiFont.regular();
        float s = c.em(0.08), pad = c.em(0.07);
        String why = ClientOps.haveOps ? "The ops feed is offline (adapter link down). Last data is hidden until it is back."
            : "No ops feed yet: the adapter has no ops source, or does not offer ops.* (see docs/ops-protocol.md).";
        for (String l : PanelText.wrap(reg, why, c.w - 2 * pad, s, 4)) {
            reg.draw(l, pad, y, s, 0xFF000000 | c.theme.muted);
            y += reg.lineHeight(s);
        }
        return true;
    }

    static void footerNote(PanelContext c, String text, int color) {
        if (text == null || c.lod == PanelContext.FAR) return;
        UiFont reg = UiFont.regular();
        float s = c.em(c.lod == PanelContext.NEAR ? 0.06 : 0.075), pad = c.em(0.07);
        reg.drawFit(text, pad, c.h - pad - reg.lineHeight(s), s, c.w - 2 * pad, 0xFF000000 | color);
    }

    static float footerH(PanelContext c, String text) {
        return text == null || c.lod == PanelContext.FAR ? 0 : UiFont.regular()
            .lineHeight(c.em(c.lod == PanelContext.NEAR ? 0.06 : 0.075)) + c.em(0.02);
    }

    static void bar(float x0, float y0, float x1, float y1, float frac, int fill, int track) {
        float r = (y1 - y0) / 2;
        Ui.round(x0, y0, x1, y1, r, 0xFF000000 | track);
        if (frac > 0) Ui.round(x0, y0, x0 + Math.max(2 * r, (x1 - x0) * Math.min(1, frac)), y1, r, 0xFF000000 | fill);
    }

    /** Which page of a long list to show now (pages flip every wallPageSeconds). */
    static int page(PanelContext c, int total, int perPage) {
        if (perPage <= 0 || total <= perPage) return 0;
        int pages = (total + perPage - 1) / perPage;
        return (int) ((c.now / (Math.max(2, Config.wallPageSeconds) * 1000L)) % pages);
    }

    /** Type scale for a list: grow (up to maxK) until n rows of rowH fill the available height. */
    static float grow(int n, float rowH, float avail, float maxK) {
        if (n <= 0 || rowH <= 0) return 1F;
        return Math.max(1F, Math.min(maxK, avail / (n * rowH)));
    }

    // ---- Fleet board ----------------------------------------------------------------------------

    public static final class Fleet implements PanelRenderer {

        @Override
        public String id() {
            return "ops-fleet";
        }

        @Override
        public String source() {
            return "ops";
        }

        @Override
        public String describe() {
            return "Fleet board: a tile per host/service with an up/degraded/down lamp; CPU/RAM/disk bars up close";
        }

        @Override
        public void render(PanelContext c) {
            if (offline(c, "Fleet")) return;
            Theme th = c.theme;
            OpsData.View v = ClientOps.view;
            List<OpsData.Service> list = new ArrayList<>();
            for (OpsData.Service s : v.services) if (OpsData.matches(c.binding, s.group, s.sourceId)) list.add(s);
            OpsHealth.Summary sum = OpsHealth.fleet(v, c.binding);
            String status = sum.headline;
            int sc = toneColor(sum.tone);
            if (c.lod == PanelContext.FAR) {
                headline(c, status, sc, sum.small());
                return;
            }
            float y = header(c, "Fleet", status, sc);
            String problem = sum.problem;
            float bottom = c.h - c.em(0.06) - footerH(c, problem);
            boolean near = c.lod == PanelContext.NEAR;
            UiFont bold = UiFont.bold(), reg = UiFont.regular(), mono = UiFont.mono();
            float pad = c.em(0.07), gap = c.em(0.04);
            float ns0 = c.em(near ? 0.075 : 0.11), ds0 = c.em(0.055), bs0 = c.em(0.05);
            float tileH0 = bold.lineHeight(ns0) + c.em(0.04) + (near ? reg.lineHeight(ds0) + 3 * bs0 + c.em(0.02) : 0);
            float tileW0 = c.em(near ? 1.0 : 1.3);
            // grow the tiles (fewer columns, bigger type) while everything still fits on one page
            float k = 1F;
            for (float t = 2.0F; t > 1.0F; t -= 0.1F) {
                int cc = Math.max(1, (int) ((c.w - 2 * pad + gap) / (tileW0 * t + gap)));
                int rr = (list.size() + cc - 1) / cc;
                if (rr * (tileH0 * t + gap) - gap <= bottom - y) {
                    k = t;
                    break;
                }
            }
            float tileW = tileW0 * k;
            int cols = Math.max(1, (int) ((c.w - 2 * pad + gap) / (tileW + gap)));
            tileW = (c.w - 2 * pad - (cols - 1) * gap) / cols;
            float ns = ns0 * k, ds = ds0 * k, bstep = bs0 * k;
            float tileH = tileH0 * k;
            int rows = Math.max(1, (int) ((bottom - y + gap) / (tileH + gap)));
            int per = rows * cols;
            int pg = page(c, list.size(), per);
            for (int i = pg * per, n = 0; i < list.size() && n < per; i++, n++) {
                OpsData.Service s = list.get(i);
                float x0 = pad + (n % cols) * (tileW + gap), y0 = y + (n / cols) * (tileH + gap);
                int col = toneColor(OpsHealth.serviceTone(v, s));
                Ui.round(x0, y0, x0 + tileW, y0 + tileH, c.em(0.03), 0xFF000000 | th.surface);
                Ui.round(x0, y0, x0 + c.em(0.025), y0 + tileH, c.em(0.012), 0xFF000000 | col);
                float lr = ns * 0.24F, tx = x0 + c.em(0.05);
                Ui.dot(tx + lr, y0 + c.em(0.02) + bold.lineHeight(ns) / 2, lr, 0xFF000000 | col);
                bold.drawFit(s.name, tx + lr * 2.6F, y0 + c.em(0.02), ns, tileW - (tx - x0) - lr * 2.6F - c.em(0.03), 0xFF000000 | th.text);
                if (!near) continue;
                float yy = y0 + c.em(0.02) + bold.lineHeight(ns);
                String d = OpsHealth.stateLabel(v, s.sourceId, s.state) + (s.detail.isEmpty() ? "" : " \u00b7 " + s.detail);
                reg.drawFit(d, tx, yy, ds, tileW - (tx - x0) - c.em(0.03), 0xFF000000 | Theme.readable(col, th.surface));
                yy += reg.lineHeight(ds) + c.em(0.01) * k;
                float[] pcts = { s.cpu, s.mem, s.disk };
                String[] names = { "CPU", "RAM", "DSK" };
                float lw = mono.width("DSK ", ds * 0.9F);
                for (int b = 0; b < 3; b++) {
                    float by = yy + b * bstep;
                    mono.draw(names[b], tx, by - ds * 0.1F, ds * 0.8F, 0xFF000000 | th.muted);
                    float bx0 = tx + lw, bx1 = x0 + tileW - c.em(0.04);
                    float b0 = by + bstep * 0.24F, b1 = by + bstep * 0.64F;
                    if (pcts[b] < 0) {
                        bar(bx0, b0, bx1, b1, 0, 0, th.raised);
                        continue;
                    }
                    int fill = pcts[b] >= 90 ? DOWN : pcts[b] >= 75 ? WARN : OpsHealth.suspect(v, s.sourceId) ? GREY : RUN;
                    bar(bx0, b0, bx1, b1, pcts[b] / 100F, fill, th.raised);
                }
            }
            if (list.isEmpty()) reg.draw("No hosts or services for " + scope(c.binding) + ".", pad, y, c.em(0.08), 0xFF000000 | th.muted);
            else if (list.size() > per) reg.drawRight(
                "page " + (pg + 1) + "/" + ((list.size() + per - 1) / per),
                c.w - pad,
                c.h - c.em(0.06) - reg.lineHeight(c.em(0.05)),
                c.em(0.05),
                0xFF000000 | th.muted);
            footerNote(c, problem, WARN);
        }
    }

    // ---- Cron board -----------------------------------------------------------------------------

    public static final class Cron implements PanelRenderer {

        @Override
        public String id() {
            return "ops-cron";
        }

        @Override
        public String source() {
            return "ops";
        }

        @Override
        public String describe() {
            return "Cron board: scheduled jobs with last and next run; failures highlighted";
        }

        @Override
        public void render(PanelContext c) {
            if (offline(c, "Jobs")) return;
            Theme th = c.theme;
            OpsData.View v = ClientOps.view;
            List<OpsData.Job> list = new ArrayList<>();
            for (OpsData.Job j : v.jobs) if (OpsData.matches(c.binding, j.sourceId)) list.add(j);
            OpsHealth.Summary sum = OpsHealth.jobs(v, c.binding);
            String status = sum.headline;
            int sc = toneColor(sum.tone);
            if (c.lod == PanelContext.FAR) {
                headline(c, status, sc, sum.small());
                return;
            }
            float y = header(c, "Jobs", status, sc);
            String problem = sum.problem;
            float bottom = c.h - c.em(0.06) - footerH(c, problem);
            boolean near = c.lod == PanelContext.NEAR;
            UiFont bold = UiFont.bold(), reg = UiFont.regular();
            float pad = c.em(0.07), ns = c.em(near ? 0.08 : 0.11), ds = c.em(0.06);
            float rowH = bold.lineHeight(ns) + (near ? reg.lineHeight(ds) : 0) + c.em(0.035);
            float k = grow(list.size(), rowH, bottom - y, near ? 2.0F : 1.5F);
            ns *= k;
            ds *= k;
            rowH *= k;
            int per = Math.max(1, (int) ((bottom - y) / rowH));
            int pg = page(c, list.size(), per);
            for (int i = pg * per, k2 = 0; i < list.size() && k2 < per; i++, k2++) {
                OpsData.Job j = list.get(i);
                float y0 = y + k2 * rowH;
                boolean bad = j.enabled && "failed".equals(j.lastStatus);
                int col = toneColor(OpsHealth.jobTone(v, j));
                if (bad) Ui.round(pad * 0.5F, y0 - c.em(0.01), c.w - pad * 0.5F, y0 + rowH - c.em(0.025), c.em(0.025), 0xFF000000 | Theme.mix(th.bg, DOWN, 0.22F));
                float lr = ns * 0.24F;
                Ui.dot(pad + lr, y0 + bold.lineHeight(ns) / 2, lr, 0xFF000000 | col);
                long now = c.now;
                String right = j.nextRun > 0 ? "next " + OpsData.age(j.nextRun, now) : j.lastRun > 0 ? "ran " + OpsData.age(j.lastRun, now) : "";
                float rw = reg.drawRight(right, c.w - pad, y0 + (ns - ds) * 0.4F, near ? ds : ns * 0.8F, 0xFF000000 | th.muted);
                bold.drawFit(j.name + (j.enabled ? "" : " (paused)"), pad + lr * 3, y0, ns, c.w - 2 * pad - lr * 3 - rw - c.em(0.04),
                    0xFF000000 | (j.enabled ? th.text : th.muted));
                if (!near) continue;
                String line = OpsHealth.stateLabel(v, j.sourceId, j.lastStatus) + (j.lastRun > 0 ? " \u00b7 last run " + OpsData.age(j.lastRun, now) : " \u00b7 never ran")
                    + (j.durationMs > 0 ? " (" + Math.max(1, j.durationMs / 1000) + " s)" : "")
                    + (j.schedule.isEmpty() ? "" : " \u00b7 " + j.schedule)
                    + (j.detail.isEmpty() ? "" : " \u00b7 " + j.detail);
                reg.drawFit(line, pad + lr * 3, y0 + bold.lineHeight(ns), ds, c.w - 2 * pad - lr * 3, 0xFF000000 | (bad ? Theme.readable(DOWN, th.bg) : th.muted));
            }
            if (list.isEmpty()) reg.draw("No scheduled jobs for " + scope(c.binding) + ".", pad, y, c.em(0.08), 0xFF000000 | th.muted);
            footerNote(c, problem, WARN);
        }
    }

    // ---- Usage panel ----------------------------------------------------------------------------

    public static final class Usage implements PanelRenderer {

        @Override
        public String id() {
            return "ops-usage";
        }

        @Override
        public String source() {
            return "ops";
        }

        @Override
        public String describe() {
            return "Usage panel: remaining quota per provider window, with the reset time";
        }

        @Override
        public void render(PanelContext c) {
            if (offline(c, "Usage")) return;
            Theme th = c.theme;
            OpsData.View v = ClientOps.view;
            List<OpsData.Usage> list = new ArrayList<>();
            for (OpsData.Usage u : v.usage) if (OpsData.matches(c.binding, u.provider, u.sourceId)) list.add(u);
            OpsHealth.Summary sum = OpsHealth.usage(v, c.binding);
            String status = sum.headline;
            int sc = toneColor(sum.tone);
            if (c.lod == PanelContext.FAR) {
                headline(c, status, sc, list.isEmpty() && sum.problem == null ? null : sum.small());
                return;
            }
            float y = header(c, "Usage", status, sc);
            String problem = sum.problem;
            float bottom = c.h - c.em(0.06) - footerH(c, problem);
            boolean near = c.lod == PanelContext.NEAR;
            UiFont bold = UiFont.bold(), reg = UiFont.regular();
            float pad = c.em(0.07), ns = c.em(near ? 0.075 : 0.1), ds = c.em(0.058), bh = c.em(near ? 0.05 : 0.07);
            float rowH = bold.lineHeight(ns) + bh + (near ? reg.lineHeight(ds) : 0) + c.em(0.05);
            float k = grow(list.size(), rowH, bottom - y, near ? 1.8F : 1.4F);
            ns *= k;
            ds *= k;
            bh *= k;
            rowH *= k;
            int per = Math.max(1, (int) ((bottom - y) / rowH));
            int pg = page(c, list.size(), per);
            for (int i = pg * per, k2 = 0; i < list.size() && k2 < per; i++, k2++) {
                OpsData.Usage u = list.get(i);
                float y0 = y + k2 * rowH;
                boolean known = u.remainingPct >= 0;
                int col = toneColor(OpsHealth.usageTone(v, u));
                String pct = known ? (u.remainingPct >= 10 ? String.valueOf(Math.round(u.remainingPct)) : String.format(java.util.Locale.ROOT, "%.1f", u.remainingPct)) + "% left" : "unknown";
                float pw = bold.drawRight(pct, c.w - pad, y0, ns, 0xFF000000 | Theme.readable(col, th.bg));
                bold.drawFit(u.provider + " \u00b7 " + u.window, pad, y0, ns, c.w - 2 * pad - pw - c.em(0.04), 0xFF000000 | th.text);
                float by = y0 + bold.lineHeight(ns);
                bar(pad, by, c.w - pad, by + bh, known ? u.remainingPct / 100F : 0, col, th.raised);
                if (!near) continue;
                String line = (OpsHealth.suspect(v, u.sourceId) ? "last known \u00b7 " : "") + (u.resetsAt > 0 ? "resets " + OpsData.age(u.resetsAt, c.now) : "no reset time") + (u.detail.isEmpty() ? "" : " \u00b7 " + u.detail);
                reg.drawFit(line, pad, by + bh + c.em(0.008), ds, c.w - 2 * pad, 0xFF000000 | th.muted);
            }
            if (list.isEmpty()) reg.draw("No provider usage for " + scope(c.binding) + ".", pad, y, c.em(0.08), 0xFF000000 | th.muted);
            footerNote(c, problem, WARN);
        }
    }

    // ---- Alert feed -----------------------------------------------------------------------------

    public static final class Alerts implements PanelRenderer {

        @Override
        public String id() {
            return "ops-alerts";
        }

        @Override
        public String source() {
            return "ops";
        }

        @Override
        public String describe() {
            return "Alert feed: read-only scrolling list, severity colours, open before resolved";
        }

        @Override
        public void render(PanelContext c) {
            if (offline(c, "Alerts")) return;
            Theme th = c.theme;
            OpsData.View v = ClientOps.view;
            List<OpsData.Alert> list = new ArrayList<>();
            for (OpsData.Alert a : v.alerts) if (OpsData.matches(c.binding, a.source, a.sourceId)) list.add(a);
            OpsHealth.Summary sum = OpsHealth.alerts(v, c.binding);
            String status = sum.headline;
            int sc = toneColor(sum.tone);
            if (c.lod == PanelContext.FAR) {
                headline(c, status, sc, sum.small());
                return;
            }
            float y = header(c, "Alerts", status, sc);
            String problem = sum.problem;
            float bottom = c.h - c.em(0.06) - footerH(c, problem);
            boolean near = c.lod == PanelContext.NEAR;
            UiFont bold = UiFont.bold(), reg = UiFont.regular();
            float pad = c.em(0.07), ns = c.em(near ? 0.072 : 0.1), ds = c.em(0.056);
            float rowH = bold.lineHeight(ns) + (near ? reg.lineHeight(ds) : 0) + c.em(0.04);
            float k = grow(list.size(), rowH, bottom - y, near ? 1.9F : 1.4F);
            ns *= k;
            ds *= k;
            rowH *= k;
            int per = Math.max(1, (int) ((bottom - y) / rowH));
            // scrolling: a long feed advances one page every wallPageSeconds (read-only, no input)
            int pg = page(c, list.size(), per);
            for (int i = pg * per, k2 = 0; i < list.size() && k2 < per; i++, k2++) {
                OpsData.Alert a = list.get(i);
                float y0 = y + k2 * rowH;
                boolean isOpen = "open".equals(a.state);
                int col = isOpen ? severityColor(a.severity) : GREY;
                Ui.round(pad * 0.5F, y0, pad * 0.5F + c.em(0.025), y0 + rowH - c.em(0.03), c.em(0.012), 0xFF000000 | col);
                float tx = pad * 0.5F + c.em(0.06);
                String age = OpsData.age(a.ts, c.now);
                float aw = reg.drawRight(age, c.w - pad, y0 + (ns - ds) * 0.3F, ds, 0xFF000000 | th.muted);
                bold.drawFit(a.title, tx, y0, ns, c.w - pad - tx - aw - c.em(0.04), 0xFF000000 | (isOpen ? th.text : th.muted));
                if (!near) continue;
                String line = a.severity + " \u00b7 " + (isOpen ? "open" : "resolved" + (a.resolvedAt > 0 ? " " + OpsData.age(a.resolvedAt, c.now) : ""))
                    + " \u00b7 " + a.source + (a.detail.isEmpty() ? "" : " \u00b7 " + a.detail);
                reg.drawFit(line, tx, y0 + bold.lineHeight(ns), ds, c.w - pad - tx, 0xFF000000 | (isOpen ? Theme.readable(col, th.bg) : th.muted));
            }
            if (list.isEmpty()) reg.draw("No alerts for " + scope(c.binding) + ".", pad, y, c.em(0.08), 0xFF000000 | th.muted);
            else if (list.size() > per) reg.drawRight(
                (pg + 1) + "/" + ((list.size() + per - 1) / per),
                c.w - pad,
                bottom - reg.lineHeight(c.em(0.05)) + c.em(0.04),
                c.em(0.05),
                0xFF000000 | th.muted);
            footerNote(c, problem, WARN);
        }
    }
}
