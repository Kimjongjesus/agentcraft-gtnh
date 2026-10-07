package com.robertsnest.aifactory.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Screen-local X/Z projection, never a world overlay or navigation instruction. */
final class OracleMap {

    private OracleMap() {}

    static boolean draw(JsonObject data, FontRenderer font, int x, int y, int width, int height, int mouseX,
        int mouseY) {
        if (data == null || !data.has("markers")
            || !data.get("markers")
                .isJsonArray())
            return false;
        JsonArray rows = data.getAsJsonArray("markers");
        List<JsonObject> points = new ArrayList<>();
        String dimension = null;
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (JsonElement row : rows) {
            if (points.size() >= 512) break;
            if (!row.isJsonObject()) continue;
            JsonObject p = row.getAsJsonObject();
            if (!"observed".equals(text(p, "provenance")) || !coordinate(p, "x") || !coordinate(p, "z")) continue;
            String dim = text(p, "dimensionId");
            if ("unknown".equals(dim)) continue;
            if (dimension == null) dimension = dim;
            if (!dimension.equals(dim)) continue;
            points.add(p);
            minX = Math.min(
                minX,
                p.get("x")
                    .getAsDouble());
            maxX = Math.max(
                maxX,
                p.get("x")
                    .getAsDouble());
            minZ = Math.min(
                minZ,
                p.get("z")
                    .getAsDouble());
            maxZ = Math.max(
                maxZ,
                p.get("z")
                    .getAsDouble());
        }
        Gui.drawRect(x, y, x + width, y + height, 0xFF101C25);
        font.drawString("X-RAY / observed machines", x + 8, y + 8, 0xCDE3E8);
        font.drawString(
            "X/Z, all heights / dim " + (dimension == null ? "unknown" : dimension),
            x + 8,
            y + 20,
            0x9CB4BE);
        font.drawString("No transfer links or collision-safe route", x + 8, y + 32, 0xFFB06B);
        if (points.isEmpty()) {
            font.drawString("No observed positions returned", x + 8, y + 56, 0xCDE3E8);
            return true;
        }
        int left = x + 18, top = y + 58, plotW = width - 36, plotH = height - 108;
        double scale = Math.min(plotW / Math.max(1, maxX - minX), plotH / Math.max(1, maxZ - minZ));
        for (int i = 0; i <= 4; i++) {
            int gx = left + plotW * i / 4, gz = top + plotH * i / 4;
            Gui.drawRect(gx, top, gx + 1, top + plotH, 0xFF263B47);
            Gui.drawRect(left, gz, left + plotW, gz + 1, 0xFF263B47);
        }
        String hover = "Hover a marker for coordinates / " + points.size() + " shown";
        for (JsonObject p : points) {
            int px = left + (int) ((p.get("x")
                .getAsDouble() - minX) * scale);
            int pz = top + (int) ((p.get("z")
                .getAsDouble() - minZ) * scale);
            String state = text(p, "state");
            int color = "fault".equals(state) ? 0xFFFFB06B : "active".equals(state) ? 0xFF58C4A3 : 0xFF9CB4BE;
            Gui.drawRect(px - 3, pz - 3, px + 4, pz + 4, color);
            if (Math.abs(mouseX - px) <= 5 && Math.abs(mouseY - pz) <= 5) hover = text(
                p,
                "name") + " / " + state + " @ " + text(p, "x") + "," + text(p, "y") + "," + text(p, "z");
        }
        font.drawString("active / idle / fault (amber)", x + 8, y + height - 34, 0xCDE3E8);
        font.drawString(font.trimStringToWidth(OracleText.clean(hover), width - 16), x + 8, y + height - 18, 0xCDE3E8);
        return true;
    }

    private static boolean coordinate(JsonObject p, String key) {
        try {
            double value = p.get(key)
                .getAsDouble();
            return Double.isFinite(value) && Math.abs(value) <= 30000000;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static String text(JsonObject p, String key) {
        JsonElement v = p.get(key);
        return v != null && v.isJsonPrimitive() ? OracleText.clean(v.getAsString()) : "unknown";
    }
}
