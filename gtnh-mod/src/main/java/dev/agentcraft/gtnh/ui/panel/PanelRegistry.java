package dev.agentcraft.gtnh.ui.panel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Panel type registry (type id -> renderer) and data source registry (source id -> resolver from a
 * binding string to the object the renderer reads). Built-in: panels "kanban", "goal",
 * "agent-monitor"; sources "board" (a board slug or "all") and "agent" (an agent id). A later card
 * adds more panels (ops feeds, factory telemetry) by registering here and naming them in the layout
 * file; nothing else has to change.
 */
public final class PanelRegistry {

    public interface Source {

        Object resolve(String binding);
    }

    private static final Map<String, PanelRenderer> PANELS = new LinkedHashMap<>();
    private static final Map<String, Source> SOURCES = new LinkedHashMap<>();

    private PanelRegistry() {}

    public static void register(PanelRenderer r) {
        PANELS.put(r.id(), r);
    }

    public static void registerSource(String id, Source s) {
        SOURCES.put(id, s);
    }

    public static PanelRenderer get(String id) {
        return PANELS.get(id);
    }

    public static Object resolve(String source, String binding) {
        Source s = SOURCES.get(source);
        return s == null ? null : s.resolve(binding);
    }

    public static List<String> ids() {
        return new ArrayList<>(PANELS.keySet());
    }

    public static List<String> sources() {
        return new ArrayList<>(SOURCES.keySet());
    }
}
