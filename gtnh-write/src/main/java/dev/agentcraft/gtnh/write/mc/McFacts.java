package dev.agentcraft.gtnh.write.mc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ServerConfigurationManager;

import dev.agentcraft.gtnh.write.core.Controller;
import dev.agentcraft.gtnh.write.core.GateFacts;
import dev.agentcraft.gtnh.write.core.WriteAudit;
import dev.agentcraft.gtnh.write.core.WriteLock;
import dev.agentcraft.gtnh.write.proto.Msg;

/** The live server as {@link GateFacts}. The dedicated-only lists are read only after {@code dedicated()} is true (the gate guarantees it). */
final class McFacts implements GateFacts {

    static final String OVERRIDE_PROPERTY = "agentcraft.write.devOverride";

    private final MinecraftServer server;
    private final WriteRuntime rt;
    private final WriteAudit audit;
    private final WriteLock lock;

    McFacts(MinecraftServer server, WriteRuntime rt, WriteAudit audit, WriteLock lock) {
        this.server = server;
        this.rt = rt;
        this.audit = audit;
        this.lock = lock;
    }

    private Controller ctl() {
        return rt.controller();
    }

    @Override
    public boolean dedicated() {
        return server.isDedicatedServer();
    }

    @Override
    public boolean onlineMode() {
        return server.isServerInOnlineMode();
    }

    @Override
    public boolean whitelistEnforced() {
        return server.getConfigurationManager()
            .isWhiteListEnabled();
    }

    /**
     * The UUIDs of a whitelist / ops list. The lists' own key listing (func_152685_a) returns player NAMES, which
     * would let a second entry with the owner's name and another UUID look like the owner, so the entries are read
     * with their profiles. If the entries cannot be read the result holds a marker that never equals a UUID
     * (the gate then fails closed: the list is "not the owner").
     */
    @SuppressWarnings("unchecked")
    private static List<String> uuids(net.minecraft.server.management.UserList list) {
        List<String> out = new ArrayList<>();
        try {
            java.lang.reflect.Method m = net.minecraft.server.management.UserList.class.getDeclaredMethod("func_152688_e");
            m.setAccessible(true);
            java.util.Map<String, net.minecraft.server.management.UserListEntry> map = (java.util.Map<String, net.minecraft.server.management.UserListEntry>) m.invoke(list);
            java.lang.reflect.Method value = net.minecraft.server.management.UserListEntry.class.getDeclaredMethod("func_152640_f");
            value.setAccessible(true);
            for (net.minecraft.server.management.UserListEntry e : map.values()) {
                Object v = value.invoke(e);
                java.util.UUID id = v instanceof com.mojang.authlib.GameProfile ? ((com.mojang.authlib.GameProfile) v).getId() : null;
                out.add(id == null ? "?entry-without-uuid" : id.toString().toLowerCase(Locale.ROOT));
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            out.add("?list-unreadable");
            out.add("?list-unreadable");
        }
        return out;
    }

    @Override
    public List<String> whitelist() {
        ServerConfigurationManager cm = server.getConfigurationManager();
        return uuids(cm.func_152599_k());
    }

    @Override
    public List<String> ops() {
        ServerConfigurationManager cm = server.getConfigurationManager();
        return uuids(cm.func_152603_m());
    }

    @Override
    public String owner() {
        return WriteConfig.owner;
    }

    @Override
    public String serverIp() {
        String h = server.isDedicatedServer() ? server.getServerHostname() : "";
        return h == null ? "" : h;
    }

    @Override
    public String controlHost() {
        return ControlLink.hostOf(WriteConfig.controlUrl);
    }

    @Override
    public String devOverrideProperty() {
        return System.getProperty(OVERRIDE_PROPERTY);
    }

    @Override
    public boolean linkReady() {
        ControlLink l = rt.link();
        Controller c = ctl();
        return l != null && l.ready() && c != null && c.policy() != null && c.hermesState() != null;
    }

    @Override
    public String linkNote() {
        if (!server.isDedicatedServer()) return "not started: not a dedicated server";
        if (WriteConfig.owner.isEmpty()) return "not started: write.owner is not set";
        ControlLink l = rt.link();
        return l == null ? "not started" : l.note();
    }

    @Override
    public List<String> policyActors() {
        Controller c = ctl();
        Msg.Policy p = c == null ? null : c.policy();
        return p == null ? null : p.actors;
    }

    @Override
    public boolean policyDryRun() {
        Controller c = ctl();
        Msg.Policy p = c == null ? null : c.policy();
        return p != null && p.dryRun;
    }

    @Override
    public boolean hermesArmed() {
        Controller c = ctl();
        Msg.State s = c == null ? null : c.hermesState();
        return s != null && s.armed;
    }

    @Override
    public boolean hermesLocked() {
        Controller c = ctl();
        Msg.State s = c == null ? null : c.hermesState();
        return s == null || s.locked;
    }

    @Override
    public String hermesLockReason() {
        Controller c = ctl();
        Msg.State s = c == null ? null : c.hermesState();
        return s == null ? "no state" : s.lockReason;
    }

    @Override
    public boolean gameLocked() {
        return lock.isLocked();
    }

    @Override
    public String gameLockInfo() {
        return lock.info();
    }

    @Override
    public boolean auditWritable() {
        return audit.writable();
    }

    @Override
    public String auditError() {
        return audit.lastError();
    }

    @Override
    public boolean clockFault() {
        ControlLink l = rt.link();
        return l != null && l.clockFault();
    }

    @Override
    public boolean overrideTainted() {
        Controller c = ctl();
        return c != null && c.overrideTainted();
    }
}
