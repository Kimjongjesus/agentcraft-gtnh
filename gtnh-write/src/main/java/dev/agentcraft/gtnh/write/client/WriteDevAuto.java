package dev.agentcraft.gtnh.write.client;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Keyboard;

import dev.agentcraft.gtnh.write.AgentCraftWrite;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Dev/QA automation, inert unless the JVM property {@code agentcraft.dev.writeAuto} is set (never in a normal
 * game). It lets a QA run press the same buttons a player presses, from the server console, so screenshots
 * can be taken without a human: {@code say devwrite click NAME} presses the button or text box of that name
 * on the current screen (it calls the screen's own mouseClicked at the centre of the button, so the real click
 * path runs), {@code devwrite type TEXT} types into the focused box ('_' is a space), {@code devwrite key
 * enter|esc|tab|back}, {@code devwrite chat AGENT} opens the chat window, {@code devwrite actions} opens the
 * Write actions screen, {@code devwrite dump} logs the buttons of the last frame. Only lines that come from
 * the server console's {@code say} (they start with "[Server] ") are obeyed. Together with the core's
 * agentcraft.dev.connect / shotOnChat properties (devgui, devshot).
 */
public final class WriteDevAuto {

    private static final Pattern CMD = Pattern.compile("devwrite (\\S+)(?: (.*))?");
    private final ConcurrentLinkedQueue<String[]> queue = new ConcurrentLinkedQueue<String[]>();

    private WriteDevAuto() {}

    static void registerIfRequested() {
        if (!WUi.DEV) return;
        WriteDevAuto d = new WriteDevAuto();
        MinecraftForge.EVENT_BUS.register(d);
        FMLCommonHandler.instance()
            .bus()
            .register(d);
        AgentCraftWrite.LOG.info("WriteDevAuto active (agentcraft.dev.writeAuto): the server console can press buttons");
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent e) {
        if (e.message == null) return;
        String text = e.message.getUnformattedText();
        if (!text.contains("devwrite ")) return;
        e.setCanceled(true); // keep QA control lines out of the shots
        if (!text.startsWith("[Server] ")) return;
        Matcher m = CMD.matcher(text);
        if (m.find()) queue.add(new String[] { m.group(1), m.group(2) == null ? "" : m.group(2) });
    }

    @SubscribeEvent
    public void onRender(TickEvent.RenderTickEvent e) {
        if (e.phase == TickEvent.Phase.START) WUi.HITS.clear(); // only buttons drawn in the newest frame count
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        String[] c = queue.poll();
        if (c == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            run(mc, c[0], c[1]);
        } catch (Exception ex) {
            AgentCraftWrite.LOG.warn("WriteDevAuto {} {} failed: {}", c[0], c[1], ex.toString());
        }
    }

    private void run(Minecraft mc, String cmd, String arg) throws Exception {
        GuiScreen s = mc.currentScreen;
        switch (cmd) {
            case "click": {
                float[] r = WUi.HITS.get(arg.trim().replace('_', ' '));
                if (s == null || r == null) {
                    AgentCraftWrite.LOG.warn("WriteDevAuto click '{}': {}", arg, s == null ? "no screen" : "no such button in the last frame (" + WUi.HITS.keySet() + ")");
                    return;
                }
                int x = Math.round((r[0] + r[2]) / 2), y = Math.round((r[1] + r[3]) / 2);
                invoke(s, new String[] { "mouseClicked", "func_73864_a" }, new Class<?>[] { int.class, int.class, int.class }, x, y, 0);
                AgentCraftWrite.LOG.info("WriteDevAuto: clicked {} at {},{} on {}", arg, x, y, s.getClass().getSimpleName());
                return;
            }
            case "type": {
                if (s == null) return;
                for (char ch : arg.replace('_', ' ').toCharArray()) {
                    invoke(s, new String[] { "keyTyped", "func_73869_a" }, new Class<?>[] { char.class, int.class }, ch, 0);
                }
                AgentCraftWrite.LOG.info("WriteDevAuto: typed {} chars on {}", arg.length(), s.getClass().getSimpleName());
                return;
            }
            case "key": {
                if (s == null) return;
                int k = "enter".equals(arg) ? Keyboard.KEY_RETURN : "esc".equals(arg) ? Keyboard.KEY_ESCAPE : "tab".equals(arg) ? Keyboard.KEY_TAB : "back".equals(arg) ? Keyboard.KEY_BACK : 0;
                char ch = "enter".equals(arg) ? '\r' : 0;
                invoke(s, new String[] { "keyTyped", "func_73869_a" }, new Class<?>[] { char.class, int.class }, ch, k);
                AgentCraftWrite.LOG.info("WriteDevAuto: key {} on {}", arg, s.getClass().getSimpleName());
                return;
            }
            case "chat":
                mc.displayGuiScreen(new GuiChatWindow(arg.trim(), mc.currentScreen));
                return;
            case "cmd": // a chat line / command typed as the player, e.g. "/ask helper-a hello"
                mc.thePlayer.sendChatMessage(arg);
                AgentCraftWrite.LOG.info("WriteDevAuto: sent '{}' as the player", arg);
                return;
            case "raw": { // a request the way a modified client would send it: no local armed/lock check (the server must refuse it)
                String[] parts = arg.trim().split(" ");
                java.util.Map<String, Object> a = new java.util.LinkedHashMap<String, Object>();
                for (int i = 1; i < parts.length; i++) {
                    int eq = parts[i].indexOf('=');
                    if (eq > 0) a.put(parts[i].substring(0, eq), parts[i].substring(eq + 1).replace('_', ' '));
                }
                dev.agentcraft.gtnh.write.mc.WriteNet.CHANNEL.sendToServer(new dev.agentcraft.gtnh.write.mc.WriteNet.Req(parts[0], dev.agentcraft.gtnh.write.proto.StrictJson.write(a)));
                AgentCraftWrite.LOG.info("WriteDevAuto: raw request {} {}", parts[0], a.keySet());
                return;
            }
            case "actions":
                mc.displayGuiScreen(new GuiWriteActions(mc.currentScreen));
                return;
            case "sync":
                WriteClient.sync();
                return;
            case "dump": {
                StringBuilder b = new StringBuilder();
                for (Map.Entry<String, float[]> en : WUi.HITS.entrySet()) b.append(en.getKey())
                    .append(' ');
                AgentCraftWrite.LOG.info(
                    "WriteDevAuto dump: screen={} state=[{}] prompt={} buttons: {}",
                    s == null ? "none" : s.getClass().getSimpleName(),
                    ClientWriteState.statusLine(),
                    ClientWriteState.pendingPrompt() != null,
                    b);
                return;
            }
            default:
                AgentCraftWrite.LOG.warn("WriteDevAuto: unknown command {}", cmd);
        }
    }

    private static void invoke(GuiScreen s, String[] names, Class<?>[] types, Object... args) throws Exception {
        for (Class<?> c = s.getClass(); c != null; c = c.getSuperclass()) {
            for (String n : names) {
                try {
                    Method m = c.getDeclaredMethod(n, types);
                    m.setAccessible(true);
                    m.invoke(s, args);
                    return;
                } catch (NoSuchMethodException ignore) {
                    // try the next name / superclass
                }
            }
        }
        throw new NoSuchMethodException(names[0] + " on " + s.getClass());
    }
}
