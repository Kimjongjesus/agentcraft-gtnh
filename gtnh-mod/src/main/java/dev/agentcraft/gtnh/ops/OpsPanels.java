package dev.agentcraft.gtnh.ops;

import dev.agentcraft.gtnh.edit.PanelType;
import dev.agentcraft.gtnh.edit.PanelTypes;

/**
 * Card 5b: the four ops panel kinds, registered exactly like the card 2-4 kinds (ids = block
 * registry names), so the card 6 edit tool's palette, inspector, safety gate, presets and
 * import/export pick them up. Pure Java: the plain-Java checks register them the same way and run
 * them through the layout engine.
 */
public final class OpsPanels {

    public static final String FLEET = "fleet_board", CRON = "cron_board", USAGE = "usage_panel", ALERTS = "alert_feed";
    /** block kind -> card-4 panel renderer id */
    public static final String[][] RENDERERS = { { FLEET, "ops-fleet" }, { CRON, "ops-cron" }, { USAGE, "ops-usage" },
        { ALERTS, "ops-alerts" } };

    private OpsPanels() {}

    /** Idempotent. */
    public static void registerTypes() {
        PanelTypes.register(
            new PanelType(
                FLEET,
                "Fleet Board",
                "Ops: a tile per host or service with an up / degraded / down lamp (CPU, RAM, disk up close).",
                PanelType.OPS,
                true,
                true,
                4,
                3,
                "ops-fleet",
                true));
        PanelTypes.register(
            new PanelType(
                CRON,
                "Cron Board",
                "Ops: scheduled jobs with last and next run; failures highlighted.",
                PanelType.OPS,
                true,
                true,
                4,
                3,
                "ops-cron",
                true));
        PanelTypes.register(
            new PanelType(
                USAGE,
                "Usage Panel",
                "Ops: remaining quota per provider window, with the reset time.",
                PanelType.OPS,
                true,
                true,
                3,
                2,
                "ops-usage",
                true));
        PanelTypes.register(
            new PanelType(
                ALERTS,
                "Alert Feed",
                "Ops: a read-only scrolling list of alerts, severity coloured, open before resolved.",
                PanelType.OPS,
                true,
                true,
                4,
                3,
                "ops-alerts",
                true));
    }

    public static boolean isOpsKind(String id) {
        return FLEET.equals(id) || CRON.equals(id) || USAGE.equals(id) || ALERTS.equals(id);
    }
}
