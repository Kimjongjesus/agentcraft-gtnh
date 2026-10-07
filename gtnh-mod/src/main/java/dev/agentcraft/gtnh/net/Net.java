package dev.agentcraft.gtnh.net;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;

import dev.agentcraft.gtnh.AgentCraftGTNH;
import dev.agentcraft.gtnh.state.AgentInfo;
import dev.agentcraft.gtnh.state.ClientAgentCache;
import dev.agentcraft.gtnh.state.LogLine;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * Server -> client replication over a SimpleNetworkWrapper channel. Nothing flows client -> server.
 * AgentSync: every agent (replace semantics) + fleet summary; LogSync: one agent's monitor tail,
 * sent only when it changed; AnchorOverlay: anchors for the op debug overlay. Card 6 adds the one
 * client -> server message, {@link EditCmd} (an edit-tool request, JSON, re-validated by the server:
 * op level, lock, rate limit, the layout engine's safety rules), and its answer {@link EditView}.
 */
public final class Net {

    public static final int MAX_AGENTS = 64;
    public static final int MAX_LINES = 24;
    /** Card 3 blobs (board, library): total cap and bytes per packet (well under the 32 KiB payload limit). */
    public static final int MAX_BLOB = 512 * 1024;
    public static final int BLOB_PART = 30000;

    public static SimpleNetworkWrapper CHANNEL;
    public static long sentPackets, sentLogPackets, sentBlobPackets;

    private Net() {}

    public static void init() {
        CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel(AgentCraftGTNH.MODID);
        CHANNEL.registerMessage(AgentSyncHandler.class, AgentSync.class, 0, Side.CLIENT);
        CHANNEL.registerMessage(LogSyncHandler.class, LogSync.class, 1, Side.CLIENT);
        CHANNEL.registerMessage(AnchorOverlayHandler.class, AnchorOverlay.class, 2, Side.CLIENT);
        CHANNEL.registerMessage(BlobHandler.class, Blob.class, 3, Side.CLIENT);
        // card 6 (office edit tool): the only client -> server message; the server re-checks everything
        CHANNEL.registerMessage(EditCmdHandler.class, EditCmd.class, 4, Side.SERVER);
        CHANNEL.registerMessage(EditViewHandler.class, EditView.class, 5, Side.CLIENT);
        CHANNEL.registerMessage(DisplayHandler.class, Display.class, 6, Side.CLIENT);
        // card 5b: /agentcraft toast mute|unmute|test|status -> the player's own client
        CHANNEL.registerMessage(ToastCtlHandler.class, ToastCtl.class, 7, Side.CLIENT);
    }

    /** Server -> one client: a toast control from /agentcraft toast (the client keeps the setting). */
    public static final class ToastCtl implements IMessage {

        public String action = "";

        public ToastCtl() {}

        public ToastCtl(String action) {
            this.action = action;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            action = clip(ByteBufUtils.readUTF8String(buf), 16);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            ByteBufUtils.writeUTF8String(buf, clip(action, 16));
        }
    }

    public static final class ToastCtlHandler implements IMessageHandler<ToastCtl, IMessage> {

        @Override
        public IMessage onMessage(ToastCtl msg, MessageContext ctx) {
            dev.agentcraft.gtnh.AgentCraftGTNH.proxy.toastControl(msg.action);
            return null;
        }
    }

    public static void sendTo(IMessage msg, EntityPlayerMP player) {
        CHANNEL.sendTo(msg, player);
        count(msg);
    }

    public static void sendToAll(IMessage msg) {
        CHANNEL.sendToAll(msg);
        count(msg);
    }

    private static void count(IMessage msg) {
        if (msg instanceof LogSync) sentLogPackets++;
        else if (msg instanceof Blob) sentBlobPackets++;
        else sentPackets++;
    }

    private static String clip(String s, int n) {
        return s == null ? "" : s.length() > n ? s.substring(0, n) : s;
    }

    /** Every agent (entityId -1 = no NPC: over the cap), fleet summary, overflow count. */
    public static final class AgentSync implements IMessage {

        public final List<AgentInfo> agents = new ArrayList<>();
        public final List<Integer> entityIds = new ArrayList<>();
        public boolean linkUp;
        public String fleet = "idle";
        public int overflow;

        public AgentSync() {}

        @Override
        public void fromBytes(ByteBuf buf) {
            linkUp = buf.readBoolean();
            fleet = ByteBufUtils.readUTF8String(buf);
            overflow = buf.readUnsignedByte();
            int n = Math.min(buf.readUnsignedByte(), MAX_AGENTS);
            for (int i = 0; i < n; i++) {
                AgentInfo a = new AgentInfo();
                entityIds.add(buf.readInt());
                a.id = ByteBufUtils.readUTF8String(buf);
                a.name = ByteBufUtils.readUTF8String(buf);
                a.title = ByteBufUtils.readUTF8String(buf);
                a.role = ByteBufUtils.readUTF8String(buf);
                a.state = ByteBufUtils.readUTF8String(buf);
                a.activity = ByteBufUtils.readUTF8String(buf);
                a.station = ByteBufUtils.readUTF8String(buf);
                a.taskId = ByteBufUtils.readUTF8String(buf);
                a.color = buf.readInt();
                a.active = buf.readBoolean();
                a.waiting = buf.readBoolean();
                agents.add(a);
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeBoolean(linkUp);
            ByteBufUtils.writeUTF8String(buf, clip(fleet, 16));
            buf.writeByte(Math.min(overflow, 255));
            int n = Math.min(agents.size(), MAX_AGENTS);
            buf.writeByte(n);
            for (int i = 0; i < n; i++) {
                AgentInfo a = agents.get(i);
                buf.writeInt(entityIds.get(i));
                ByteBufUtils.writeUTF8String(buf, clip(a.id, 64));
                ByteBufUtils.writeUTF8String(buf, clip(a.name, 64));
                ByteBufUtils.writeUTF8String(buf, clip(a.title, 64));
                ByteBufUtils.writeUTF8String(buf, clip(a.role, 16));
                ByteBufUtils.writeUTF8String(buf, clip(a.state, 32));
                ByteBufUtils.writeUTF8String(buf, clip(a.activity, 64));
                ByteBufUtils.writeUTF8String(buf, clip(a.station, 32));
                ByteBufUtils.writeUTF8String(buf, clip(a.taskId, 64));
                buf.writeInt(a.color);
                buf.writeBoolean(a.active);
                buf.writeBoolean(a.waiting);
            }
        }
    }

    public static final class AgentSyncHandler implements IMessageHandler<AgentSync, IMessage> {

        @Override
        public IMessage onMessage(AgentSync msg, MessageContext ctx) {
            // runs on the netty thread in 1.7.10; the cache uses concurrent maps, read by the renderers
            ClientAgentCache.replace(msg.agents, msg.entityIds, msg.linkUp, msg.fleet, msg.overflow);
            return null;
        }
    }

    /** One agent's monitor tail (already filtered by the adapter; replace semantics). */
    public static final class LogSync implements IMessage {

        public String agentId = "";
        public final List<LogLine> lines = new ArrayList<>();

        public LogSync() {}

        @Override
        public void fromBytes(ByteBuf buf) {
            agentId = ByteBufUtils.readUTF8String(buf);
            int n = Math.min(buf.readUnsignedByte(), MAX_LINES);
            for (int i = 0; i < n; i++) {
                long ts = buf.readLong();
                String kind = ByteBufUtils.readUTF8String(buf);
                String text = ByteBufUtils.readUTF8String(buf);
                lines.add(new LogLine(ts, kind, text));
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            ByteBufUtils.writeUTF8String(buf, clip(agentId, 64));
            int n = Math.min(lines.size(), MAX_LINES);
            buf.writeByte(n);
            for (int i = lines.size() - n; i < lines.size(); i++) {
                LogLine l = lines.get(i);
                buf.writeLong(l.ts);
                ByteBufUtils.writeUTF8String(buf, clip(l.kind, 16));
                ByteBufUtils.writeUTF8String(buf, clip(l.text, LogLine.MAX_TEXT));
            }
        }
    }

    public static final class LogSyncHandler implements IMessageHandler<LogSync, IMessage> {

        @Override
        public IMessage onMessage(LogSync msg, MessageContext ctx) {
            ClientAgentCache.putLogs(msg.agentId, msg.lines);
            return null;
        }
    }

    /** Anchors + missing stations for the op debug overlay (shown for a limited time). */
    public static final class AnchorOverlay implements IMessage {

        public int seconds;
        public final List<String> names = new ArrayList<>();
        public final List<double[]> spots = new ArrayList<>(); // x, y, z, yaw
        public final List<String> missing = new ArrayList<>();

        public AnchorOverlay() {}

        @Override
        public void fromBytes(ByteBuf buf) {
            seconds = buf.readShort();
            int n = Math.min(buf.readUnsignedShort(), 512);
            for (int i = 0; i < n; i++) {
                names.add(ByteBufUtils.readUTF8String(buf));
                spots.add(new double[] { buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat() });
            }
            int m = Math.min(buf.readUnsignedByte(), 32);
            for (int i = 0; i < m; i++) missing.add(ByteBufUtils.readUTF8String(buf));
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeShort(seconds);
            int n = Math.min(names.size(), 512);
            buf.writeShort(n);
            for (int i = 0; i < n; i++) {
                ByteBufUtils.writeUTF8String(buf, clip(names.get(i), 64));
                double[] s = spots.get(i);
                buf.writeDouble(s[0]);
                buf.writeDouble(s[1]);
                buf.writeDouble(s[2]);
                buf.writeFloat((float) s[3]);
            }
            int m = Math.min(missing.size(), 32);
            buf.writeByte(m);
            for (int i = 0; i < m; i++) ByteBufUtils.writeUTF8String(buf, clip(missing.get(i), 64));
        }
    }

    public static final class AnchorOverlayHandler implements IMessageHandler<AnchorOverlay, IMessage> {

        @Override
        public IMessage onMessage(AnchorOverlay msg, MessageContext ctx) {
            ClientAgentCache.setOverlay(msg.names, msg.spots, msg.missing, msg.seconds);
            return null;
        }
    }

    /**
     * Card 3: one part of a board (tasks + goals) or library blob ({@link dev.agentcraft.gtnh.state.HqData}).
     * The client swaps its view only when every part of a generation has arrived.
     */
    public static final class Blob implements IMessage {

        public static final byte BOARD = 1, LIBRARY = 2, OPS = 3, DECISIONS = 4;
        public static final int MAX_PARTS = (MAX_BLOB + BLOB_PART - 1) / BLOB_PART;

        public byte kind;
        public int gen, index, count;
        public byte[] data = new byte[0];

        public Blob() {}

        public static List<Blob> split(byte kind, int gen, byte[] all) {
            List<Blob> out = new ArrayList<>();
            int count = Math.max(1, (all.length + BLOB_PART - 1) / BLOB_PART);
            for (int i = 0; i < count && i < MAX_PARTS; i++) {
                Blob b = new Blob();
                b.kind = kind;
                b.gen = gen;
                b.index = i;
                b.count = Math.min(count, MAX_PARTS);
                int from = i * BLOB_PART, to = Math.min(all.length, from + BLOB_PART);
                b.data = java.util.Arrays.copyOfRange(all, from, to);
                out.add(b);
            }
            return out;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            kind = buf.readByte();
            gen = buf.readInt();
            index = buf.readUnsignedShort();
            count = buf.readUnsignedShort();
            int n = buf.readUnsignedShort();
            if (n > BLOB_PART || n > buf.readableBytes()) throw new IllegalArgumentException("blob part too large");
            data = new byte[n];
            buf.readBytes(data);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeByte(kind);
            buf.writeInt(gen);
            buf.writeShort(index);
            buf.writeShort(count);
            buf.writeShort(data.length);
            buf.writeBytes(data);
        }
    }

    public static final class BlobHandler implements IMessageHandler<Blob, IMessage> {

        @Override
        public IMessage onMessage(Blob msg, MessageContext ctx) {
            dev.agentcraft.gtnh.state.ClientHq.acceptPart(msg.kind, msg.gen, msg.index, msg.count, msg.data);
            return null;
        }
    }

    // ---- card 6: edit tool ------------------------------------------------------------------

    public static final int MAX_CMD = 8000, MAX_VIEW = 30000;

    static String readText(ByteBuf buf, int max) {
        int n = buf.readInt();
        if (n < 0 || n > max || n > buf.readableBytes()) throw new IllegalArgumentException("text too long");
        byte[] b = new byte[n];
        buf.readBytes(b);
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    static void writeText(ByteBuf buf, String s, int max) {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (b.length > max) b = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buf.writeInt(b.length);
        buf.writeBytes(b);
    }

    /** Client -> server: one edit-tool request as JSON ({"a": action, ...}). */
    public static final class EditCmd implements IMessage {

        public String json = "{}";

        public EditCmd() {}

        public EditCmd(String json) {
            this.json = json;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            json = readText(buf, MAX_CMD);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            writeText(buf, json, MAX_CMD);
        }
    }

    public static final class EditCmdHandler implements IMessageHandler<EditCmd, IMessage> {

        @Override
        public IMessage onMessage(EditCmd msg, MessageContext ctx) {
            // netty thread: only queue it; the server tick handles it (EditService)
            dev.agentcraft.gtnh.server.EditService.enqueue(ctx.getServerHandler().playerEntity, msg.json);
            return null;
        }
    }

    /** Server -> client: the editor view (lock, undo/redo, anchors, snapshots, presets, last result). */
    public static final class EditView implements IMessage {

        public String json = "{}";

        public EditView() {}

        public EditView(String json) {
            this.json = json;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            json = readText(buf, MAX_VIEW);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            writeText(buf, json, MAX_VIEW);
        }
    }

    public static final class EditViewHandler implements IMessageHandler<EditView, IMessage> {

        @Override
        public IMessage onMessage(EditView msg, MessageContext ctx) {
            dev.agentcraft.gtnh.client.edit.ClientEdit.acceptView(msg.json);
            return null;
        }
    }

    /** Server -> every client: layout display options (theme, labels, detail thresholds). */
    public static final class Display implements IMessage {

        public String json = "{}";

        public Display() {}

        public Display(String json) {
            this.json = json;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            json = readText(buf, 2000);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            writeText(buf, json, 2000);
        }
    }

    public static final class DisplayHandler implements IMessageHandler<Display, IMessage> {

        @Override
        public IMessage onMessage(Display msg, MessageContext ctx) {
            dev.agentcraft.gtnh.ui.panel.PanelLayout.serverDisplay(msg.json);
            return null;
        }
    }
}
