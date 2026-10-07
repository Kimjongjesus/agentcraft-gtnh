package com.robertsnest.aifactory.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;

import org.lwjgl.opengl.GL11;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Screen-only isometric proposal geometry. No block registry/world interaction. */
final class OracleGhost {

    private OracleGhost() {}

    static boolean draw(JsonObject data, FontRenderer font, int x, int y, int width, int height) {
        if (data == null || !"ghost".equals(OracleMap.text(data, "render"))
            || !"false".equals(OracleMap.text(data, "mutates"))
            || !data.has("blocks")
            || !data.get("blocks")
                .isJsonArray())
            return false;
        List<double[]> blocks = new ArrayList<>();
        for (JsonElement row : data.getAsJsonArray("blocks")) {
            if (blocks.size() >= 4096) break;
            try {
                JsonObject p = row.getAsJsonObject();
                double bx = p.get("x")
                    .getAsDouble(),
                    by = p.get("y")
                        .getAsDouble(),
                    bz = p.get("z")
                        .getAsDouble();
                if (Double.isFinite(bx) && Double.isFinite(by)
                    && Double.isFinite(bz)
                    && Math.abs(bx) <= 30000000
                    && Math.abs(by) <= 30000000
                    && Math.abs(bz) <= 30000000) blocks.add(new double[] { bx - bz, (bx + bz) / 2 - by, bx + bz + by });
            } catch (RuntimeException ignored) {
                // Malformed positions are not invented as origin blocks.
            }
        }
        Gui.drawRect(x, y, x + width, y + height, 0xFF101C25);
        font.drawString("GHOST / proposed geometry", x + 8, y + 8, 0xCDE3E8);
        font.drawString("Not installed / not clearance approval", x + 8, y + 24, 0xFFB06B);
        font.drawString("Dimension " + OracleMap.text(data, "dimensionId"), x + 8, y + 40, 0x9CB4BE);
        if (blocks.isEmpty()) {
            font.drawString("No valid proposal positions returned", x + 8, y + 64, 0xCDE3E8);
            return true;
        }
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (double[] b : blocks) {
            minX = Math.min(minX, b[0]);
            maxX = Math.max(maxX, b[0]);
            minY = Math.min(minY, b[1]);
            maxY = Math.max(maxY, b[1]);
        }
        double scale = Math.min((width - 40) / (maxX - minX + 3), (height - 110) / (maxY - minY + 3));
        blocks.sort(Comparator.comparingDouble(b -> b[2]));
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        try {
            for (double[] b : blocks) {
                double px = x + width / 2.0 + (b[0] - (minX + maxX) / 2) * scale;
                double py = y + 62 + (b[1] - minY + 1) * scale;
                face(0xB99BE8, px, py - scale / 2, px + scale, py, px, py + scale / 2, px - scale, py);
                face(0x73569C, px - scale, py, px, py + scale / 2, px, py + 1.5 * scale, px - scale, py + scale);
                face(0x9275BB, px, py + scale / 2, px + scale, py, px + scale, py + scale, px, py + 1.5 * scale);
            }
        } finally {
            GL11.glPopAttrib();
        }
        font.drawString(
            blocks.size() + " of "
                + data.getAsJsonArray("blocks")
                    .size()
                + " blocks / render only",
            x + 8,
            y + height - 18,
            0xCDE3E8);
        return true;
    }

    private static void face(int rgb, double... xy) {
        GL11.glColor3ub((byte) (rgb >> 16), (byte) (rgb >> 8), (byte) rgb);
        GL11.glBegin(GL11.GL_QUADS);
        for (int i = 0; i < xy.length; i += 2) GL11.glVertex2d(xy[i], xy[i + 1]);
        GL11.glEnd();
    }
}
