package dev.agentcraft.gtnh.ui;

import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.opengl.GL11;

/**
 * Immediate-mode drawing primitives shared by the GUI screens and the in-world panels, in the
 * caller's current GL units (+y down): filled and rounded rectangles, outlines, rings (progress),
 * dots, pills, and the segmented panel/card shapes. Back-to-front painter's order: the in-world
 * canvas turns depth writes off after its background, so later calls draw over earlier ones.
 */
public final class Ui {

    private Ui() {}

    private static boolean cullWasOn;

    private static void begin() {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        // GUI screens run with back-face culling on; the fans (rounded shapes, dots) and rings wind
        // the other way than the quads, so without this they vanished in screens but not in-world
        cullWasOn = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        if (cullWasOn) GL11.glDisable(GL11.GL_CULL_FACE);
    }

    private static void end() {
        if (cullWasOn) GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    private static void color(Tessellator t, int argb) {
        if ((argb & 0xFF000000) == 0) argb |= 0xFF000000;
        t.setColorRGBA((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
    }

    public static void rect(double x0, double y0, double x1, double y1, int argb) {
        rectZ(x0, y0, x1, y1, argb, 0);
    }

    /** Rectangle at depth z (in-world backgrounds that must write depth sit slightly behind 0). */
    public static void rectZ(double x0, double y0, double x1, double y1, int argb, double z) {
        if (x1 <= x0 || y1 <= y0) return;
        begin();
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        color(t, argb);
        t.addVertex(x0, y1, z);
        t.addVertex(x1, y1, z);
        t.addVertex(x1, y0, z);
        t.addVertex(x0, y0, z);
        t.draw();
        end();
    }

    /** Rounded rectangle (corner radius r, clamped to half the smaller side). */
    public static void round(double x0, double y0, double x1, double y1, double r, int argb) {
        roundZ(x0, y0, x1, y1, r, argb, 0);
    }

    public static void roundZ(double x0, double y0, double x1, double y1, double r, int argb, double z) {
        if (x1 <= x0 || y1 <= y0) return;
        r = Math.max(0, Math.min(r, Math.min(x1 - x0, y1 - y0) / 2));
        if (r <= 0.01) {
            rectZ(x0, y0, x1, y1, argb, z);
            return;
        }
        begin();
        Tessellator t = Tessellator.instance;
        t.startDrawing(GL11.GL_TRIANGLE_FAN);
        color(t, argb);
        t.addVertex((x0 + x1) / 2, (y0 + y1) / 2, z);
        int seg = 6;
        double[][] corners = { { x1 - r, y0 + r, -90 }, { x1 - r, y1 - r, 0 }, { x0 + r, y1 - r, 90 }, { x0 + r, y0 + r, 180 } };
        // clockwise in canvas space (+y down) starting at the top edge's right end
        for (double[] c : corners) {
            for (int i = 0; i <= seg; i++) {
                double a = Math.toRadians(c[2] + 90.0 * i / seg);
                t.addVertex(c[0] + Math.cos(a) * r, c[1] + Math.sin(a) * r, z);
            }
        }
        t.addVertex(x1 - r, y0, z);
        t.draw();
        end();
    }

    /** Rounded outline of width lw (drawn as a ring of quads). */
    public static void roundOutline(double x0, double y0, double x1, double y1, double r, double lw, int argb) {
        r = Math.max(lw, Math.min(r, Math.min(x1 - x0, y1 - y0) / 2));
        begin();
        Tessellator t = Tessellator.instance;
        t.startDrawing(GL11.GL_QUAD_STRIP);
        color(t, argb);
        int seg = 6;
        double[][] corners = { { x1 - r, y0 + r, -90 }, { x1 - r, y1 - r, 0 }, { x0 + r, y1 - r, 90 }, { x0 + r, y0 + r, 180 } };
        double fx = 0, fy = 0, fxi = 0, fyi = 0;
        boolean first = true;
        for (double[] c : corners) {
            for (int i = 0; i <= seg; i++) {
                double a = Math.toRadians(c[2] + 90.0 * i / seg);
                double ox = c[0] + Math.cos(a) * r, oy = c[1] + Math.sin(a) * r;
                double ix = c[0] + Math.cos(a) * (r - lw), iy = c[1] + Math.sin(a) * (r - lw);
                if (first) {
                    fx = ox;
                    fy = oy;
                    fxi = ix;
                    fyi = iy;
                    first = false;
                }
                t.addVertex(ox, oy, 0);
                t.addVertex(ix, iy, 0);
            }
        }
        t.addVertex(fx, fy, 0);
        t.addVertex(fxi, fyi, 0);
        t.draw();
        end();
    }

    /** Filled circle. */
    public static void dot(double cx, double cy, double r, int argb) {
        begin();
        Tessellator t = Tessellator.instance;
        t.startDrawing(GL11.GL_TRIANGLE_FAN);
        color(t, argb);
        t.addVertex(cx, cy, 0);
        int seg = 20;
        for (int i = 0; i <= seg; i++) {
            double a = Math.PI * 2 * i / seg;
            t.addVertex(cx + Math.cos(a) * r, cy + Math.sin(a) * r, 0);
        }
        t.draw();
        end();
    }

    /** Annulus segment from fraction a0 to a1 (0 = top, clockwise). */
    public static void ring(double cx, double cy, double rIn, double rOut, double a0, double a1, int argb) {
        if (a1 <= a0) return;
        begin();
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        color(t, argb);
        int seg = Math.max(2, (int) Math.ceil((a1 - a0) * 96));
        for (int i = 0; i < seg; i++) {
            double f0 = a0 + (a1 - a0) * i / seg, f1 = a0 + (a1 - a0) * (i + 1) / seg;
            double t0 = f0 * Math.PI * 2, t1 = f1 * Math.PI * 2;
            double s0 = Math.sin(t0), c0 = -Math.cos(t0), s1 = Math.sin(t1), c1 = -Math.cos(t1);
            t.addVertex(cx + s0 * rIn, cy + c0 * rIn, 0);
            t.addVertex(cx + s1 * rIn, cy + c1 * rIn, 0);
            t.addVertex(cx + s1 * rOut, cy + c1 * rOut, 0);
            t.addVertex(cx + s0 * rOut, cy + c0 * rOut, 0);
        }
        t.draw();
        end();
    }

    /**
     * Status pill: rounded, filled with {@code fill}, text in the readable colour for it.
     *
     * @return the pill width
     */
    public static float pill(UiFont f, String text, float x, float y, float size, int fill) {
        float padX = size * 0.55F, h = f.lineHeight(size) * 0.92F + size * 0.18F;
        float w = f.width(text, size) + 2 * padX;
        round(x, y, x + w, y + h, h / 2, 0xFF000000 | fill);
        int fg = Theme.contrast(Theme.INK, fill) >= Theme.contrast(0xFFFFFF, fill) ? Theme.INK : 0xFFFFFF;
        f.draw(text, x + padX, y + (h - f.lineHeight(size)) / 2 + size * 0.02F, size, 0xFF000000 | fg);
        return w;
    }

    public static float pillHeight(UiFont f, float size) {
        return f.lineHeight(size) * 0.92F + size * 0.18F;
    }

    /** Right-aligned pill ending at xRight. */
    public static float pillRight(UiFont f, String text, float xRight, float y, float size, int fill) {
        float w = f.width(text, size) + 2 * size * 0.55F;
        return pill(f, text, xRight - w, y, size, fill);
    }

    /**
     * A segmented panel: rounded surface with a coloured header strip carrying a title (and an
     * optional right-aligned note). Returns the y where the body starts.
     */
    public static float panel(UiFont titleFont, UiFont noteFont, Theme th, float x0, float y0, float x1, float y1, float r,
        String title, String note, int headerFill, float size) {
        round(x0, y0, x1, y1, r, 0xFF000000 | th.surface);
        float hh = titleFont.lineHeight(size) + size * 0.5F;
        if (title == null) return y0;
        // header strip: rounded top, square bottom
        round(x0, y0, x1, y0 + hh, r, 0xFF000000 | headerFill);
        rect(x0, y0 + hh - r, x1, y0 + hh, 0xFF000000 | headerFill);
        int fg = Theme.contrast(Theme.INK, headerFill) >= 4.5 ? Theme.INK : 0xFFFFFF;
        float pad = size * 0.55F;
        float noteW = note == null || note.isEmpty() ? 0 : noteFont.width(note, size * 0.86F) + pad;
        titleFont.drawFit(title, x0 + pad, y0 + size * 0.25F, size, x1 - x0 - 2 * pad - noteW, 0xFF000000 | fg);
        if (noteW > 0) noteFont.drawRight(note, x1 - pad, y0 + size * 0.25F + size * 0.1F, size * 0.86F, 0xFF000000 | fg);
        return y0 + hh;
    }
}
