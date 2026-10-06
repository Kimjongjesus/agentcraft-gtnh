package dev.agentcraft.gtnh.edit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of placeable panel kinds (id -> {@link PanelType}), shared by the server's safety gate
 * and the client's palette. The card 2-4 blocks are registered by {@link #registerBuiltins()};
 * later cards (ops feeds, factory telemetry, ...) call {@link #register} next to their block and
 * panel-renderer registration, and every editor screen picks them up.
 */
public final class PanelTypes {

    private static final Map<String, PanelType> TYPES = new LinkedHashMap<>();

    private PanelTypes() {}

    public static synchronized void register(PanelType t) {
        TYPES.put(t.id, t);
    }

    public static synchronized PanelType get(String id) {
        return id == null ? null : TYPES.get(id);
    }

    public static synchronized List<PanelType> all() {
        return new ArrayList<>(TYPES.values());
    }

    /** The card 2-4 kinds (ids = block registry names). Idempotent. */
    public static void registerBuiltins() {
        register(
            new PanelType(
                "task_wall",
                "Task Wall",
                "The Kanban as five columns, bound to a board or every board.",
                PanelType.BOARD,
                true,
                true,
                5,
                3,
                "kanban",
                true));
        register(
            new PanelType(
                "goal_atrium",
                "Goal Atrium",
                "Progress ring and open counts for a board.",
                PanelType.BOARD,
                true,
                true,
                3,
                3,
                "goal",
                true));
        register(
            new PanelType(
                "library",
                "Agent Library",
                "A book block; right-click opens the notes reader.",
                PanelType.BOARD,
                true,
                false,
                1,
                1,
                "",
                true));
        register(
            new PanelType(
                "monitor",
                "Desk Monitor",
                "One agent's state, activity and log tail.",
                PanelType.AGENT,
                true,
                true,
                3,
                2,
                "agent-monitor",
                true));
        register(
            new PanelType(
                "status_lamp",
                "Status Lamp",
                "Glows in one agent's status colour, or the fleet's.",
                PanelType.LAMP,
                false,
                false,
                1,
                1,
                "",
                true));
        register(
            new PanelType(
                "fleet_beacon",
                "Fleet Beacon",
                "A beam in the fleet colour with the all-boards summary.",
                PanelType.NONE,
                false,
                false,
                1,
                1,
                "",
                true));
        register(
            new PanelType(
                "overflow_sign",
                "Overflow Sign",
                "A vanilla sign you place; agents over the NPC cap are listed on it.",
                PanelType.SIGN,
                false,
                false,
                1,
                1,
                "",
                false));
    }
}
