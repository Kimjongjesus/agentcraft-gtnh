package dev.agentcraft.gtnh.ops;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Card 5b (review r1): what each ops panel says about health, source-aware and the same at every
 * level of detail. Pure Java, so every rule is checked headless (OpsCheck). The rules, from
 * docs/ops-protocol.md section 7:
 * <ul>
 * <li>only CONFIRMED good data is ever green: an entry is green only when its own state is good
 * AND its source is not in {@code error} / {@code stale};</li>
 * <li>unknown entries (unknown services, jobs that never ran or report "unknown", usage without a
 * remaining %) are grey and keep the headline grey: "all up" / "all ok" need every entry
 * confirmed;</li>
 * <li>a source in {@code error} or {@code stale} keeps its last good data on the board (the
 * protocol says so), but that data is only "last known": good entries turn grey, bad ones stay
 * bad (stale data may still warn, it never reassures), and the panel headline names the problem
 * (amber) at every distance unless something is already down / failed / critical;</li>
 * <li>which sources matter for a panel: those whose entries it shows, plus (for an "all" binding or
 * a binding naming the source) every failing source whose last good collect carried the panel's
 * kind, or that never reported its kinds (it might carry them).</li>
 * </ul>
 * Tones: {@link #BAD} red, {@link #WARN} amber, {@link #OK} green, {@link #RUN} teal (a job is
 * running), {@link #UNKNOWN} grey.
 */
public final class OpsHealth {

    public static final String BAD = "bad", WARN = "warn", OK = "ok", RUN = "run", UNKNOWN = "unknown";

    private OpsHealth() {}

    /** A panel's headline: big text + tone, a second line, and the source problem (or null). */
    public static final class Summary {

        public String headline = "", tone = UNKNOWN, counts = "";
        /** "name: error · detail" for the first failing source that matters here, else null */
        public String problem;
        public int total;

        /** FAR second line: the problem when there is one (it explains a grey/amber board), else the counts. */
        public String small() {
            return problem != null ? problem : counts;
        }
    }

    public static boolean failing(OpsData.Source s) {
        return "error".equals(s.state) || "stale".equals(s.state);
    }

    /** True if the entry's source is in error / stale (its data is only last known). */
    public static boolean suspect(OpsData.View v, String sourceId) {
        if (sourceId == null || sourceId.isEmpty()) return false;
        for (OpsData.Source s : v.sources) if (sourceId.equals(s.id)) return failing(s);
        return false;
    }

    /** Failing sources that matter for a panel of {@code kindBit} with {@code binding}. */
    public static List<OpsData.Source> problems(OpsData.View v, int kindBit, String binding, Set<String> shownSourceIds) {
        List<OpsData.Source> out = new ArrayList<>();
        boolean all = binding == null || binding.isEmpty() || "all".equals(binding);
        for (OpsData.Source s : v.sources) {
            if (!failing(s)) continue;
            boolean shown = shownSourceIds.contains(s.id);
            boolean bound = (all || binding.equalsIgnoreCase(s.id)) && s.carries(kindBit);
            if (shown || bound) out.add(s);
        }
        return out;
    }

    static String problemLine(List<OpsData.Source> ps) {
        if (ps.isEmpty()) return null;
        OpsData.Source s = ps.get(0);
        return s.name + ": " + s.state + (s.detail.isEmpty() ? "" : " \u00b7 " + s.detail) + (ps.size() > 1 ? " (+" + (ps.size() - 1) + " more)" : "");
    }

    static String problemHeadline(List<OpsData.Source> ps) {
        for (OpsData.Source s : ps) if ("error".equals(s.state)) return "source error";
        return "data stale";
    }

    // ---- per entry ------------------------------------------------------------------------------

    public static String serviceTone(OpsData.View v, OpsData.Service s) {
        if ("down".equals(s.state)) return BAD;
        if ("degraded".equals(s.state)) return WARN;
        if ("up".equals(s.state) && !suspect(v, s.sourceId)) return OK;
        return UNKNOWN;
    }

    public static String jobTone(OpsData.View v, OpsData.Job j) {
        if (!j.enabled) return UNKNOWN;
        if ("failed".equals(j.lastStatus)) return BAD;
        if (suspect(v, j.sourceId)) return UNKNOWN;
        if ("ok".equals(j.lastStatus)) return OK;
        if ("running".equals(j.lastStatus)) return RUN;
        return UNKNOWN;
    }

    public static String usageTone(OpsData.View v, OpsData.Usage u) {
        if (u.remainingPct < 0) return UNKNOWN;
        if (u.remainingPct < 10) return BAD;
        if (u.remainingPct < 25) return WARN;
        return suspect(v, u.sourceId) ? UNKNOWN : OK;
    }

    /** The state word for a detail line, marked when the source is failing ("up · last known"). */
    public static String stateLabel(OpsData.View v, String sourceId, String state) {
        return suspect(v, sourceId) ? state + " \u00b7 last known" : state;
    }

    // ---- per panel ------------------------------------------------------------------------------

    public static Summary fleet(OpsData.View v, String binding) {
        int down = 0, deg = 0, up = 0, unk = 0, n = 0;
        Set<String> shown = new HashSet<>();
        for (OpsData.Service s : v.services) {
            if (!OpsData.matches(binding, s.group, s.sourceId)) continue;
            n++;
            shown.add(s.sourceId);
            String t = serviceTone(v, s);
            if (BAD.equals(t)) down++;
            else if (WARN.equals(t)) deg++;
            else if (OK.equals(t)) up++;
            else unk++;
        }
        List<OpsData.Source> ps = problems(v, OpsData.KIND_SERVICE, binding, shown);
        Summary r = new Summary();
        r.total = n;
        r.problem = problemLine(ps);
        if (down > 0) set(r, down + " down", BAD);
        else if (deg > 0) set(r, deg + " degraded", WARN);
        else if (!ps.isEmpty()) set(r, problemHeadline(ps), WARN);
        else if (n == 0) set(r, "no data", UNKNOWN);
        else if (unk > 0) set(r, unk + " unknown", UNKNOWN);
        else set(r, "all up", OK);
        StringBuilder c = new StringBuilder();
        c.append(up)
            .append(" up");
        if (deg > 0 && down > 0) c.append(" \u00b7 ")
            .append(deg)
            .append(" degraded");
        if (unk > 0) c.append(" \u00b7 ")
            .append(unk)
            .append(" unknown");
        c.append(" \u00b7 ")
            .append(n)
            .append(" total");
        r.counts = n == 0 ? "no hosts or services" : c.toString();
        return r;
    }

    public static Summary jobs(OpsData.View v, String binding) {
        int failed = 0, running = 0, ok = 0, unk = 0, paused = 0, n = 0;
        Set<String> shown = new HashSet<>();
        for (OpsData.Job j : v.jobs) {
            if (!OpsData.matches(binding, j.sourceId)) continue;
            n++;
            shown.add(j.sourceId);
            if (!j.enabled) {
                paused++;
                continue;
            }
            String t = jobTone(v, j);
            if (BAD.equals(t)) failed++;
            else if (RUN.equals(t)) running++;
            else if (OK.equals(t)) ok++;
            else unk++;
        }
        List<OpsData.Source> ps = problems(v, OpsData.KIND_JOB, binding, shown);
        Summary r = new Summary();
        r.total = n;
        r.problem = problemLine(ps);
        if (failed > 0) set(r, failed + " failed", BAD);
        else if (!ps.isEmpty()) set(r, problemHeadline(ps), WARN);
        else if (n == 0) set(r, "no jobs", UNKNOWN);
        else if (running > 0) set(r, running + " running", RUN);
        else if (unk > 0) set(r, unk + " unknown", UNKNOWN);
        else if (ok == 0) set(r, "all paused", UNKNOWN);
        else set(r, "all ok", OK);
        r.counts = n + (n == 1 ? " scheduled job" : " scheduled jobs") + (unk > 0 ? " \u00b7 " + unk + " unknown" : "")
            + (paused > 0 ? " \u00b7 " + paused + " paused" : "");
        return r;
    }

    /** Usage: the headline is the lowest KNOWN remaining %; any unknown or stale entry keeps it from green. */
    public static Summary usage(OpsData.View v, String binding) {
        OpsData.Usage low = null;
        int unk = 0, n = 0;
        boolean stale = false;
        Set<String> shown = new HashSet<>();
        for (OpsData.Usage u : v.usage) {
            if (!OpsData.matches(binding, u.provider, u.sourceId)) continue;
            n++;
            shown.add(u.sourceId);
            if (u.remainingPct < 0) {
                unk++;
                continue;
            }
            if (suspect(v, u.sourceId)) stale = true;
            if (low == null || u.remainingPct < low.remainingPct) low = u;
        }
        List<OpsData.Source> ps = problems(v, OpsData.KIND_USAGE, binding, shown);
        Summary r = new Summary();
        r.total = n;
        r.problem = problemLine(ps);
        String pct = low == null ? "" : Math.round(low.remainingPct) + "% left";
        if (low != null && low.remainingPct < 10) set(r, pct, BAD);
        else if (low != null && low.remainingPct < 25) set(r, pct, WARN);
        else if (!ps.isEmpty()) set(r, problemHeadline(ps), WARN);
        else if (n == 0) set(r, "no data", UNKNOWN);
        else if (low == null) set(r, "unknown", UNKNOWN);
        else set(r, pct, unk > 0 || stale ? UNKNOWN : OK);
        r.counts = low == null ? (n == 0 ? "no provider usage" : unk + " unknown")
            : "lowest" + (unk > 0 ? " known" : "") + ": " + low.provider + " \u00b7 " + low.window + (unk > 0 ? " \u00b7 " + unk + " unknown" : "");
        return r;
    }

    /**
     * Alerts: "no open alerts" is green only when an alert source is known and healthy; a failing
     * source that may carry alerts makes the feed "unverified" (amber) at every distance.
     */
    public static Summary alerts(OpsData.View v, String binding) {
        int open = 0, crit = 0, n = 0;
        Set<String> shown = new HashSet<>();
        for (OpsData.Alert a : v.alerts) {
            if (!OpsData.matches(binding, a.source, a.sourceId)) continue;
            n++;
            shown.add(a.sourceId);
            if ("open".equals(a.state)) {
                open++;
                if ("critical".equals(a.severity)) crit++;
            }
        }
        List<OpsData.Source> ps = problems(v, OpsData.KIND_ALERT, binding, shown);
        boolean anyAlertSource = !shown.isEmpty();
        boolean all = binding == null || binding.isEmpty() || "all".equals(binding);
        for (OpsData.Source s : v.sources) {
            if ((all || binding.equalsIgnoreCase(s.id)) && s.carries(OpsData.KIND_ALERT) && !"starting".equals(s.state)) anyAlertSource = true;
        }
        Summary r = new Summary();
        r.total = n;
        r.problem = problemLine(ps);
        if (crit > 0) set(r, open + " open", BAD);
        else if (open > 0 && ps.isEmpty()) set(r, open + " open", WARN);
        else if (!ps.isEmpty()) set(r, open > 0 ? open + " open" : "alerts unverified", WARN);
        else if (!anyAlertSource) set(r, "no alert source", UNKNOWN);
        else set(r, "no open alerts", OK);
        r.counts = crit > 0 ? crit + " critical" : n + " in the feed";
        return r;
    }

    private static void set(Summary r, String headline, String tone) {
        r.headline = headline;
        r.tone = tone;
    }
}
