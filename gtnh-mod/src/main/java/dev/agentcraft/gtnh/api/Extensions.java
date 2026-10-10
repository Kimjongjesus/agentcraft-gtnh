package dev.agentcraft.gtnh.api;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Extension points for add-on jars. Every provider is null until an add-on installs one, and the
 * core does nothing extra while it is null: the office stays a read-only view. The core knows
 * nothing about what an add-on does with these (it has no packet, no protocol and no link of its
 * own for it); an add-on registers its providers in its own init and the core only asks.
 */
public final class Extensions {

    /** Server side hooks (dedicated or integrated server thread). */
    public static volatile ServerHooks server;
    /** Client side hooks (render thread). */
    public static volatile ClientHooks client;

    private Extensions() {}

    public interface ServerHooks {

        /** {@code /agentcraft write <args>}: {@code args} is what follows the word "write". */
        void command(ICommandSender sender, String[] args);

        /** Why the office edit tool is locked by the add-on right now, or null when it is not. */
        String editLock();

        /** A player right-clicked the NPC of agent {@code agentId}; true = the add-on handled it. */
        boolean agentClicked(EntityPlayerMP player, String agentId);
    }

    /**
     * Client hooks for the read-only screens. {@code screen} is "decisions" or "taskwall"; {@code subject}
     * is the selected {@code DecisionData.Decision} / {@code HqData.Task} (null when nothing is
     * selected). A screen reserves {@link #footerHeight} pixels under its detail pane and gives the
     * add-on that strip to draw and click in.
     */
    public interface ClientHooks {

        /** Height of the strip the add-on wants under the detail pane of {@code screen}; 0 = none. */
        float footerHeight(String screen);

        void drawFooter(String screen, Object subject, float x0, float y0, float x1, float y1, int mouseX, int mouseY);

        /** true = the click was consumed. */
        boolean footerClick(String screen, Object subject, float x0, float y0, float x1, float y1, int mouseX, int mouseY, int button);

        /** Replaces the screen's "read-only" notice with a status line; null keeps the notice. */
        String statusLine(String screen);
    }

    /** The add-on's reason the edit tool is locked, or null. Never throws. */
    public static String editLockReason() {
        ServerHooks h = server;
        if (h == null) return null;
        try {
            return h.editLock();
        } catch (RuntimeException e) {
            return "add-on lock query failed"; // fail closed
        }
    }
}
