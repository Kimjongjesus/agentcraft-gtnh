package dev.agentcraft.gtnh.ops;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Card 5b: the open decisions (questions / approvals that need the player) as the server sends them
 * to clients for the decision toast and the read-only decision screen. Pure Java. Only OPEN
 * decisions are sent: the details of at most {@link #MAX} (the newest), plus the ids of every open
 * decision the server knows (at most {@link #MAX_IDS}) and whether that id list is complete. A
 * decision missing from the details is NOT closed: the toast treats an id as closed only when it
 * is missing from a complete id list (cap eviction must never look like a close). Strings are
 * capped like the adapter's. Nothing here can answer a decision (that is a later card).
 */
public final class DecisionData {

    public static final int MAX = 32, MAX_OPTIONS = 6, MAX_IDS = 512, VERSION = 2;

    public static final class Decision {

        public String id = "", agentId = "", agentName = "", kind = "question", question = "", context = "", taskId = "";
        public final List<String> options = new ArrayList<>();
        public long createdAt;
        public int agentColor = 0x9C9488;

        /** "Approval" for permission/merge decisions, else "Decision". */
        public String label() {
            return "question".equals(kind) ? "Decision" : "Approval";
        }
    }

    private DecisionData() {}

    /**
     * What the server knows: {@code live} = the adapter link is up and its snapshot arrived;
     * {@code open} = details of the newest open decisions (at most {@link #MAX}); {@code openIds} =
     * ids of all open decisions (the details' ids included); {@code complete} = that id list holds
     * every open decision (false when the server had to cut it: then a missing id proves nothing).
     */
    public static final class Snapshot {

        public boolean live, complete = true;
        public final List<Decision> open = new ArrayList<>();
        public final List<String> openIds = new ArrayList<>();
    }

    /** Details only; the id list is the details' ids (all of them, so complete unless over MAX_IDS). */
    public static void write(DataOutputStream out, boolean live, List<Decision> open) throws IOException {
        List<String> ids = new ArrayList<>();
        for (Decision d : open) ids.add(d.id);
        write(out, live, open, ids, true);
    }

    /**
     * {@code open}: details (the first {@link #MAX} are sent); {@code openIds}: every open id the
     * server holds (the first {@link #MAX_IDS} are sent); {@code complete} goes out false if the
     * caller says so or the id list had to be cut here.
     */
    public static void write(DataOutputStream out, boolean live, List<Decision> open, List<String> openIds, boolean complete)
        throws IOException {
        int ni = Math.min(openIds.size(), MAX_IDS);
        out.writeByte(VERSION);
        out.writeBoolean(live);
        out.writeBoolean(complete && openIds.size() <= MAX_IDS);
        out.writeShort(ni);
        for (int i = 0; i < ni; i++) OpsData.w(out, OpsData.clean(openIds.get(i), 96));
        int n = Math.min(open.size(), MAX);
        out.writeByte(n);
        for (int i = 0; i < n; i++) {
            Decision d = open.get(i);
            OpsData.w(out, OpsData.clean(d.id, 96));
            OpsData.w(out, OpsData.clean(d.agentId, 64));
            OpsData.w(out, OpsData.clean(d.agentName, 40));
            OpsData.w(out, OpsData.clean(d.kind, 16));
            OpsData.w(out, OpsData.clean(d.question, 240));
            OpsData.w(out, OpsData.clean(d.context, 160));
            OpsData.w(out, OpsData.clean(d.taskId, 96));
            out.writeLong(d.createdAt);
            out.writeInt(d.agentColor);
            int k = Math.min(d.options.size(), MAX_OPTIONS);
            out.writeByte(k);
            for (int j = 0; j < k; j++) OpsData.w(out, OpsData.clean(d.options.get(j), 40));
        }
    }

    public static Snapshot read(DataInputStream in) throws IOException {
        int ver = in.readUnsignedByte();
        if (ver != VERSION) throw new IOException("unknown decision blob version " + ver);
        Snapshot s = new Snapshot();
        s.live = in.readBoolean();
        s.complete = in.readBoolean();
        int ni = Math.min(in.readUnsignedShort(), MAX_IDS);
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < ni; i++) {
            String id = OpsData.r(in, 96);
            if (!id.isEmpty() && ids.add(id)) s.openIds.add(id);
        }
        int n = Math.min(in.readUnsignedByte(), MAX);
        List<Decision> out = s.open;
        for (int i = 0; i < n; i++) {
            Decision d = new Decision();
            d.id = OpsData.r(in, 96);
            d.agentId = OpsData.r(in, 64);
            d.agentName = OpsData.r(in, 40);
            d.kind = OpsData.r(in, 16);
            d.question = OpsData.r(in, 240);
            d.context = OpsData.r(in, 160);
            d.taskId = OpsData.r(in, 96);
            d.createdAt = in.readLong();
            d.agentColor = in.readInt() & 0xFFFFFF;
            int k = Math.min(in.readUnsignedByte(), MAX_OPTIONS);
            for (int j = 0; j < k; j++) d.options.add(OpsData.r(in, 40));
            if (d.id.isEmpty()) continue;
            out.add(d);
            if (ids.add(d.id)) s.openIds.add(d.id); // details are open by definition
        }
        return s;
    }
}
