package dev.agentcraft.gtnh.ui;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;

import dev.agentcraft.gtnh.AgentCraftGTNH;

/**
 * A TrueType font (bundled OFL .ttf) rasterized once with java.awt into a mipmapped alpha texture
 * atlas, drawn as textured quads at any size. Glyphs are rendered at {@link #PX} pixels per em, so
 * text stays crisp up close on a wall and in a GUI at any GUI scale, and the trilinear mipmaps keep
 * it clean (not shimmering) at a distance. No framebuffer objects or shaders are used.
 *
 * <p>
 * If java.awt cannot load or rasterize the font on a client JVM, the font falls back to the vanilla
 * Minecraft font through the same API (scaled to the requested size), so screens never break.
 *
 * <p>
 * Units: every size and coordinate is in the caller's current GL units (GUI units in a screen,
 * canvas units on an in-world panel). {@code size} is the em size; a line is {@link #lineHeight}.
 */
public final class UiFont {

    /** Rasterization size (pixels per em) of the atlas. */
    public static final int PX = 64;
    private static final int PAD = 8, ATLAS_W = 2048;
    /** Extra glyphs beyond ASCII and Latin-1. */
    private static final String EXTRA = "\u2022\u00b7\u2192\u2190\u2191\u2193\u2026\u2713\u2717\u25cf\u25cb\u2605\u2013\u2014\u2018\u2019\u201c\u201d\u20ac\u2116\u00d7\u2212\u25b2\u25bc\u25b6\u25c0";

    private static final Map<String, UiFont> FONTS = new HashMap<>();

    public static UiFont regular() {
        return get("Nunito-Regular.ttf");
    }

    public static UiFont bold() {
        return get("Nunito-Bold.ttf");
    }

    public static UiFont heavy() {
        return get("Nunito-ExtraBold.ttf");
    }

    public static UiFont mono() {
        return get("JetBrainsMono-Regular.ttf");
    }

    public static synchronized UiFont get(String file) {
        UiFont f = FONTS.get(file);
        if (f == null) {
            f = new UiFont(file);
            FONTS.put(file, f);
        }
        return f;
    }

    /** True when every bundled font loaded into an atlas (false = vanilla font fallback). */
    public static boolean allTrueType() {
        for (UiFont f : FONTS.values()) if (!f.ok) return false;
        return true;
    }

    // ---------------------------------------------------------------------------------------

    private static final class Glyph {

        float advance; // in em * PX units
        int u, v, w, h; // atlas rect (pixels), including PAD
        float ox, oy; // offset of the rect's top-left from the pen position at the baseline
    }

    public final String file;
    private boolean ok, tried;
    private int texture = -1, atlasH;
    private float ascent, descent, leading; // per PX
    private final Map<Character, Glyph> glyphs = new HashMap<>();
    private Glyph fallbackGlyph;
    public String error = "";

    private UiFont(String file) {
        this.file = file;
    }

    private void ensure() {
        if (tried) return;
        tried = true;
        try {
            load();
            ok = true;
            AgentCraftGTNH.LOG.info("UI font {}: {} glyphs, atlas {}x{}", file, glyphs.size(), ATLAS_W, atlasH);
        } catch (Throwable t) {
            ok = false;
            error = t.toString();
            AgentCraftGTNH.LOG.warn("UI font {} unavailable ({}); using the vanilla font", file, t.toString());
        }
    }

    private void load() throws Exception {
        Font base;
        try (InputStream in = Minecraft.getMinecraft()
            .getResourceManager()
            .getResource(new ResourceLocation(AgentCraftGTNH.MODID, "fonts/" + file))
            .getInputStream()) {
            base = Font.createFont(Font.TRUETYPE_FONT, in);
        }
        Font font = base.deriveFont((float) PX);
        FontRenderContext frc = new FontRenderContext(null, true, true);
        java.awt.font.LineMetrics lm = font.getLineMetrics("Hg", frc);
        ascent = lm.getAscent();
        descent = lm.getDescent();
        leading = lm.getLeading();

        StringBuilder set = new StringBuilder();
        for (char c = 32; c < 127; c++) set.append(c);
        for (char c = 160; c <= 255; c++) set.append(c);
        set.append(EXTRA);

        // measure + pack (shelf packing, rows of glyph height)
        Map<Character, GlyphVector> vec = new HashMap<>();
        int x = 0, y = 0, rowH = 0;
        for (int i = 0; i < set.length(); i++) {
            char c = set.charAt(i);
            if (c != ' ' && c != '\u00a0' && !font.canDisplay(c)) continue;
            GlyphVector gv = font.createGlyphVector(frc, String.valueOf(c));
            Rectangle b = gv.getPixelBounds(frc, 0, 0);
            Glyph g = new Glyph();
            g.advance = (float) gv.getGlyphMetrics(0)
                .getAdvanceX();
            int w = Math.max(1, b.width) + 2 * PAD, h = Math.max(1, b.height) + 2 * PAD;
            if (x + w > ATLAS_W) {
                x = 0;
                y += rowH;
                rowH = 0;
            }
            g.u = x;
            g.v = y;
            g.w = w;
            g.h = h;
            g.ox = b.x - PAD;
            g.oy = b.y - PAD;
            x += w;
            rowH = Math.max(rowH, h);
            glyphs.put(c, g);
            vec.put(c, gv);
        }
        int needH = y + rowH;
        atlasH = 64;
        while (atlasH < needH) atlasH <<= 1;

        BufferedImage img = new BufferedImage(ATLAS_W, atlasH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setColor(java.awt.Color.WHITE);
        for (Map.Entry<Character, GlyphVector> e : vec.entrySet()) {
            Glyph g = glyphs.get(e.getKey());
            g2.drawGlyphVector(e.getValue(), g.u - g.ox, g.v - g.oy);
        }
        g2.dispose();
        fallbackGlyph = glyphs.containsKey('?') ? glyphs.get('?') : glyphs.get(' ');

        // alpha channel + box-filtered mip chain, uploaded as GL_ALPHA (white text, vertex colour tints)
        int[] argb = img.getRGB(0, 0, ATLAS_W, atlasH, null, 0, ATLAS_W);
        byte[] level = new byte[ATLAS_W * atlasH];
        for (int i = 0; i < argb.length; i++) level[i] = (byte) (argb[i] >>> 24);
        texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        int w = ATLAS_W, h = atlasH, lv = 0;
        while (true) {
            ByteBuffer buf = BufferUtils.createByteBuffer(level.length);
            buf.put(level)
                .flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, lv, GL11.GL_ALPHA, w, h, 0, GL11.GL_ALPHA, GL11.GL_UNSIGNED_BYTE, buf);
            if (w == 1 && h == 1) break;
            int nw = Math.max(1, w / 2), nh = Math.max(1, h / 2);
            byte[] next = new byte[nw * nh];
            for (int yy = 0; yy < nh; yy++) {
                for (int xx = 0; xx < nw; xx++) {
                    int x0 = Math.min(w - 1, xx * 2), x1 = Math.min(w - 1, xx * 2 + 1);
                    int y0 = Math.min(h - 1, yy * 2), y1 = Math.min(h - 1, yy * 2 + 1);
                    int s = (level[y0 * w + x0] & 0xFF) + (level[y0 * w + x1] & 0xFF) + (level[y1 * w + x0] & 0xFF)
                        + (level[y1 * w + x1] & 0xFF);
                    next[yy * nw + xx] = (byte) ((s + 2) / 4);
                }
            }
            level = next;
            w = nw;
            h = nh;
            lv++;
        }
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, lv);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        try {
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS, -0.35F); // a touch sharper
        } catch (Throwable ignored) {}
    }

    // ---- metrics ---------------------------------------------------------------------------

    public boolean isTrueType() {
        ensure();
        return ok;
    }

    private static FontRenderer vanilla() {
        return Minecraft.getMinecraft().fontRenderer;
    }

    private Glyph glyph(char c) {
        Glyph g = glyphs.get(c);
        return g != null ? g : fallbackGlyph;
    }

    /** Width of {@code s} at em size {@code size}. */
    public float width(String s, float size) {
        ensure();
        if (s == null || s.isEmpty()) return 0;
        if (!ok) return vanilla().getStringWidth(s) * size / 9.0F;
        float w = 0;
        for (int i = 0; i < s.length(); i++) w += glyph(s.charAt(i)).advance;
        return w * size / PX;
    }

    /** Distance between baselines of consecutive lines. */
    public float lineHeight(float size) {
        ensure();
        if (!ok) return size * 10.0F / 9.0F;
        return (ascent + descent + leading) * size / PX;
    }

    public float ascent(float size) {
        ensure();
        return ok ? ascent * size / PX : size * 7.0F / 9.0F;
    }

    /** Height of capital letters (for vertical centring of single lines). */
    public float capHeight(float size) {
        ensure();
        return ok ? 0.705F * size : size * 7.0F / 9.0F;
    }

    public TextLayout.Measure measure(final float size) {
        return s -> width(s, size);
    }

    // ---- drawing ---------------------------------------------------------------------------

    /**
     * Draw {@code s} with the top of its line box at (x, y); returns the advance width. Leaves
     * TEXTURE_2D enabled and blending on (the panels draw back-to-front with depth writes off).
     */
    public float draw(String s, float x, float y, float size, int argb) {
        ensure();
        if (s == null || s.isEmpty()) return 0;
        if ((argb & 0xFF000000) == 0) argb |= 0xFF000000;
        if (!ok) return drawVanilla(s, x, y, size, argb);
        float k = size / PX;
        float base = y + ascent * k;
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.setColorRGBA((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
        float pen = x;
        float iw = 1.0F / ATLAS_W, ih = 1.0F / atlasH;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            Glyph g = glyph(c);
            if (c != ' ' && g != null) {
                double x0 = pen + g.ox * k, y0 = base + g.oy * k, x1 = x0 + g.w * k, y1 = y0 + g.h * k;
                double u0 = g.u * iw, v0 = g.v * ih, u1 = (g.u + g.w) * iw, v1 = (g.v + g.h) * ih;
                t.addVertexWithUV(x0, y1, 0, u0, v1);
                t.addVertexWithUV(x1, y1, 0, u1, v1);
                t.addVertexWithUV(x1, y0, 0, u1, v0);
                t.addVertexWithUV(x0, y0, 0, u0, v0);
            }
            if (g != null) pen += g.advance * k;
        }
        t.draw();
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        // the next vanilla draw re-binds its own texture; leave ours unbound to be safe
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return pen - x;
    }

    private float drawVanilla(String s, float x, float y, float size, int argb) {
        FontRenderer fr = vanilla();
        float k = size / 9.0F;
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y + size * 0.1F, 0);
        GL11.glScalef(k, k, 1);
        fr.drawString(s, 0, 0, argb);
        GL11.glPopMatrix();
        return fr.getStringWidth(s) * k;
    }

    /** Right-aligned at {@code xRight}. */
    public float drawRight(String s, float xRight, float y, float size, int argb) {
        float w = width(s, size);
        draw(s, xRight - w, y, size, argb);
        return w;
    }

    public float drawCentered(String s, float cx, float y, float size, int argb) {
        float w = width(s, size);
        draw(s, cx - w / 2, y, size, argb);
        return w;
    }

    /** Draw ellipsized to {@code maxW}. */
    public float drawFit(String s, float x, float y, float size, float maxW, int argb) {
        return draw(TextLayout.ellipsize(s, maxW, measure(size)), x, y, size, argb);
    }
}
