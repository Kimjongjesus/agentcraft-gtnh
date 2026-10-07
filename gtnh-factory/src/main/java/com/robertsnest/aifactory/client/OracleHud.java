package com.robertsnest.aifactory.client;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Main-thread cached HUD presentation. Missing data is never a healthy zero. */
public final class OracleHud {

    private JsonObject base;
    private JsonObject shift;
    private long baseAt;
    private long shiftAt;
    private String baseError;
    private String shiftError;

    public void reset() {
        base = null;
        shift = null;
        baseError = null;
        shiftError = null;
    }

    public void acceptBase(JsonObject data, String error, long now) {
        base = data;
        baseError = error;
        baseAt = now;
    }

    public void acceptShift(JsonObject data, String error, long now) {
        shift = data;
        shiftError = error;
        shiftAt = now;
    }

    public List<String> lines(long now) {
        List<String> out = new ArrayList<>();
        out.add("ORACLE / READ ONLY");
        out.add(
            "Shift: " + (shift == null || shiftError != null ? "unknown"
                : value(shift, "observed", "eventsTotal") + " events / "
                    + value(shift, "capturesInWindow")
                    + " captures")
                + (shift != null && now - shiftAt > 90000 ? " [stale]" : ""));
        if (base == null || baseError != null) {
            out.add("Telemetry unknown / unavailable");
            return out;
        }
        out.add(value(base, "scope", "label"));
        out.add(
            "Telemetry: " + value(base, "freshness", "status") + (now - baseAt > 30000 ? " [client cache stale]" : ""));
        JsonElement coverage = base.get("coverage");
        if (coverage != null && coverage.isJsonObject())
            for (java.util.Map.Entry<String, JsonElement> e : coverage.getAsJsonObject()
                .entrySet()) {
                    String state = e.getValue()
                        .isJsonObject()
                            ? value(
                                e.getValue()
                                    .getAsJsonObject(),
                                "status")
                            : "unknown";
                    if (!state.equals("OK")) out.add(e.getKey() + " coverage: " + state);
                }
        if (value(base, "stock", "power", "powered").equals("false")) out.add("CRITICAL: ME unpowered");
        JsonElement faults = at(base, "machines", "faults");
        if (faults != null && faults.isJsonArray()) for (JsonElement fault : faults.getAsJsonArray()) {
            if (fault.isJsonObject()) out.add(
                "FAULT: " + value(fault.getAsJsonObject(), "name") + " / " + value(fault.getAsJsonObject(), "problem"));
        }
        JsonElement stock = at(base, "stock", "top");
        if (stock != null && stock.isJsonArray()) for (JsonElement item : stock.getAsJsonArray()) {
            if (item.isJsonObject() && value(item.getAsJsonObject(), "quantity").equals("0"))
                out.add("STOCK zero: " + value(item.getAsJsonObject(), "name"));
        }
        JsonElement warnings = base.get("warnings");
        if (warnings != null && warnings.isJsonArray()) for (JsonElement warning : warnings.getAsJsonArray())
            if (warning.isJsonPrimitive()) out.add("WARN: " + warning.getAsString());
        out.add("Stock warnings cover returned rows only");
        if (out.size() > 12) {
            int hidden = out.size() - 11;
            out = new ArrayList<>(out.subList(0, 11));
            out.add("+" + hidden + " lines; open Oracle for details");
        }
        for (int i = 0; i < out.size(); i++) out.set(i, OracleText.clean(out.get(i)));
        return out;
    }

    private static JsonElement at(JsonObject obj, String... path) {
        JsonElement value = obj;
        for (String key : path) {
            if (value == null || !value.isJsonObject()) return null;
            value = value.getAsJsonObject()
                .get(key);
        }
        return value;
    }

    private static String value(JsonObject obj, String... path) {
        JsonElement value = at(obj, path);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "unknown";
    }
}
