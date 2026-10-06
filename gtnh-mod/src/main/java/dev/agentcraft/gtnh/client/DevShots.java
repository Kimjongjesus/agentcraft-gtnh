package dev.agentcraft.gtnh.client;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.entity.Entity;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.common.MinecraftForge;

import dev.agentcraft.gtnh.AgentCraftGTNH;
import dev.agentcraft.gtnh.entity.EntityHermesAgent;
import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Dev/QA only, inert unless JVM system properties are set (never in a normal game):
 * -Dagentcraft.dev.connect=host:port auto-joins a server from the main menu;
 * -Dagentcraft.dev.shotOnChat=1: the server console drives the camera (/agentcraft anchor tp cam_x
 * Developer) and says "devshot NAME" -> a screenshot devshot-NAME.png 2 s later; "devquit" quits;
 * -Dagentcraft.dev.shots=N (card 1 mode): N screenshots of the nearest agent NPC, one every
 * -Dagentcraft.dev.shotEvery=ticks (default 400), then quits the client.
 */
public final class DevShots {

    private static final Pattern SHOT = Pattern.compile("devshot ([A-Za-z0-9_-]{1,40})");
    /** devgui taskwall|library|close [binding|-] [select]: open a card-3 screen for a screenshot. */
    private static final Pattern GUI = Pattern.compile("devgui (taskwall|library|editor|inspector|close)(?: (\\S+))?(?: (\\S+))?");
    private volatile String[] pendingGui;
    /** card 6: devact ACTION k=v ... sends an edit-tool request as this player; devhud on|off shows the HUD in shots. */
    private static final Pattern ACT = Pattern.compile("devact (\\S+)((?: [A-Za-z]+=\\S+)*)");
    private static final Pattern HUD = Pattern.compile("devhud (on|off)");
    private volatile boolean showHud;

    private final String connect = System.getProperty("agentcraft.dev.connect", "");
    private final boolean onChat = System.getProperty("agentcraft.dev.shotOnChat") != null;
    private final int shots = Integer.getInteger("agentcraft.dev.shots", 0);
    private final int every = Integer.getInteger("agentcraft.dev.shotEvery", 400);
    private boolean connected, wantConnect;
    private int ticksInWorld;
    private int taken;
    private volatile String pendingShot;
    private volatile boolean pendingQuit;
    private int pendingDelay;

    public static void registerIfRequested() {
        if (System.getProperty("agentcraft.dev.connect") == null && System.getProperty("agentcraft.dev.shots") == null
            && System.getProperty("agentcraft.dev.shotOnChat") == null) {
            return;
        }
        DevShots d = new DevShots();
        MinecraftForge.EVENT_BUS.register(d);
        FMLCommonHandler.instance()
            .bus()
            .register(d);
        AgentCraftGTNH.LOG
            .info("DevShots active: connect={} onChat={} shots={} every={} ticks", d.connect, d.onChat, d.shots, d.every);
    }

    @SubscribeEvent
    public void onGui(GuiOpenEvent e) {
        if (!connected && !connect.isEmpty() && e.gui instanceof GuiMainMenu) {
            wantConnect = true; // connect from the next client tick, through FML's own path
        }
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent e) {
        if (!onChat || e.message == null) return;
        String text = e.message.getUnformattedText();
        Matcher m = SHOT.matcher(text);
        Matcher g = GUI.matcher(text);
        Matcher act = ACT.matcher(text);
        Matcher hud = HUD.matcher(text);
        if (text.contains("devshot ") || text.contains("devgui ") || text.contains("devact ") || text.contains("devhud ")) e.setCanceled(true); // keep QA control lines out of the shots
        if (act.find() && text.startsWith("[Server] ")) { // dev client only, and only from the console's say, never a player's chat line
            java.util.List<Object> kv = new java.util.ArrayList<>();
            kv.add("a");
            kv.add(act.group(1));
            for (String pair : act.group(2)
                .trim()
                .split(" ")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    kv.add(pair.substring(0, eq));
                    kv.add(pair.substring(eq + 1));
                }
            }
            if ("applylast".equals(act.group(1))) {
                java.util.Map<String, Object> r = dev.agentcraft.gtnh.client.edit.ClientEdit.result;
                java.util.Map<String, Object> plan = r == null ? null : dev.agentcraft.gtnh.edit.Json.obj(r, "plan");
                if (plan != null) dev.agentcraft.gtnh.client.edit.ClientEdit.send("a", "plan.apply", "token", dev.agentcraft.gtnh.edit.Json.str(plan, "token", ""));
            } else {
                dev.agentcraft.gtnh.client.edit.ClientEdit.send(kv.toArray());
            }
            AgentCraftGTNH.LOG.info("DevShots: devact {}", act.group(0));
            return;
        }
        if (hud.find()) {
            showHud = "on".equals(hud.group(1));
            return;
        }
        if (m.find()) {
            pendingShot = m.group(1);
            pendingDelay = 40;
        } else if (g.find()) {
            pendingGui = new String[] { g.group(1), g.group(2) == null || "-".equals(g.group(2)) ? "" : g.group(2),
                g.group(3) == null ? "" : g.group(3) };
        } else if (text.contains("devquit")) {
            pendingQuit = true;
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (wantConnect && !connected && mc.currentScreen instanceof GuiMainMenu) {
            connected = true;
            wantConnect = false;
            mc.gameSettings.showInventoryAchievementHint = false;
            if (mc.displayWidth < 1000 && !mc.isFullScreen()) {
                mc.toggleFullscreen(); // fills the (virtual) monitor, e.g. 1280x720
            }
            String[] hp = connect.split(":");
            // FML's own --server path: pings first (fills its server data), then connects
            FMLClientHandler.instance()
                .connectToServerAtStartup(hp[0], hp.length > 1 ? Integer.parseInt(hp[1]) : 25565);
            return;
        }
        if (mc.theWorld == null || mc.thePlayer == null) {
            ticksInWorld = 0;
            return;
        }
        ticksInWorld++;
        if (onChat) {
            mc.gameSettings.hideGUI = !showHud;
            String[] gui = pendingGui;
            if (gui != null) {
                pendingGui = null;
                if ("close".equals(gui[0])) {
                    mc.displayGuiScreen(null);
                } else if ("editor".equals(gui[0])) {
                    // card 6: devgui editor palette|anchors|layouts [anchor name | action like diff:NAME]
                    int tab = "anchors".equals(gui[1]) ? 1 : "layouts".equals(gui[1]) ? 2 : 0;
                    String arg = gui[2].isEmpty() ? null : gui[2];
                    mc.displayGuiScreen(new dev.agentcraft.gtnh.client.edit.GuiEditor(tab, tab == 1 ? arg : null, null, tab == 2 ? arg : null));
                } else if ("inspector".equals(gui[0])) {
                    // card 6: devgui inspector x,y,z [binding:w:h]
                    String[] p = gui[1].split(",");
                    dev.agentcraft.gtnh.client.edit.GuiPanelInspector gi = new dev.agentcraft.gtnh.client.edit.GuiPanelInspector(
                        Integer.parseInt(p[0]),
                        Integer.parseInt(p[1]),
                        Integer.parseInt(p[2]));
                    mc.displayGuiScreen(gi);
                    if (!gui[2].isEmpty()) gi.devPreset(gui[2]);
                } else if ("library".equals(gui[0])) {
                    // select "q=<word>": open with that search text instead of selecting a note
                    boolean q = gui[2].startsWith("q=");
                    GuiLibrary lib = new GuiLibrary(gui[1], q ? gui[2].substring(2) : "");
                    mc.displayGuiScreen(lib);
                    if (!q && !gui[2].isEmpty()) lib.selectFirstMatching(gui[2]);
                } else {
                    mc.displayGuiScreen(new GuiTaskWall(gui[1], gui[2].isEmpty() ? null : gui[2]));
                }
                AgentCraftGTNH.LOG.info("DevShots: devgui {} '{}' '{}'", gui[0], gui[1], gui[2]);
            }
            if (pendingShot != null && --pendingDelay <= 0) {
                String name = pendingShot;
                pendingShot = null;
                IChatComponent msg = ScreenShotHelper
                    .saveScreenshot(mc.mcDataDir, "devshot-" + name + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
                AgentCraftGTNH.LOG.info("DevShots {}: {}", name, msg.getUnformattedText());
            }
            if (pendingQuit && pendingShot == null) {
                AgentCraftGTNH.LOG.info("DevShots: devquit, quitting");
                mc.shutdown();
            }
            return;
        }
        if (shots <= 0) return;
        Entity target = null;
        double best = 64 * 64;
        for (Object o : mc.theWorld.loadedEntityList) {
            if (o instanceof EntityHermesAgent) {
                double d = ((Entity) o).getDistanceSqToEntity(mc.thePlayer);
                if (d < best) {
                    best = d;
                    target = (Entity) o;
                }
            }
        }
        if (target != null) {
            double dx = target.posX - mc.thePlayer.posX;
            double dz = target.posZ - mc.thePlayer.posZ;
            double flat = Math.sqrt(dx * dx + dz * dz);
            // walk up to ~4.5 blocks in small client-side steps (the server accepts < 10 blocks/tick)
            if (flat > 4.5) {
                double step = Math.min(0.8, flat - 4.5);
                mc.thePlayer.setPosition(
                    mc.thePlayer.posX + dx / flat * step,
                    mc.thePlayer.posY,
                    mc.thePlayer.posZ + dz / flat * step);
                dx = target.posX - mc.thePlayer.posX;
                dz = target.posZ - mc.thePlayer.posZ;
            }
            double dy = (target.posY + 2.0) - (mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
            float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
            float pitch = (float) -(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI);
            mc.thePlayer.rotationYaw = mc.thePlayer.prevRotationYaw = yaw;
            mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = pitch;
            mc.thePlayer.rotationYawHead = mc.thePlayer.prevRotationYawHead = yaw;
        }
        mc.gameSettings.hideGUI = true;
        if (ticksInWorld >= 200 && (ticksInWorld - 200) % every == 0 && taken < shots) {
            taken++;
            IChatComponent msg = ScreenShotHelper
                .saveScreenshot(mc.mcDataDir, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            AgentCraftGTNH.LOG.info(
                "DevShots {}/{}: {} (npc {} at distance {})",
                taken,
                shots,
                msg.getUnformattedText(),
                target == null ? "none" : target.getEntityId(),
                target == null ? -1 : Math.sqrt(best));
        }
        if (taken >= shots && ticksInWorld > 200 + every * shots) {
            AgentCraftGTNH.LOG.info("DevShots done, quitting");
            mc.shutdown();
        }
    }
}
