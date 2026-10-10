package dev.agentcraft.gtnh.write.mc;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import dev.agentcraft.gtnh.CommonProxy;
import dev.agentcraft.gtnh.api.Extensions;
import dev.agentcraft.gtnh.hq.Anchor;
import dev.agentcraft.gtnh.hq.HqAnchors;
import dev.agentcraft.gtnh.hq.StationAssigner;
import dev.agentcraft.gtnh.write.core.Controller;
import dev.agentcraft.gtnh.write.core.WriteAudit;
import dev.agentcraft.gtnh.write.core.WriteLock;
import dev.agentcraft.gtnh.write.proto.Clock;
import dev.agentcraft.gtnh.write.proto.StrictJson;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Server side glue: owns the {@link Controller}, the control link, the audit log and the lock, moves
 * packets and link events onto the server thread, turns controller callbacks into packets, and answers
 * the core's hooks (/agentcraft write ..., agent NPC click, edit-tool lock). Server thread only, except
 * {@link #enqueue} (netty threads).
 */
public final class WriteRuntime implements Controller.Notifier, Controller.Presence, Extensions.ServerHooks {

    public static final int REQ = 1, CONFIRM = 2, CANCEL = 3, LOCK = 4, SYNC = 5;
    static final Logger LOG = LogManager.getLogger("AgentCraftWrite");
    private static final int QUEUE_MAX = 256, PER_PLAYER = 16;

    private static volatile WriteRuntime instance;
    private static final Queue<Object[]> WORK = new ConcurrentLinkedQueue<>();

    private MinecraftServer server;
    private Controller controller;
    private ControlLink link;
    private WriteAudit audit;
    private WriteLock lock;
    private int ticks;
    private final Map<String, String> lastState = new HashMap<>();
    private final Map<String, Long> lastSync = new HashMap<>();

    Controller controller() {
        return controller;
    }

    ControlLink link() {
        return link;
    }

    // ---- lifecycle ---------------------------------------------------------------------------------------

    public static void start(MinecraftServer server) {
        WriteRuntime rt = new WriteRuntime();
        rt.server = server;
        long startMs;
        try {
            startMs = ManagementFactory.getRuntimeMXBean()
                .getStartTime();
        } catch (RuntimeException e) {
            startMs = System.currentTimeMillis();
        }
        rt.audit = new WriteAudit(new File(WriteConfig.auditLog), 8L * 1024 * 1024, Clock.SYSTEM);
        rt.lock = new WriteLock(new File(WriteConfig.lockFile), Clock.SYSTEM);
        rt.lock.load();
        rt.link = new ControlLink(WriteConfig.controlUrl, WriteConfig.keyFile, startMs);
        Controller.Settings s = new Controller.Settings();
        s.owner = WriteConfig.owner;
        s.presenceRadius = WriteConfig.presenceRadius;
        s.lockAlsoLocksEdit = WriteConfig.lockAlsoLocksEdit;
        rt.controller = new Controller(s, Clock.SYSTEM, rt.audit, rt.lock, new McFacts(server, rt, rt.audit, rt.lock), rt.link, rt, rt);
        instance = rt;
        Extensions.server = rt;
        WORK.clear();
        if (server.isDedicatedServer() && !WriteConfig.owner.isEmpty()) {
            rt.link.start();
        } else {
            LOG.info("write module idle: {}", server.isDedicatedServer() ? "write.owner is not set" : "not a dedicated server (writes never arm here)");
        }
        rt.controller.recheck();
        LOG.info("write module: {}", rt.controller.armed() ? "ARMED" : "disarmed: " + rt.controller.disarmReason());
    }

    public static void stop() {
        WriteRuntime rt = instance;
        instance = null;
        WORK.clear();
        if (rt == null) return;
        if (Extensions.server == rt) Extensions.server = null;
        if (rt.link != null) rt.link.stop();
    }

    // ---- netty thread -------------------------------------------------------------------------------------

    public static boolean enqueue(EntityPlayerMP p, int kind, String a, String b) {
        if (p == null || instance == null || WORK.size() >= QUEUE_MAX) return false;
        int mine = 0;
        for (Object[] w : WORK) if (w[0] == p && ++mine >= PER_PLAYER) return false;
        WORK.add(new Object[] { p, Integer.valueOf(kind), a, b });
        return true;
    }

    // ---- server thread ------------------------------------------------------------------------------------

    /** Forge bus handler; static so the mod can register it once. */
    public static final class Events {

        @SubscribeEvent
        public void onTick(TickEvent.ServerTickEvent e) {
            WriteRuntime rt = instance;
            if (rt != null && e.phase == TickEvent.Phase.END) rt.tick();
        }

        @SubscribeEvent
        public void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
            WriteRuntime rt = instance;
            if (rt != null && e.player instanceof EntityPlayerMP) rt.sendState((EntityPlayerMP) e.player, true);
        }

        @SubscribeEvent
        public void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
            WriteRuntime rt = instance;
            if (rt == null || !(e.player instanceof EntityPlayerMP)) return;
            String uuid = ((EntityPlayerMP) e.player).getGameProfile()
                .getId()
                .toString();
            rt.controller.playerLeft(uuid);
            rt.lastState.remove(uuid);
            rt.lastSync.remove(uuid);
        }
    }

    private void tick() {
        try {
            ControlLink.Event ev;
            int n = 0;
            while (n++ < 64 && (ev = link.inbox.poll()) != null) {
                switch (ev.kind) {
                    case ControlLink.Event.UP:
                        controller.onLinkUp(ev.policy, ev.state);
                        break;
                    case ControlLink.Event.DOWN:
                        controller.onLinkDown(ev.detail);
                        break;
                    case ControlLink.Event.FRAME:
                        controller.onFrame(ev.msg);
                        break;
                    default:
                        controller.onBadFrame(ev.code, ev.detail);
                }
            }
            Object[] w;
            n = 0;
            while (n++ < 32 && (w = WORK.poll()) != null) handle((EntityPlayerMP) w[0], ((Integer) w[1]).intValue(), (String) w[2], (String) w[3]);
            if (++ticks % 20 == 0) controller.tick();
        } catch (RuntimeException ex) {
            LOG.error("write module tick failed", ex);
        }
    }

    private static Controller.Player toPlayer(EntityPlayerMP p) {
        return new Controller.Player(
            p.getGameProfile()
                .getId()
                .toString(),
            p.getCommandSenderName(),
            p.canCommandSenderUseCommand(2, "agentcraft"));
    }

    private void handle(EntityPlayerMP ep, int kind, String a, String b) {
        if (ep.playerNetServerHandler == null || ep.isDead) return;
        Controller.Player pl = toPlayer(ep);
        try {
            switch (kind) {
                case REQ:
                    controller.handleRequest(pl, a, b);
                    break;
                case CONFIRM:
                    controller.handleConfirm(pl, a);
                    break;
                case CANCEL:
                    controller.handleCancel(pl, a);
                    break;
                case LOCK:
                    ep.addChatMessage(new ChatComponentText("[AgentCraft] " + controller.lockBy(new Controller.Principal(pl), a)));
                    break;
                case SYNC: {
                    Long last = lastSync.get(pl.uuid);
                    long now = System.currentTimeMillis();
                    if (last == null || now - last.longValue() >= 1000L) {
                        lastSync.put(pl.uuid, Long.valueOf(now));
                        sendState(ep, true);
                    }
                    break;
                }
                default:
            }
        } catch (RuntimeException ex) {
            LOG.error("write request failed", ex);
        }
    }

    // ---- notifier ---------------------------------------------------------------------------------------------

    private EntityPlayerMP find(String uuid) {
        for (Object o : server.getConfigurationManager().playerEntityList) {
            EntityPlayerMP p = (EntityPlayerMP) o;
            if (p.getGameProfile()
                .getId()
                .toString()
                .equals(uuid)) return p;
        }
        return null;
    }

    private boolean eligible(EntityPlayerMP p) {
        return p.getGameProfile()
            .getId()
            .toString()
            .equals(WriteConfig.owner) || p.canCommandSenderUseCommand(2, "agentcraft");
    }

    void sendState(EntityPlayerMP p, boolean force) {
        if (controller == null || !eligible(p)) return;
        String uuid = p.getGameProfile()
            .getId()
            .toString();
        String json = controller.stateJson();
        if (!force && json.equals(lastState.get(uuid))) return;
        lastState.put(uuid, json);
        WriteNet.sendTo(new WriteNet.State(json), p);
    }

    @Override
    public void stateChanged() {
        if (server == null) return;
        for (Object o : server.getConfigurationManager().playerEntityList) sendState((EntityPlayerMP) o, false);
    }

    @Override
    public void prompt(String uuid, String requestId, String token, long msLeft, Map<String, String> summary) {
        EntityPlayerMP p = find(uuid);
        if (p != null) WriteNet.sendTo(new WriteNet.Prompt(requestId, token, msLeft, StrictJson.write(new LinkedHashMap<String, Object>(summary))), p);
    }

    @Override
    public void promptClosed(String uuid, String token, String why) {
        EntityPlayerMP p = find(uuid);
        if (p != null) WriteNet.sendTo(new WriteNet.PromptClosed(token, why), p);
    }

    @Override
    public void result(String uuid, String requestId, String capability, String status, String error, Map<String, String> result, String auditId, boolean dryRun) {
        EntityPlayerMP p = find(uuid);
        if (p == null) return;
        WriteNet.Result r = new WriteNet.Result();
        r.requestId = requestId;
        r.cap = capability;
        r.status = status;
        r.error = error;
        r.resultJson = StrictJson.write(new LinkedHashMap<String, Object>(result));
        r.audit = auditId;
        r.dryRun = dryRun;
        WriteNet.sendTo(r, p);
    }

    @Override
    public void chat(String uuid, String conversation, String agentId, String text, boolean fin) {
        EntityPlayerMP p = find(uuid);
        if (p != null) WriteNet.sendTo(new WriteNet.Chat(conversation, agentId, text, fin), p);
    }

    // ---- presence (tier 2) -----------------------------------------------------------------------------------------

    @Override
    public String check(Controller.Player pl, int radius) {
        EntityPlayerMP p = find(pl.uuid);
        if (p == null) return "you are not online";
        HqAnchors a = CommonProxy.sync == null ? null : CommonProxy.sync.anchors();
        if (a == null || a.isEmpty()) return "no HQ anchors are set (/agentcraft anchor set <station>)";
        if (p.dimension != a.dimension()) return "you are not in the HQ dimension";
        double best = Double.MAX_VALUE;
        for (Anchor an : a.all()
            .values()) {
            if (!StationAssigner.isStandingAnchor(an.name)) continue;
            double dx = p.posX - an.x, dy = p.posY - an.y, dz = p.posZ - an.z;
            best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        if (best == Double.MAX_VALUE) return "no HQ anchors are set";
        return best <= radius ? null : "you are " + Math.round(best) + " blocks from the nearest HQ anchor (limit " + radius + ")";
    }

    // ---- core hooks --------------------------------------------------------------------------------------------------

    @Override
    public String editLock() {
        return controller == null ? null : controller.editLockReason();
    }

    @Override
    public boolean agentClicked(EntityPlayerMP player, String agentId) {
        if (controller == null) return false;
        String uuid = player.getGameProfile()
            .getId()
            .toString();
        if (!uuid.equals(WriteConfig.owner)) return false;
        sendState(player, true);
        WriteNet.sendTo(new WriteNet.OpenChat(agentId == null ? "" : agentId), player);
        return true;
    }

    private static void say(ICommandSender s, String text) {
        s.addChatMessage(new ChatComponentText(text));
    }

    @Override
    public void command(ICommandSender sender, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "status";
        EntityPlayerMP ep = sender instanceof EntityPlayerMP ? (EntityPlayerMP) sender : null;
        Controller.Principal who = new Controller.Principal(ep == null ? null : toPlayer(ep));
        switch (sub) {
            case "status":
                for (String l : controller.statusLines()) say(sender, "[AgentCraft] " + l);
                return;
            case "verify":
                say(sender, "[AgentCraft] write path verification (docs/write-path.md 7.1), also written to the audit log:");
                for (String l : controller.verifyLines()) say(sender, l);
                return;
            case "lock": {
                StringBuilder why = new StringBuilder();
                for (int i = 1; i < args.length; i++) why.append(i > 1 ? " " : "")
                    .append(args[i]);
                say(sender, "[AgentCraft] " + controller.lockBy(who, why.toString()));
                return;
            }
            case "unlock":
                if (ep == null && !(sender instanceof MinecraftServer)) {
                    say(sender, "\u00a7c[AgentCraft] only the owner (in game) or the server console may unlock");
                    return;
                }
                say(sender, "[AgentCraft] " + controller.unlockBy(who));
                return;
            case "audit": {
                int n = 8;
                if (args.length > 1) {
                    try {
                        n = Math.max(1, Math.min(30, Integer.parseInt(args[1])));
                    } catch (NumberFormatException e) {
                        say(sender, "\u00a7c[AgentCraft] /agentcraft write audit [1..30]");
                        return;
                    }
                }
                for (String l : audit.recent(n)) say(sender, "  " + l);
                return;
            }
            default:
                say(sender, "\u00a7c[AgentCraft] /agentcraft write <status|verify|lock [reason]|unlock|audit [n]>");
        }
    }
}
