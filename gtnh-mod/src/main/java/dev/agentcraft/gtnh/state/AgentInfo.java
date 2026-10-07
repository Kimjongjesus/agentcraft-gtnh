package dev.agentcraft.gtnh.state;

import java.util.Collection;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** The subset of a protocol Agent the viewer needs (server and client side). */
public final class AgentInfo {

    public String id = "";
    public String name = "";
    public String title = "";
    public String role = "worker";
    public String state = "idle";
    public String activity = "";
    public String station = "lounge";
    public String taskId = "";
    public int color = 0x9C9488;
    public boolean active = true;
    /** Server-computed: waiting on the player (state waiting_user/blocked, or an open decision names this agent). */
    public boolean waiting;

    public static AgentInfo fromJson(JsonObject o) {
        AgentInfo a = new AgentInfo();
        a.id = str(o, "id", "");
        a.name = str(o, "name", a.id);
        a.title = str(o, "title", "");
        a.role = "lead".equals(str(o, "role", "worker")) ? "lead" : "worker";
        a.state = str(o, "state", "idle");
        a.activity = str(o, "activity", "");
        a.station = str(o, "station", "lounge");
        a.taskId = str(o, "taskId", "");
        a.color = parseColor(str(o, "color", "#9C9488"), 0x9C9488);
        JsonElement act = o.get("active");
        a.active = act == null || !act.isJsonPrimitive() || act.getAsBoolean();
        return a;
    }

    public AgentInfo copy() {
        AgentInfo a = new AgentInfo();
        a.id = id;
        a.name = name;
        a.title = title;
        a.role = role;
        a.state = state;
        a.activity = activity;
        a.station = station;
        a.taskId = taskId;
        a.color = color;
        a.active = active;
        a.waiting = waiting;
        return a;
    }

    static String str(JsonObject o, String k, String dflt) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return dflt;
        return sanitize(e.getAsString(), 64);
    }

    /** No formatting codes or control characters from the wire; bounded length. */
    public static String sanitize(String s, int max) {
        StringBuilder b = new StringBuilder(Math.min(s.length(), max));
        for (int i = 0; i < s.length() && b.length() < max; i++) {
            char c = s.charAt(i);
            if (c == '\u00a7') c = '?';
            if (c < 0x20 && c != '\n') continue;
            b.append(c);
        }
        return b.toString();
    }

    static int parseColor(String hex, int dflt) {
        if (hex == null || hex.length() != 7 || hex.charAt(0) != '#') return dflt;
        try {
            return Integer.parseInt(hex.substring(1), 16);
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** Status family of this agent as shown in-world: an open decision makes it "waiting". */
    public String family() {
        if (waiting) return "waiting";
        return family(state, active);
    }

    /** Upstream status families: idle, thinking, working, waiting, error, done. */
    public static String family(String state, boolean active) {
        if (!active) return "idle";
        switch (state) {
            case "thinking":
                return "thinking";
            case "reading":
            case "editing":
            case "running":
            case "testing":
                return "working";
            case "waiting_user":
            case "blocked":
                return "waiting";
            case "error":
                return "error";
            case "done":
                return "done";
            default:
                return "idle";
        }
    }

    /**
     * Fleet summary for status lamps bound to "fleet" and the roof beacon, most urgent first:
     * someone waiting on the player, then error, then working (incl. thinking), else idle.
     */
    public static String fleetFamily(Collection<AgentInfo> agents, boolean linkUp) {
        if (!linkUp) return "offline";
        boolean err = false, work = false;
        for (AgentInfo a : agents) {
            String f = a.family();
            if ("waiting".equals(f)) return "waiting";
            if ("error".equals(f)) err = true;
            if ("working".equals(f) || "thinking".equals(f)) work = true;
        }
        return err ? "error" : work ? "working" : "idle";
    }

    /** Status colours from upstream's palette.json ("status" block). */
    public static int familyColor(String family) {
        switch (family) {
            case "thinking":
                return 0xC9A227;
            case "working":
                return 0x2FA3A0;
            case "waiting":
                return 0xD97757;
            case "error":
                return 0xC2413B;
            case "warn":
                return 0xE8A93A; // card 5b: ops only (degraded service, warning alert, stale source)
            case "done":
                return 0x8FA98B;
            case "offline":
                return 0x4A4744;
            default:
                return 0x9C9488;
        }
    }

    /** Closest vanilla chat colour, for the vanilla custom-name fallback and console output. */
    public static String familyChat(String family) {
        switch (family) {
            case "thinking":
                return "\u00a7e";
            case "working":
                return "\u00a73";
            case "waiting":
                return "\u00a76";
            case "error":
                return "\u00a7c";
            case "warn":
                return "\u00a76";
            case "done":
                return "\u00a7a";
            default:
                return "\u00a77";
        }
    }

    public String stateLine() {
        String s = state.replace('_', ' ');
        return activity.isEmpty() ? s : s + " \u00b7 " + activity;
    }

    public boolean sameView(AgentInfo o) {
        return o != null && id.equals(o.id)
            && name.equals(o.name)
            && role.equals(o.role)
            && state.equals(o.state)
            && activity.equals(o.activity)
            && station.equals(o.station)
            && color == o.color
            && active == o.active
            && waiting == o.waiting
            && taskId.equals(o.taskId);
    }

    @Override
    public String toString() {
        return id + "[" + state + (active ? "" : ",off") + (waiting ? ",!" : "") + " @" + station + "] \"" + activity + "\"";
    }
}
