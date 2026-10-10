package dev.agentcraft.gtnh.write.client;

import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;

import dev.agentcraft.gtnh.api.Extensions;
import dev.agentcraft.gtnh.write.mc.CommonSide;
import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

/**
 * Client only. Registers the add-on hooks the core's decision screen and task wall ask, a tick handler
 * that turns an NPC click into a call on {@link #openChatListener} on the client thread, and resets
 * {@link ClientWriteState} when the player leaves a server.
 *
 * <p>
 * For the GUI work: set {@link #delegate} to the real footer renderer (buttons under the detail pane
 * of "decisions" / "taskwall") and {@link #openChatListener} to open the chat window. Until a delegate
 * is set the core screens keep their "read-only" notice.
 */
public class ClientSide extends CommonSide {

    /** The GUI helper's footer provider; null = the core screens stay read-only. */
    public static volatile Extensions.ClientHooks delegate;
    /** Called on the client thread with the agent id when a player right-clicked an agent NPC. */
    public static volatile Consumer<String> openChatListener;

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        Extensions.client = new Extensions.ClientHooks() {

            @Override
            public float footerHeight(String screen) {
                Extensions.ClientHooks d = delegate;
                return d == null ? 0 : d.footerHeight(screen);
            }

            @Override
            public void drawFooter(String screen, Object subject, float x0, float y0, float x1, float y1, int mouseX, int mouseY) {
                Extensions.ClientHooks d = delegate;
                if (d != null) d.drawFooter(screen, subject, x0, y0, x1, y1, mouseX, mouseY);
            }

            @Override
            public boolean footerClick(String screen, Object subject, float x0, float y0, float x1, float y1, int mouseX, int mouseY, int button) {
                Extensions.ClientHooks d = delegate;
                return d != null && d.footerClick(screen, subject, x0, y0, x1, y1, mouseX, mouseY, button);
            }

            @Override
            public String statusLine(String screen) {
                return delegate == null ? null : ClientWriteState.statusLine();
            }
        };
        FMLCommonHandler.instance()
            .bus()
            .register(new Ticker());
    }

    public static final class Ticker {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            String agent = ClientWriteState.takeOpenChat();
            if (agent == null) return;
            Consumer<String> l = openChatListener;
            if (l != null) {
                l.accept(agent);
            } else if (Minecraft.getMinecraft().thePlayer != null) {
                Minecraft.getMinecraft().thePlayer
                    .addChatMessage(new ChatComponentText("[AgentCraft] chat with " + agent + ": " + ClientWriteState.statusLine() + " (the chat window is not installed yet)"));
            }
        }

        @SubscribeEvent
        public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent e) {
            ClientWriteState.reset();
        }

        @SubscribeEvent
        public void onConnect(FMLNetworkEvent.ClientConnectedToServerEvent e) {
            ClientWriteState.reset();
        }
    }
}
