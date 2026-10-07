package com.robertsnest.aifactory.client;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Discrete recorded frames, time-spaced; never interpolated world state. */
final class OracleRecorder {

    private OracleRecorder() {}

    static JsonArray frames(JsonObject data) {
        return data != null && data.has("frames")
            && data.get("frames")
                .isJsonArray() ? data.getAsJsonArray("frames") : new JsonArray();
    }

    static double time(JsonElement frame) {
        try {
            double t = frame.getAsJsonObject()
                .get("capturedAtMillis")
                .getAsDouble();
            return Double.isFinite(t) ? t : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    static int position(JsonArray frames, int index, int width) {
        double start = time(frames.get(0));
        double end = time(frames.get(Math.min(120, frames.size()) - 1));
        double fraction = (time(frames.get(index)) - start) / Math.max(1, end - start);
        return (int) (Math.max(0, Math.min(1, fraction)) * width);
    }

    static int pick(JsonObject data, int mouse, int width) {
        JsonArray frames = frames(data);
        int best = 0, distance = Integer.MAX_VALUE;
        for (int i = 0; i < Math.min(120, frames.size()); i++) {
            int d = Math.abs(mouse - position(frames, i, width));
            if (d < distance) {
                best = i;
                distance = d;
            }
        }
        return best;
    }

    static JsonObject details(JsonObject data, int index) {
        JsonArray frames = frames(data);
        if (frames.size() == 0) return data;
        JsonObject detail = new JsonObject();
        detail.addProperty("meaning", "Recorded telemetry only; no rollback. Click a frame to inspect.");
        detail.add("cadence", data.get("cadence"));
        detail.add("truncated", data.get("truncated"));
        detail.add("frame", frames.get(Math.min(index, frames.size() - 1)));
        return detail;
    }

    static boolean draw(JsonObject data, FontRenderer font, int x, int y, int width, int height, int selected) {
        if (data == null || !data.has("frames")) return false;
        JsonArray frames = frames(data);
        Gui.drawRect(x, y, x + width, y + height, 0xFF101C25);
        font.drawString("RECORDER / sampled captures", x + 8, y + 8, 0xCDE3E8);
        font.drawString("Click a tick to inspect / not rollback", x + 8, y + 24, 0xFFB06B);
        font.drawString("Uneven spacing shows sampling gaps", x + 8, y + 40, 0x9CB4BE);
        if (frames.size() == 0) {
            font.drawString("No recorded frames in window", x + 8, y + 64, 0xCDE3E8);
            return true;
        }
        int axis = y + 100;
        Gui.drawRect(x + 16, axis, x + width - 16, axis + 1, 0xFF263B47);
        for (int i = 0; i < Math.min(120, frames.size()); i++) {
            int px = x + 16 + position(frames, i, width - 32);
            Gui.drawRect(px - 2, axis - 24, px + 2, axis + 12, 0xFF72B9E8);
            if (i == selected) Gui.drawRect(px - 4, axis + 16, px + 4, axis + 20, 0xFFFFB06B);
        }
        JsonElement f = frames.get(Math.min(selected, frames.size() - 1));
        font.drawString("Frame " + (selected + 1) + " / " + Math.min(120, frames.size()), x + 8, axis + 32, 0xCDE3E8);
        if (f.isJsonObject()) font.drawString(
            "Recorded ms: " + OracleMap.text(f.getAsJsonObject(), "capturedAtMillis"),
            x + 8,
            axis + 48,
            0xCDE3E8);
        return true;
    }
}
