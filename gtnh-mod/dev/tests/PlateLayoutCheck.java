import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.agentcraft.gtnh.hq.Anchor;
import dev.agentcraft.gtnh.hq.StationAssigner;
import dev.agentcraft.gtnh.hq.StationAssigner.Target;
import dev.agentcraft.gtnh.hq.StationAssigner.Want;
import dev.agentcraft.gtnh.ui.PlateDeclutter;
import dev.agentcraft.gtnh.ui.PlateDeclutter.Plate;

/**
 * Card 4: 12 agents all waiting on the player in the QA nook (two "user" slots, desks on the first ring,
 * walls behind and beside). The server fans them out over distinct cells (StationAssigner), and the
 * client lays their nameplates out in screen space (PlateDeclutter): from the far and the close QA
 * cameras and from four other angles, no two plates (including the "!" badge) overlap on screen.
 * Plate sizes are the real ones of a full plate ("waiting user · Question: Decision fixture…",
 * about 3 blocks wide with the badge), a compact one (name + state) and a one-line mini one (name).
 *
 *   dev/tests/run.sh   (compiles Anchor, StationAssigner, PlateDeclutter and this file)
 */
public class PlateLayoutCheck {

    static int checks;
    static final int TIERS = Integer.getInteger("tiers", 4);

    static void ok(boolean c, String what) {
        checks++;
        if (!c) throw new AssertionError(what);
    }

    /** Fan cells for 12 waiting agents in the nook (same scene as StationAssignerCheck). */
    static List<double[]> nook() {
        Map<String, Anchor> an = new HashMap<>();
        an.put("user", new Anchor("user", -30.5, 4, -229.5, 90, 0));
        an.put("user_2", new Anchor("user_2", -31.5, 4, -228.5, 90, 0));
        an.put("lounge", new Anchor("lounge", -56.5, 4, -222.5, 180, 0));
        final Set<String> blocked = new HashSet<>();
        int ax = -31, az = -230;
        for (int dz = -5; dz <= 5; dz++) blocked.add((ax + 4) + "," + (az + dz));
        for (int dx = 0; dx < 4; dx++) {
            blocked.add((ax + dx) + "," + (az - 5));
            blocked.add((ax + dx) + "," + (az + 5));
        }
        int[][] desks = { { 2, 0 }, { -2, 0 }, { 0, 2 }, { 0, -2 }, { 2, 2 }, { -2, 2 }, { 2, -2 }, { -2, -2 } };
        for (int[] d : desks) blocked.add((ax + d[0]) + "," + (az + d[1]));
        List<Want> ws = new ArrayList<>();
        for (int i = 0; i < 12; i++) ws.add(new Want("agent" + i, "user", true, 0, 64, 0));
        Map<String, Target> t = new StationAssigner().assign(ws, an, (bx, by, bz) -> !blocked.contains(bx + "," + bz));
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Target x = t.get("agent" + i);
            out.add(new double[] { x.x(), 4, x.z() });
        }
        return out;
    }

    /** Plates as seen from a camera at (cx, cy, cz): bottom centre 1.8 + 0.78 blocks above the feet. */
    static List<Plate> plates(List<double[]> feet, double cx, double cy, double cz) {
        List<Plate> ps = new ArrayList<>();
        int id = 0;
        // full: 244 plate px wide + 34 px badge, 45 px high; compact: about 150 px; mini: about
        // 140 px, one 26 px line; dot: the 40 px badge alone; scale 0.0125 blocks per plate px
        float s = 0.0125F;
        for (double[] f : feet) {
            ps.add(new Plate(id++, f[0] - cx, f[1] + 1.8 + 0.78 - cy, f[2] - cz,
                new float[] { (122 + 34) * s, (75 + 34) * s, (70 + 34) * s, 20 * s },
                new float[] { 122 * s, 75 * s, 70 * s, 20 * s },
                new float[] { 45 * s, 45 * s, 26 * s, 40 * s }));
        }
        return ps;
    }

    static void view(String name, List<double[]> feet, double cx, double cy, double cz, double yaw, double pitch) {
        view(name, feet, cx, cy, cz, yaw, pitch, false);
    }

    static void view(String name, List<double[]> feet, double cx, double cy, double cz, double yaw, double pitch, boolean crowd) {
        List<Plate> ps = plates(feet, cx, cy, cz);
        double before = PlateDeclutter.totalOverlap(ps, yaw, pitch, 0.62); // all full, on tier 0
        PlateDeclutter.layout(ps, yaw, pitch, 0.62, TIERS);
        double after = PlateDeclutter.totalOverlap(ps, yaw, pitch, 0.62);
        int raised = 0, compact = 0, mini = 0, dots = 0;
        for (Plate p : ps) {
            if (p.tier > 0) raised++;
            if (p.mode == PlateDeclutter.COMPACT) compact++;
            if (p.mode == PlateDeclutter.MINI) mini++;
            if (p.mode == PlateDeclutter.DOT) dots++;
            ok(p.tier >= 0 && p.tier < TIERS, name + " tier in range");
        }
        System.out.printf("  %-6s overlap before %.4f after %.4f (raised %d, compact %d, mini %d, marker only %d)%n", name, before,
            after, raised, compact, mini, dots);
        int named = ps.size() - dots;
        ok(before > 0, name + ": without the layout the plates overlap (the bug scene)");
        if (crowd) {
            // the camera is inside the crowd or level with it, so agents hide behind each other
            // (their bodies overlap too): require a 90 % cut and at least half the names readable
            ok(after <= before * 0.10, name + ": overlap cut by 90 %, got " + after + " of " + before);
            ok(named >= 6, name + ": at least 6 of 12 names shown, got " + named);
        } else {
            ok(after == 0, name + ": no two plates overlap on screen, got " + after);
            ok(dots == 0, name + ": every plate keeps its name, " + dots + " shrank to the marker");
        }
        // stable: running the layout again with last frame's choice keeps every plate where it is
        List<Plate> again = plates(feet, cx, cy, cz);
        for (int i = 0; i < ps.size(); i++) {
            again.get(i).prevTier = ps.get(i).tier;
            again.get(i).prevMode = ps.get(i).mode;
        }
        PlateDeclutter.layout(again, yaw, pitch, 0.62, TIERS);
        for (int i = 0; i < ps.size(); i++) {
            ok(again.get(i).tier == ps.get(i).tier && again.get(i).mode == ps.get(i).mode, name + " stable plate " + i);
        }
    }

    public static void main(String[] args) {
        List<double[]> feet = nook();
        Set<String> cells = new HashSet<>();
        for (double[] f : feet) cells.add(Math.floor(f[0]) + "," + Math.floor(f[2]));
        ok(cells.size() == 12, "12 distinct cells");
        // QA cameras (eye 1.62 above the anchor y): far = 11 blocks out, 26 deg down (the overview
        // angle: strict, every name readable, zero overlap); the others are crowd views
        view("far", feet, -41.5, 9 + 1.62, -229.5, -90, 26);
        view("high", feet, -36.5, 14 + 1.62, -229.5, -90, 50);
        view("near", feet, -35.5, 5 + 1.62, -229.5, -90, 14, true);
        view("level", feet, -38.5, 4 + 1.62, -229.5, -90, 0, true);
        view("left", feet, -38.5, 6 + 1.62, -236.5, -45, 10, true);
        view("right", feet, -38.5, 6 + 1.62, -222.5, -135, 10, true);
        // a plate behind the camera is left alone
        List<Plate> behind = plates(feet, -20, 6, -229.5);
        PlateDeclutter.layout(behind, -90, 0, 0.62, TIERS);
        for (Plate p : behind) ok(p.tier == 0 && p.mode == PlateDeclutter.FULL, "behind the camera: untouched");
        System.out.println("PlateLayoutCheck OK: " + checks + " checks");
    }
}
