import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.agentcraft.gtnh.hq.Anchor;
import dev.agentcraft.gtnh.hq.StationAssigner;
import dev.agentcraft.gtnh.hq.StationAssigner.Target;
import dev.agentcraft.gtnh.hq.StationAssigner.Want;

/**
 * Plain-Java check of the station -> anchor rules (no Minecraft on the classpath):
 *   javac -d /tmp/sa src/main/java/dev/agentcraft/gtnh/hq/{Anchor,StationAssigner}.java dev/tests/StationAssignerCheck.java
 *   java -ea -cp /tmp/sa StationAssignerCheck
 */
public class StationAssignerCheck {

    static int checks;

    static void eq(Object want, Object got, String what) {
        checks++;
        if (want == null ? got != null : !want.equals(got)) {
            throw new AssertionError(what + ": expected " + want + " got " + got);
        }
    }

    static Map<String, Anchor> anchors(String... names) {
        Map<String, Anchor> m = new HashMap<>();
        int i = 0;
        for (String n : names) {
            m.put(n, new Anchor(n, 10 * i + 0.5, 64, 0.5, 180, 0));
            i++;
        }
        return m;
    }

    static Want w(String id, String station, boolean active) {
        return new Want(id, station, active, 0, 64, 0);
    }

    /**
     * Card 4: 12 agents all waiting on Eli, two "user" slots in a nook whose first-ring spots are
     * desks and whose back and sides are walls (the scene that stacked everyone on one spot): every
     * agent gets its own standable cell, no two share a cell, nobody is on a desk or wall.
     */
    static void twelveWaiting() {
        Map<String, Anchor> an = new HashMap<>();
        an.put("user", new Anchor("user", -30.5, 4, -229.5, 90, 0)); // facing west
        an.put("user_2", new Anchor("user_2", -31.5, 4, -228.5, 90, 0));
        an.put("lounge", new Anchor("lounge", -56.5, 4, -222.5, 180, 0));
        final java.util.Set<String> blocked = new java.util.HashSet<>();
        int ax = -31, az = -230;
        for (int dz = -5; dz <= 5; dz++) blocked.add((ax + 4) + "," + (az + dz)); // back wall
        for (int dx = 0; dx < 4; dx++) {
            blocked.add((ax + dx) + "," + (az - 5));
            blocked.add((ax + dx) + "," + (az + 5));
        }
        int[][] desks = { { 2, 0 }, { -2, 0 }, { 0, 2 }, { 0, -2 }, { 2, 2 }, { -2, 2 }, { 2, -2 }, { -2, -2 } };
        for (int[] d : desks) blocked.add((ax + d[0]) + "," + (az + d[1]));
        StationAssigner.Standable ok = (bx, by, bz) -> !blocked.contains(bx + "," + bz);

        StationAssigner sa = new StationAssigner();
        List<Want> ws = new ArrayList<>();
        for (int i = 0; i < 12; i++) ws.add(w("agent" + i, "user", true));
        Map<String, Target> t = sa.assign(ws, an, ok);
        eq(12, t.size(), "12 waiting agents placed");
        java.util.Set<String> cells = new java.util.HashSet<>();
        int onSlots = 0;
        for (int i = 0; i < 12; i++) {
            Target x = t.get("agent" + i);
            eq("user", x.wantedStation, "agent" + i + " wants user");
            eq(false, x.stacked, "agent" + i + " not stacked");
            int bx = (int) Math.floor(x.x()), bz = (int) Math.floor(x.z());
            eq(false, blocked.contains(bx + "," + bz), "agent" + i + " not in a desk or wall");
            eq(true, cells.add(bx + "," + bz), "agent" + i + " has its own cell");
            if (x.ring < 0) onSlots++;
            else eq(true, Math.abs(x.dx) <= StationAssigner.FAN_RADIUS && Math.abs(x.dz) <= StationAssigner.FAN_RADIUS, "within the fan radius");
        }
        eq(2, onSlots, "two on the user slots");
        eq(false, cells.contains("-57,-223"), "lounge anchor cell left free");
        // stable: the same call again gives the same cells (no shuffling between ticks)
        Map<String, Target> t2 = sa.assign(ws, an, ok);
        for (int i = 0; i < 12; i++) eq(true, t.get("agent" + i).sameSpot(t2.get("agent" + i)), "stable cell " + i);

        // nowhere to fan out at all: agents share the anchor, flagged stacked (client collapses plates)
        Map<String, Target> t3 = new StationAssigner().assign(ws, an, (bx, by, bz) -> false);
        int stacked = 0;
        for (Target x : t3.values()) if (x.stacked) stacked++;
        eq(10, stacked, "no free cell: 10 stacked, flagged");
    }

    public static void main(String[] args) {
        // personal desk, shared library slots, lounge with two slots
        Map<String, Anchor> an = anchors("desk_opus", "desk", "library", "library_2", "lounge", "lounge_2", "cam_overview", "overflow_sign");
        StationAssigner sa = new StationAssigner();
        List<Want> ws = new ArrayList<>();
        ws.add(w("opus", "desk", true));
        ws.add(w("sonnet", "desk", true));
        ws.add(w("rev1", "library", true));
        ws.add(w("rev2", "library", true));
        ws.add(w("idle1", "lounge", true));
        ws.add(w("off", "desk", false)); // off shift -> lounge
        Map<String, Target> t = sa.assign(ws, an);
        eq("personal:desk_opus", t.get("opus").toString(), "personal desk wins");
        eq("slot:desk", t.get("sonnet").toString(), "shared desk slot");
        eq("slot:library", t.get("rev1").toString(), "library slot 1");
        eq("slot:library_2", t.get("rev2").toString(), "library slot 2");
        eq("slot:lounge", t.get("idle1").toString(), "lounge slot");
        eq("slot:lounge_2", t.get("off").toString(), "off shift -> lounge");
        eq(0, sa.newlyMissing.size(), "nothing missing");

        // sticky: rev1 leaves, rev2 keeps library_2 (no reshuffle)
        ws.remove(2);
        t = sa.assign(ws, an);
        eq("slot:library_2", t.get("rev2").toString(), "sticky slot");

        // missing station: terminal has no anchor -> lounge slot if free, logged once
        StationAssigner s2 = new StationAssigner();
        List<Want> w2 = new ArrayList<>();
        w2.add(w("cron", "terminal", true));
        t = s2.assign(w2, an);
        eq("fallback:lounge", t.get("cron").toString(), "missing station -> lounge");
        eq("[terminal]", s2.newlyMissing.toString(), "missing reported");
        s2.assign(w2, an);
        eq(0, s2.newlyMissing.size(), "missing reported only once");

        // more agents than slots: hover rings around the first slot, distinct ring indexes
        StationAssigner s3 = new StationAssigner();
        List<Want> w3 = new ArrayList<>();
        for (int i = 0; i < 5; i++) w3.add(w("idle" + i, "lounge", true));
        t = s3.assign(w3, an);
        eq("slot:lounge", t.get("idle0").toString(), "slot 1");
        eq("slot:lounge_2", t.get("idle1").toString(), "slot 2");
        eq("hover:lounge~0", t.get("idle2").toString(), "hover 0");
        eq("hover:lounge~1", t.get("idle3").toString(), "hover 1");
        eq("hover:lounge~2", t.get("idle4").toString(), "hover 2");
        // fan cells: distinct, never on an anchor, 2-block lattice first
        java.util.Set<String> cells3 = new java.util.HashSet<>();
        for (int i = 2; i < 5; i++) {
            Target h = t.get("idle" + i);
            eq(false, h.stacked, "fan cell found");
            eq(0, Math.floorMod(h.dx, 2) + Math.floorMod(h.dz, 2), "lattice cell first");
            cells3.add(h.x() + "," + h.z());
        }
        eq(3, cells3.size(), "three distinct fan cells");

        twelveWaiting();

        // no lounge and no own station: nearest standing anchor (cam_/overflow ignored)
        Map<String, Anchor> only = new HashMap<>();
        only.put("cam_overview", new Anchor("cam_overview", 0.5, 64, 0.5, 0, 0));
        only.put("library", new Anchor("library", 100.5, 64, 0.5, 0, 0));
        only.put("meeting", new Anchor("meeting", 20.5, 64, 0.5, 0, 0));
        StationAssigner s4 = new StationAssigner();
        List<Want> w4 = new ArrayList<>();
        w4.add(w("opus", "desk", true));
        t = s4.assign(w4, only);
        eq("nearest:meeting~0", t.get("opus").toString(), "nearest anchor");

        // no anchors at all: null target (legacy row)
        t = new StationAssigner().assign(w4, new HashMap<String, Anchor>());
        eq(null, t.get("opus"), "no anchors -> legacy row");

        // no anchors yet, then Eli sets some: the missing station is still reported once (the
        // anchor-less phase must not use up the per-station warning)
        StationAssigner s6 = new StationAssigner();
        List<Want> w6 = new ArrayList<>();
        w6.add(w("cron", "terminal", true));
        s6.assign(w6, anchors("cam_overview", "overflow_sign"));
        eq(0, s6.newlyMissing.size(), "nothing reported while there are no station anchors");
        t = s6.assign(w6, an);
        eq("fallback:lounge", t.get("cron").toString(), "terminal missing after first anchors -> lounge");
        eq("[terminal]", s6.newlyMissing.toString(), "missing reported once anchors exist");
        s6.assign(w6, an);
        eq(0, s6.newlyMissing.size(), "and only once");

        // only other agents' personal desks: no 'missing' warning, but lounge fallback
        Map<String, Anchor> pd = anchors("desk_opus", "lounge");
        StationAssigner s5 = new StationAssigner();
        List<Want> w5 = new ArrayList<>();
        w5.add(w("sonnet", "desk", true));
        t = s5.assign(w5, pd);
        eq("fallback:lounge", t.get("sonnet").toString(), "no shared desk -> lounge");
        eq(0, s5.newlyMissing.size(), "desk has anchors (personal), not missing");

        // unknown station -> lounge; facing parsing
        eq("lounge", StationAssigner.effectiveStation("garage", true), "unknown station");
        eq(180.0F, Anchor.parseFacing("north"), "north");
        eq(-90.0F, Anchor.parseFacing("east"), "east");
        eq("west", Anchor.facingName(90), "facing name");
        eq(true, StationAssigner.isStandingAnchor("desk_claude-builder"), "personal anchor stands");
        eq(false, StationAssigner.isStandingAnchor("overflow_sign"), "block anchor");
        System.out.println("StationAssignerCheck OK: " + checks + " checks");
    }
}
