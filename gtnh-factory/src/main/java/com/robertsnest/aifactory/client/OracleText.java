package com.robertsnest.aifactory.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Plain literal text only: never interpret server strings as chat components. */
public final class OracleText {

    private OracleText() {}

    public static String clean(String text) {
        return text.replaceAll("\u00a7.", "")
            .replaceAll("[\\p{Cntrl}\\p{Cf}]", "");
    }

    public static List<String> lines(JsonObject data) {
        List<String> out = new ArrayList<>();
        if (data.has("answer") && data.get("answer")
            .isJsonPrimitive()) {
            for (String line : data.get("answer")
                .getAsString()
                .split("\n")) {
                if (out.size() >= 500) break;
                out.add(clean(line.substring(0, Math.min(500, line.length()))));
            }
        } else flatten("", data, out, 0);
        if (out.size() >= 500) out.add("[display truncated]");
        return out;
    }

    private static void flatten(String key, JsonElement value, List<String> out, int depth) {
        if (out.size() >= 500) return;
        if (depth > 10) {
            out.add("[depth truncated]");
            return;
        }
        if (value.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject()
                .entrySet())
                flatten(key.isEmpty() ? entry.getKey() : key + "." + entry.getKey(), entry.getValue(), out, depth + 1);
        } else if (value.isJsonArray()) {
            int i = 0;
            if (value.getAsJsonArray()
                .size() == 0) out.add(clean(key) + ": none");
            for (JsonElement element : value.getAsJsonArray()) flatten(key + "[" + i++ + "]", element, out, depth + 1);
        } else {
            String line = key + ": " + (value.isJsonNull() ? "unknown" : value.getAsString());
            out.add(clean(line.substring(0, Math.min(500, line.length()))));
        }
    }
}
