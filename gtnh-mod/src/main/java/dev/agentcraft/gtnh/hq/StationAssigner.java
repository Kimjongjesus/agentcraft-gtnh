package dev.agentcraft.gtnh.hq;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Station -> anchor slot assignment for the agent NPCs (pure logic, no Minecraft classes, so it is
 * checked by a plain-Java test: dev/tests/StationAssignerCheck.java).
 *
 * <p>
 * Naming contract (the same idea as upstream's AnchorNames): {@code <station>} is slot 1 of a shared
 * station, {@code <station>_2} .. {@code <station>_N} more slots, {@code <station>_<agentId>} a
 * personal spot (e.g. {@code desk_claude-builder}). Off-shift agents go to the lounge.
 *
 * <p>
 * Per agent, first match wins: personal spot; the slot it already had at that station (sticky);
 * the first free slot. A station with no anchor at all is reported once ({@link #newlyMissing}) and
 * its agents go to a free lounge slot. Everyone left over hovers in a ring around the station's
 * first slot, else the lounge's first slot, else the nearest configured anchor; with no anchors at
 * all the target is null and the caller uses its legacy row next to the world spawn.
 */
public final class StationAssigner {

    public static final String[] STATIONS = { "desk", "library", "terminal", "testbench", "mergestation", "meeting",
        "lounge", "user" };
    public static final int MAX_SLOTS = 32;

    public static final class Want {

        public final String agentId;
        public final String station;
        public final boolean active;
        /** Reference point for "nearest anchor" (the NPC's current position, or the world spawn). */
        public final double refX, refY, refZ;

        public Want(String agentId, String station, boolean active, double refX, double refY, double refZ) {
            this.agentId = agentId;
            this.station = station;
            this.active = active;
            this.refX = refX;
            this.refY = refY;
            this.refZ = refZ;
        }
    }

    public static final class Target {

        /** personal | slot | fallback (lounge slot instead of a missing station) | hover | nearest */
        public final String mode;
        public final Anchor anchor;
        /** Fan index around the anchor (0 = the first extra spot); -1 when standing on the anchor. */
        public final int ring;
        public final String wantedStation;
        /** Block offset of the fan cell from the anchor (0, 0 on the anchor). */
        public final int dx, dz;
        /**
         * True when no free standable cell was found near the anchor, so this agent shares the
         * anchor spot with others; the client then collapses their nameplates into one count pill.
         */
        public final boolean stacked;

        Target(String mode, Anchor anchor, int ring, String wantedStation) {
            this(mode, anchor, ring, wantedStation, 0, 0, false);
        }

        Target(String mode, Anchor anchor, int ring, String wantedStation, int dx, int dz, boolean stacked) {
            this.mode = mode;
            this.anchor = anchor;
            this.ring = ring;
            this.wantedStation = wantedStation;
            this.dx = dx;
            this.dz = dz;
            this.stacked = stacked;
        }

        public double x() {
            return anchor.x + dx;
        }

        public double z() {
            return anchor.z + dz;
        }

        public boolean sameSpot(Target o) {
            return o != null && o.anchor.name.equals(anchor.name)
                && o.ring == ring
                && o.dx == dx
                && o.dz == dz
                && o.anchor.x == anchor.x
                && o.anchor.y == anchor.y
                && o.anchor.z == anchor.z
                && o.anchor.yaw == anchor.yaw;
        }

        @Override
        public String toString() {
            return mode + ":" + anchor.name + (ring >= 0 ? "~" + ring : "");
        }
    }

    /** Can an agent stand in this block (feet block coordinates)? Supplied by the server world. */
    public interface Standable {

        boolean ok(int bx, int by, int bz);
    }

    /** How far (blocks, Chebyshev) the fan looks for free cells around a full station. */
    public static final int FAN_RADIUS = 6;

    private final Map<String, String> sticky = new HashMap<>();
    private final Set<String> warned = new HashSet<>();
    /** Stations reported missing by the last {@link #assign} call for the first time (log once each). */
    public final List<String> newlyMissing = new ArrayList<>();

    public static boolean isStation(String s) {
        for (String st : STATIONS) if (st.equals(s)) return true;
        return false;
    }

    /** Where the agent should be: off shift -> lounge, unknown station -> lounge. */
    public static String effectiveStation(String station, boolean active) {
        if (!active) return "lounge";
        String s = station == null ? "" : station.toLowerCase(Locale.ROOT);
        return isStation(s) ? s : "lounge";
    }

    /** Shared slots of a station in order: station, station_2, ..., station_N (gaps allowed). */
    public static List<Anchor> slots(String station, Map<String, Anchor> anchors) {
        List<Anchor> out = new ArrayList<>();
        Anchor first = anchors.get(station);
        if (first != null) out.add(first);
        for (int i = 2; i <= MAX_SLOTS; i++) {
            Anchor a = anchors.get(station + "_" + i);
            if (a != null) out.add(a);
        }
        return out;
    }

    /** True for anchors an agent may stand on (stations and personal spots; not cam_ / block anchors). */
    public static boolean isStandingAnchor(String name) {
        int cut = name.indexOf('_');
        return isStation(cut < 0 ? name : name.substring(0, cut));
    }

    /**
     * Candidate fan cells around an anchor, nearest first: block offsets (dx, dz) within
     * {@link #FAN_RADIUS}, the 2-block lattice first (neighbours two blocks apart keep their
     * nameplates apart), then the cells in between. Ties: cells in front of the anchor (its facing)
     * first, then a fixed angular order, so the result is deterministic.
     */
    public static List<int[]> fanCells(Anchor a) {
        List<int[]> cells = new ArrayList<>();
        for (int dx = -FAN_RADIUS; dx <= FAN_RADIUS; dx++) {
            for (int dz = -FAN_RADIUS; dz <= FAN_RADIUS; dz++) {
                if (dx == 0 && dz == 0) continue;
                cells.add(new int[] { dx, dz });
            }
        }
        // facing vector from yaw (Minecraft: yaw 0 = south/+z, 90 = west/-x)
        final double fx = -Math.sin(Math.toRadians(a.yaw)), fz = Math.cos(Math.toRadians(a.yaw));
        Collections.sort(cells, (p, q) -> {
            int lp = (p[0] % 2 == 0 && p[1] % 2 == 0) ? 0 : 1, lq = (q[0] % 2 == 0 && q[1] % 2 == 0) ? 0 : 1;
            if (lp != lq) return lp - lq;
            int dp = p[0] * p[0] + p[1] * p[1], dq = q[0] * q[0] + q[1] * q[1];
            if (dp != dq) return dp - dq;
            double fp = p[0] * fx + p[1] * fz, fq = q[0] * fx + q[1] * fz;
            if (fp != fq) return fp > fq ? -1 : 1;
            return Double.compare(Math.atan2(p[1], p[0]), Math.atan2(q[1], q[0]));
        });
        return cells;
    }

    private static long cellKey(int bx, int bz) {
        return ((long) bx << 32) ^ (bz & 0xFFFFFFFFL);
    }

    public void forget(String agentId) {
        sticky.remove(agentId);
    }

    public void resetWarnings() {
        warned.clear();
    }

    public Map<String, Target> assign(List<Want> wants, Map<String, Anchor> anchors) {
        return assign(wants, anchors, null);
    }

    /**
     * @param standable world check for fan cells (null = every cell is free, for plain-Java tests)
     */
    public Map<String, Target> assign(List<Want> wants, Map<String, Anchor> anchors, Standable standable) {
        newlyMissing.clear();
        // With no station anchors at all every station is "missing"; the caller logs that case once
        // itself. Do not mark stations as warned then, or the per-station warning would be used up
        // before Eli sets his first anchor.
        boolean anyStanding = false;
        for (String n : anchors.keySet()) {
            if (isStandingAnchor(n)) {
                anyStanding = true;
                break;
            }
        }
        Map<String, Target> out = new LinkedHashMap<>();
        Set<String> taken = new HashSet<>();
        // 1. personal spots
        for (Want w : wants) {
            String st = effectiveStation(w.station, w.active);
            Anchor a = anchors.get(st + "_" + w.agentId);
            if (a != null) {
                out.put(w.agentId, new Target("personal", a, -1, st));
                taken.add(a.name);
            }
        }
        // 2. the slot an agent already holds at the same station (no reshuffling when others move)
        for (Want w : wants) {
            if (out.containsKey(w.agentId)) continue;
            String st = effectiveStation(w.station, w.active);
            String prev = sticky.get(w.agentId);
            if (prev == null || taken.contains(prev)) continue;
            for (Anchor a : slots(st, anchors)) {
                if (a.name.equals(prev)) {
                    out.put(w.agentId, new Target("slot", a, -1, st));
                    taken.add(a.name);
                    break;
                }
            }
        }
        // 3. first free slot; 4. missing station -> free lounge slot
        for (Want w : wants) {
            if (out.containsKey(w.agentId)) continue;
            String st = effectiveStation(w.station, w.active);
            List<Anchor> own = slots(st, anchors);
            Anchor free = firstFree(own, taken);
            String mode = "slot";
            if (own.isEmpty()) {
                // no shared slot here (maybe only other agents' personal spots): use the lounge
                if (anyStanding && !hasPersonalAnchors(st, anchors) && warned.add(st)) newlyMissing.add(st);
                if (!"lounge".equals(st)) {
                    free = firstFree(slots("lounge", anchors), taken);
                    mode = "fallback";
                }
            }
            if (free != null) {
                out.put(w.agentId, new Target(mode, free, -1, st));
                taken.add(free.name);
            }
        }
        // 5. fan out around the station's first slot, else the lounge's, else the nearest anchor:
        // distinct free standable cells (never on another anchor or another agent's cell); only
        // when none is left does an agent share the anchor spot (flagged "stacked").
        Set<Long> occupied = new HashSet<>();
        for (Anchor a : anchors.values()) {
            if (isStandingAnchor(a.name)) occupied.add(cellKey(floor(a.x), floor(a.z)));
        }
        Map<String, Integer> ringCount = new HashMap<>();
        Map<String, Integer> scan = new HashMap<>(); // next candidate index per base anchor
        for (Want w : wants) {
            if (out.containsKey(w.agentId)) continue;
            String st = effectiveStation(w.station, w.active);
            List<Anchor> own = slots(st, anchors);
            List<Anchor> lounge = slots("lounge", anchors);
            Anchor base = !own.isEmpty() ? own.get(0) : !lounge.isEmpty() ? lounge.get(0) : nearest(w, anchors);
            if (base == null) continue; // no anchors at all
            int k = ringCount.containsKey(base.name) ? ringCount.get(base.name) : 0;
            ringCount.put(base.name, k + 1);
            String mode = !own.isEmpty() || !lounge.isEmpty() ? "hover" : "nearest";
            List<int[]> cells = fanCells(base);
            int i = scan.containsKey(base.name) ? scan.get(base.name) : 0;
            int bx = floor(base.x), by = floor(base.y), bz = floor(base.z);
            int[] pick = null;
            for (; i < cells.size(); i++) {
                int[] c = cells.get(i);
                long key = cellKey(bx + c[0], bz + c[1]);
                if (occupied.contains(key)) continue;
                if (standable != null && !standable.ok(bx + c[0], by, bz + c[1])) continue;
                pick = c;
                occupied.add(key);
                i++;
                break;
            }
            scan.put(base.name, i);
            out.put(
                w.agentId,
                pick == null ? new Target(mode, base, k, st, 0, 0, true) : new Target(mode, base, k, st, pick[0], pick[1], false));
        }
        for (Map.Entry<String, Target> e : out.entrySet()) {
            if (e.getValue().ring < 0) sticky.put(e.getKey(), e.getValue().anchor.name);
        }
        // a station that has an anchor again is re-armed: if it goes missing later it is reported again
        for (Iterator<String> it = warned.iterator(); it.hasNext();) {
            String st = it.next();
            if (!slots(st, anchors).isEmpty() || hasPersonalAnchors(st, anchors)) it.remove();
        }
        return out;
    }

    private static int floor(double d) {
        int i = (int) d;
        return d < i ? i - 1 : i;
    }

    private static boolean hasPersonalAnchors(String station, Map<String, Anchor> anchors) {
        String prefix = station + "_";
        for (String n : anchors.keySet()) {
            if (n.startsWith(prefix) && !n.substring(prefix.length())
                .matches("\\d+")) return true;
        }
        return false;
    }

    private static Anchor firstFree(List<Anchor> slots, Set<String> taken) {
        for (Anchor a : slots) if (!taken.contains(a.name)) return a;
        return null;
    }

    private static Anchor nearest(Want w, Map<String, Anchor> anchors) {
        Anchor best = null;
        double bd = Double.MAX_VALUE;
        List<String> names = new ArrayList<>(anchors.keySet());
        Collections.sort(names); // deterministic tie-break
        for (String n : names) {
            if (!isStandingAnchor(n)) continue;
            Anchor a = anchors.get(n);
            double d = a.distSq(w.refX, w.refY, w.refZ);
            if (d < bd) {
                bd = d;
                best = a;
            }
        }
        return best;
    }
}
