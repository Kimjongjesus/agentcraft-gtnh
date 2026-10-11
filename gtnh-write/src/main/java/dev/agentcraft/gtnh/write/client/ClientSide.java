package dev.agentcraft.gtnh.write.client;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ChatComponentText;

import dev.agentcraft.gtnh.api.Extensions;
import dev.agentcraft.gtnh.client.BoardView;
import dev.agentcraft.gtnh.write.mc.CommonSide;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

/**
 * Client only. Installs the write screens: the footer strips of the core's decision screen and task wall
 * ({@link WriteFooter}), the chat window opened by clicking an agent NPC, and a tick handler that opens the
 * Confirm screen when a dispatch prompt arrives, prints /ask answers and results nobody is looking at into
 * the chat, and resets {@link ClientWriteState} when the player leaves a server.
 *
 * <p>
 * {@link #delegate} and {@link #openChatListener} stay public so a different GUI could replace them. Without
 * this module's jar the core screens keep their read-only notice (the core only asks hooks this class sets).
 */
public class ClientSide extends CommonSide {

    /** The footer provider; null = the core screens stay read-only. */
    public static volatile Extensions.ClientHooks delegate;
    /** Called on the client thread with the agent id when a player right-clicked an agent NPC. */
    public static volatile Consumer<String> openChatListener;

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        delegate = new WriteFooter();
        openChatListener = new Consumer<String>() {

            @Override
            public void accept(String agent) {
                Minecraft mc = Minecraft.getMinecraft();
                mc.displayGuiScreen(new GuiChatWindow(agent, mc.currentScreen));
            }
        };
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
            public boolean footerKey(String screen, Object subject, char ch, int key) {
                Extensions.ClientHooks d = delegate;
                return d != null && d.footerKey(screen, subject, ch, key);
            }

            @Override
            public String statusLine(String screen) {
                Extensions.ClientHooks d = delegate;
                return d == null ? null : d.statusLine(screen);
            }
        };
        FMLCommonHandler.instance()
            .bus()
            .register(new Ticker());
        WriteDevAuto.registerIfRequested();
    }

    public static final class Ticker {

        private String shownToken = "";
        private long printedChat = ClientWriteState.chatSeq.get(), printedResults = ClientWriteState.resultSeq.get();

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.thePlayer == null || mc.theWorld == null) return;
            // 1. the Confirm screen, for the requesting player only (the server sends the prompt to nobody else)
            ClientWriteState.PromptInfo p = ClientWriteState.pendingPrompt();
            if (p != null && !p.token.equals(shownToken) && ClientWriteState.canWrite() && !(mc.currentScreen instanceof GuiDispatchConfirm)) {
                shownToken = p.token;
                GuiScreen cur = mc.currentScreen;
                if (cur instanceof GuiDispatchPick) cur = ((GuiDispatchPick) cur).parent;
                mc.displayGuiScreen(new GuiDispatchConfirm(p, cur));
            }
            // 2. the NPC click (the server asked for a chat window)
            String agent = ClientWriteState.takeOpenChat();
            if (agent != null) {
                Consumer<String> l = openChatListener;
                if (l != null) l.accept(agent);
                else mc.thePlayer.addChatMessage(new ChatComponentText("[AgentCraft] chat with " + agent + ": " + ClientWriteState.statusLine()));
            }
            // 3. answers and results that no screen is showing go to the Minecraft chat
            long cs = ClientWriteState.chatSeq.get();
            if (cs > printedChat) {
                int n = (int) Math.min(cs - printedChat, 50);
                List<ClientWriteState.ChatLine> l = ClientWriteState.recentChat(n);
                Collections.reverse(l);
                printedChat = cs;
                if (!(mc.currentScreen instanceof GuiChatWindow)) {
                    for (ClientWriteState.ChatLine c : l) say(mc, "\u00a7b" + BoardView.agentName(c.agentId) + "\u00a7r: " + c.text);
                }
            }
            long rs = ClientWriteState.resultSeq.get();
            if (rs > printedResults) {
                int n = (int) Math.min(rs - printedResults, 16);
                List<ClientWriteState.ResultInfo> l = ClientWriteState.recent(n);
                Collections.reverse(l);
                printedResults = rs;
                for (ClientWriteState.ResultInfo r : l) {
                    boolean mine = "agent.ask".equals(r.capability) || "agent.chat".equals(r.capability);
                    if ((mine || mc.currentScreen == null) && !"prompted".equals(r.status)) {
                        say(mc, (r.isFailure() ? "\u00a7c" : "\u00a7a") + WUi.label(r.capability) + ": " + FormLogic.resultLine(r.status, r.error, r.dryRun));
                    }
                }
            }
        }

        private static void say(Minecraft mc, String text) {
            mc.thePlayer.addChatMessage(new ChatComponentText("[AgentCraft] " + text));
        }

        @SubscribeEvent
        public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent e) {
            ClientWriteState.reset();
            GuiChatWindow.clearHistory();
            shownToken = "";
        }

        @SubscribeEvent
        public void onConnect(FMLNetworkEvent.ClientConnectedToServerEvent e) {
            ClientWriteState.reset();
            printedChat = ClientWriteState.chatSeq.get();
            printedResults = ClientWriteState.resultSeq.get();
        }
    }
}
