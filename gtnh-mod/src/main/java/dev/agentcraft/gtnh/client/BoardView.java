package dev.agentcraft.gtnh.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.agentcraft.gtnh.state.AgentInfo;
import dev.agentcraft.gtnh.state.ClientAgentCache;
import dev.agentcraft.gtnh.state.ClientHq;
import dev.agentcraft.gtnh.state.HqData;

/**
 * The board as every surface shows it (task wall panel, task wall screen, atrium, beacon label):
 * the five columns of one binding plus THE counts, taken from the board's goal (one source of truth,
 * card 4). Open columns count every open card; the Done column counts the done cards of the
 * adapter's task window ("last 3 days") and the ring counts every done card ever ("all time").
 * Cached per {@link ClientHq#version}.
 */
public final class BoardView {

    public final String binding;
    public final List<List<HqData.Task>> columns;
    public final HqData.Goal goal;

    private static long cacheVersion = -1;
    private static final Map<String, BoardView> CACHE = new HashMap<>();

    private BoardView(String binding) {
        this.binding = binding == null ? "" : binding;
        this.goal = ClientHq.summary(this.binding);
        List<List<HqData.Task>> cols = new ArrayList<>();
        for (int i = 0; i < HqData.COLUMNS.length; i++) cols.add(new ArrayList<>());
        for (HqData.Task t : ClientHq.tasksFor(this.binding)) cols.get(HqData.column(t.status))
            .add(t);
        for (int i = 0; i < cols.size(); i++) {
            final boolean done = i == 3;
            Collections.sort(
                cols.get(i),
                (a, b) -> done || a.priority == b.priority ? Long.compare(b.updatedAt, a.updatedAt)
                    : Integer.compare(b.priority, a.priority));
        }
        this.columns = cols;
    }

    public static BoardView of(String binding) {
        if (cacheVersion != ClientHq.version) {
            CACHE.clear();
            cacheVersion = ClientHq.version;
        }
        String key = binding == null ? "" : binding;
        BoardView v = CACHE.get(key);
        if (v == null) {
            v = new BoardView(key);
            CACHE.put(key, v);
        }
        return v;
    }

    /** True when the goal carries counts for this binding (else the card lists are all we have). */
    public boolean hasGoal() {
        return goal.total > 0 || goal.doneRecent > 0;
    }

    /** Column count as labelled on the wall: open columns = every open card, Done = the window. */
    public int count(int c) {
        if (!hasGoal()) return columns.get(c)
            .size();
        return c == 3 ? goal.doneRecent : goal.counts[c];
    }

    /** All-time done count (the atrium ring). */
    public int doneAllTime() {
        return hasGoal() ? goal.counts[3] : columns.get(3)
            .size();
    }

    public int openCount() {
        return count(0) + count(1) + count(2) + count(4);
    }

    /** "last 3 days" / "last 36 hours" / "all time" (no window). */
    public String windowLabel() {
        return windowLabel(goal.windowDays);
    }

    public static String windowLabel(float days) {
        if (days <= 0) return "all time";
        if (days < 2) return "last " + Math.round(days * 24) + " hours";
        return "last " + (Math.abs(days - Math.round(days)) < 0.05 ? String.valueOf(Math.round(days)) : String.format("%.1f", days)) + " days";
    }

    /** Short form for tight headers: "3d" / "all". */
    public String windowShort() {
        float d = goal.windowDays;
        if (d <= 0) return "all";
        return d < 2 ? Math.round(d * 24) + "h" : Math.round(d) + "d";
    }

    public static String scopeLabel(String binding) {
        return binding == null || binding.isEmpty() || "all".equals(binding) ? "All boards" : "Board " + binding;
    }

    /** One sentence for detail views: what each number means. */
    public String countsExplained() {
        return "Open columns count every open card. Done shows the " + count(3) + " cards finished in the "
            + windowLabel() + " (the window the adapter keeps on the wall); the progress ring counts all "
            + doneAllTime() + " cards ever finished (all time).";
    }

    public static String agentName(String id) {
        AgentInfo a = ClientAgentCache.get(id);
        return a == null ? id : a.name;
    }

    public static int agentColor(String id) {
        AgentInfo a = ClientAgentCache.get(id);
        return a == null ? 0x9C9488 : a.color;
    }
}
