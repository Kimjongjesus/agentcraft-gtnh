package dev.agentcraft.gtnh.state;

import com.google.gson.JsonObject;

/** One monitor line: a protocol LogEntry as the adapter sent it (already privacy-filtered there). */
public final class LogLine {

    public static final int MAX_TEXT = 320;

    public final long ts;
    public final String kind;
    public final String text;

    public LogLine(long ts, String kind, String text) {
        this.ts = ts;
        this.kind = kind;
        this.text = text;
    }

    public static LogLine fromJson(JsonObject o) {
        long ts = o.has("ts") && o.get("ts")
            .isJsonPrimitive() ? o.get("ts")
                .getAsLong() : 0L;
        String kind = AgentInfo.str(o, "kind", "text");
        String text = "";
        if (o.has("text") && o.get("text")
            .isJsonPrimitive()) {
            text = AgentInfo.sanitize(
                o.get("text")
                    .getAsString(),
                MAX_TEXT);
        }
        return new LogLine(ts, kind, text);
    }

    @Override
    public int hashCode() {
        return (int) (ts ^ (ts >>> 32)) * 31 * 31 + kind.hashCode() * 31 + text.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof LogLine)) return false;
        LogLine l = (LogLine) o;
        return l.ts == ts && l.kind.equals(kind) && l.text.equals(text);
    }
}
