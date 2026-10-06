package dev.agentcraft.gtnh.state;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Client-side mirror of the agents, monitor tails and the op overlay, filled by Net (SimpleNetworkWrapper). */
public final class ClientAgentCache {

    private static final Map<String, AgentInfo> BY_ID = new ConcurrentHashMap<>();
    private static final Map<Integer, String> ID_BY_ENTITY = new ConcurrentHashMap<>();
    private static final Map<String, List<LogLine>> LOGS = new ConcurrentHashMap<>();
    public static volatile boolean linkUp;
    public static volatile String fleet = "offline";
    public static volatile int overflow;
    public static volatile long updates, logUpdates;

    // op debug overlay (/agentcraft anchor show)
    public static volatile List<String> overlayNames = Collections.emptyList();
    public static volatile List<double[]> overlaySpots = Collections.emptyList();
    public static volatile List<String> overlayMissing = Collections.emptyList();
    public static volatile long overlayUntil;

    private ClientAgentCache() {}

    public static void replace(List<AgentInfo> agents, List<Integer> entityIds, boolean link, String fleetFamily,
        int over) {
        Map<String, AgentInfo> next = new ConcurrentHashMap<>();
        Map<Integer, String> ents = new ConcurrentHashMap<>();
        for (int i = 0; i < agents.size(); i++) {
            AgentInfo a = agents.get(i);
            next.put(a.id, a);
            if (i < entityIds.size() && entityIds.get(i) >= 0) ents.put(entityIds.get(i), a.id);
        }
        BY_ID.keySet()
            .retainAll(next.keySet());
        BY_ID.putAll(next);
        ID_BY_ENTITY.clear();
        ID_BY_ENTITY.putAll(ents);
        LOGS.keySet()
            .retainAll(next.keySet());
        linkUp = link;
        fleet = fleetFamily;
        overflow = over;
        updates++;
    }

    public static void putLogs(String agentId, List<LogLine> lines) {
        LOGS.put(agentId, Collections.unmodifiableList(new ArrayList<>(lines)));
        logUpdates++;
    }

    public static List<LogLine> logs(String agentId) {
        List<LogLine> l = agentId == null ? null : LOGS.get(agentId);
        return l == null ? Collections.<LogLine>emptyList() : l;
    }

    public static AgentInfo get(String agentId) {
        return agentId == null ? null : BY_ID.get(agentId);
    }

    public static Collection<AgentInfo> all() {
        return BY_ID.values();
    }

    public static AgentInfo forEntity(int entityId) {
        String id = ID_BY_ENTITY.get(entityId);
        return id == null ? null : BY_ID.get(id);
    }

    /** Status family for a lamp binding: "fleet" (or empty) = the whole fleet, else one agent id. */
    public static String familyFor(String binding) {
        if (!linkUp) return "offline";
        if (binding == null || binding.isEmpty() || "fleet".equals(binding)) return fleet;
        AgentInfo a = BY_ID.get(binding);
        if (a == null) return "offline";
        return a.active || a.waiting ? a.family() : "offline";
    }

    public static void setOverlay(List<String> names, List<double[]> spots, List<String> missing, int seconds) {
        overlayNames = Collections.unmodifiableList(new ArrayList<>(names));
        overlaySpots = Collections.unmodifiableList(new ArrayList<>(spots));
        overlayMissing = Collections.unmodifiableList(new ArrayList<>(missing));
        overlayUntil = seconds <= 0 ? 0 : System.currentTimeMillis() + seconds * 1000L;
    }
}
