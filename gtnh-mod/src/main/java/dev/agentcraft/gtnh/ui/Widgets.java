package dev.agentcraft.gtnh.ui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;

/**
 * Small stateful GUI widgets built on {@link Ui} and {@link UiFont} (GUI units; sizes follow the
 * GUI scale automatically because the font atlas is drawn at whatever scale the screen uses):
 * buttons, tabs, a scroll list with a scrollbar, a search field and tooltips. All of them are
 * read-only presentation state: nothing here talks to the server.
 */
public final class Widgets {

    private Widgets() {}

    /** Default GUI type scale (em sizes in GUI units). */
    public static final float BODY = 8.0F, SMALL = 6.6F, TITLE = 10.5F, H1 = 13.0F;

    public static boolean inside(int mx, int my, float x0, float y0, float x1, float y1) {
        return mx >= x0 && mx < x1 && my >= y0 && my < y1;
    }

    // ---- button ----------------------------------------------------------------------------

    public static final class Button {

        public float x0, y0, x1, y1;
        public String label;
        public boolean selected, enabled = true;

        public Button(String label) {
            this.label = label;
        }

        public Button at(float x, float y, float w, float h) {
            x0 = x;
            y0 = y;
            x1 = x + w;
            y1 = y + h;
            return this;
        }

        public boolean hit(int mx, int my) {
            return enabled && inside(mx, my, x0, y0, x1, y1);
        }

        public void draw(Theme th, int mx, int my) {
            boolean hover = hit(mx, my);
            int fill = selected ? th.accent : hover ? th.raised : th.surface;
            Ui.round(x0, y0, x1, y1, (y1 - y0) / 2, 0xFF000000 | fill);
            if (!selected) Ui.roundOutline(x0, y0, x1, y1, (y1 - y0) / 2, 0.75, 0xFF000000 | th.line);
            UiFont f = selected ? UiFont.bold() : UiFont.regular();
            int fg = selected ? (Theme.contrast(Theme.INK, fill) >= 4.5 ? Theme.INK : 0xFFFFFF) : th.text;
            float size = Math.min(BODY, (y1 - y0) * 0.62F);
            f.drawCentered(label, (x0 + x1) / 2, y0 + (y1 - y0 - f.lineHeight(size)) / 2 + 0.3F, size, 0xFF000000 | fg);
        }
    }

    // ---- tabs ------------------------------------------------------------------------------

    /** A row of pill tabs; wraps to more rows when it runs out of width. */
    public static final class Tabs {

        public final List<Button> tabs = new ArrayList<>();
        public int selected;

        public Tabs(String... labels) {
            for (String l : labels) tabs.add(new Button(l));
        }

        /** Lay out from (x, y) within width w; returns the y below the last row. */
        public float layout(float x, float y, float w, float h) {
            float bx = x, by = y;
            for (Button b : tabs) {
                float bw = UiFont.bold()
                    .width(b.label, Math.min(BODY, h * 0.62F)) + h * 1.1F;
                if (bx + bw > x + w && bx > x) {
                    bx = x;
                    by += h + 3;
                }
                b.at(bx, by, bw, h);
                bx += bw + 3;
            }
            return by + h;
        }

        public void draw(Theme th, int mx, int my) {
            for (int i = 0; i < tabs.size(); i++) {
                tabs.get(i).selected = i == selected;
                tabs.get(i)
                    .draw(th, mx, my);
            }
        }

        /** @return true when the click changed the selection */
        public boolean click(int mx, int my) {
            for (int i = 0; i < tabs.size(); i++) {
                if (tabs.get(i)
                    .hit(mx, my) && i != selected) {
                    selected = i;
                    return true;
                }
            }
            return false;
        }
    }

    // ---- scroll list -----------------------------------------------------------------------

    /** Scroll state for a vertical list of variable-height rows (pixel scrolling). */
    public static final class ScrollList {

        public float x0, y0, x1, y1;
        public float offset, content;

        public void bounds(float x0, float y0, float x1, float y1) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
        }

        public float viewH() {
            return y1 - y0;
        }

        public void setContent(float h) {
            content = h;
            clamp();
        }

        public void clamp() {
            offset = Math.max(0, Math.min(offset, Math.max(0, content - viewH())));
        }

        public void wheel(int dWheel, float step) {
            offset += dWheel > 0 ? -step : step;
            clamp();
        }

        public void scrollTo(float top, float bottom) {
            if (top < offset) offset = top;
            else if (bottom > offset + viewH()) offset = bottom - viewH();
            clamp();
        }

        /** Scrollbar on the right edge (only when the content is taller than the view). */
        public void drawBar(Theme th) {
            if (content <= viewH() + 0.5F) return;
            float track = viewH();
            float thumb = Math.max(12, track * viewH() / content);
            float ty = y0 + (track - thumb) * (offset / (content - viewH()));
            Ui.round(x1 - 3, y0, x1, y1, 1.5, 0x60000000 | (th.line & 0xFFFFFF));
            Ui.round(x1 - 3, ty, x1, ty + thumb, 1.5, 0xFF000000 | th.muted);
        }

        public boolean contains(int mx, int my) {
            return inside(mx, my, x0, y0, x1, y1);
        }
    }

    /** Scissor to a GUI rectangle (GUI units) for list clipping. */
    public static void clip(GuiScreen s, float x0, float y0, float x1, float y1) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        double kx = (double) mc.displayWidth / s.width, ky = (double) mc.displayHeight / s.height;
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL11.glScissor(
            (int) Math.floor(x0 * kx),
            (int) Math.floor(mc.displayHeight - y1 * ky),
            (int) Math.ceil((x1 - x0) * kx),
            (int) Math.ceil((y1 - y0) * ky));
    }

    public static void unclip() {
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
    }

    // ---- search field ----------------------------------------------------------------------

    /** Single-line text field drawn with the UI font (Nunito), placeholder, blinking caret. */
    public static final class SearchField {

        public float x0, y0, x1, y1;
        public String text = "";
        public final String placeholder;
        public int maxLen = 60;
        public boolean focused = true;
        private int blink;

        public SearchField(String placeholder) {
            this.placeholder = placeholder;
        }

        public void at(float x, float y, float w, float h) {
            x0 = x;
            y0 = y;
            x1 = x + w;
            y1 = y + h;
        }

        public void tick() {
            blink++;
        }

        public void draw(Theme th) {
            float h = y1 - y0;
            Ui.round(x0, y0, x1, y1, h / 2, 0xFF000000 | th.raised);
            Ui.roundOutline(x0, y0, x1, y1, h / 2, focused ? 1.0 : 0.75, 0xFF000000 | (focused ? th.accent : th.line));
            float size = Math.min(BODY, h * 0.6F);
            UiFont f = UiFont.regular();
            float tx = x0 + h * 0.9F, ty = y0 + (h - f.lineHeight(size)) / 2 + 0.3F;
            // magnifier: a small ring and handle
            float cx = x0 + h * 0.45F, cy = y0 + h * 0.46F, r = h * 0.17F;
            Ui.ring(cx, cy, r * 0.62, r, 0, 1, 0xFF000000 | th.muted);
            Ui.rect(cx + r * 0.55, cy + r * 0.55, cx + r * 1.15, cy + r * 1.15, 0xFF000000 | th.muted);
            float maxW = x1 - tx - h * 0.4F;
            if (text.isEmpty()) {
                f.drawFit(placeholder, tx, ty, size, maxW, 0xFF000000 | th.muted);
            } else {
                // show the tail of long input
                String shown = text;
                while (shown.length() > 1 && f.width(shown, size) > maxW) shown = shown.substring(1);
                float w = f.draw(shown, tx, ty, size, 0xFF000000 | th.text);
                if (focused && (blink / 6) % 2 == 0) Ui.rect(tx + w + 0.5, ty + size * 0.1, tx + w + 1.3, ty + f.lineHeight(size) - size * 0.1, 0xFF000000 | th.text);
            }
            if (text.isEmpty() && focused && (blink / 6) % 2 == 0) Ui.rect(tx, ty + size * 0.1, tx + 0.8, ty + f.lineHeight(size) - size * 0.1, 0xFF000000 | th.text);
        }

        /** @return true when the text changed */
        public boolean key(char ch, int key) {
            if (!focused) return false;
            if (key == Keyboard.KEY_BACK) {
                if (text.isEmpty()) return false;
                text = GuiScreen.isCtrlKeyDown() ? "" : text.substring(0, text.length() - 1);
                return true;
            }
            if (key == Keyboard.KEY_V && GuiScreen.isCtrlKeyDown()) {
                String clip = GuiScreen.getClipboardString();
                if (clip == null) return false;
                StringBuilder b = new StringBuilder(text);
                for (char c : clip.toCharArray()) if (c >= 32 && c != 127 && b.length() < maxLen) b.append(c);
                text = b.toString();
                return true;
            }
            if (ch >= 32 && ch != 127 && ch != '\u00a7' && text.length() < maxLen) {
                text = text + ch;
                return true;
            }
            return false;
        }

        public boolean click(int mx, int my) {
            focused = inside(mx, my, x0, y0, x1, y1);
            return focused;
        }
    }

    // ---- tooltip ---------------------------------------------------------------------------

    /** Wrapped tooltip box next to the mouse, kept on screen. */
    public static void tooltip(Theme th, List<String> lines, int mx, int my, int screenW, int screenH) {
        if (lines == null || lines.isEmpty()) return;
        UiFont f = UiFont.regular();
        float size = SMALL + 0.6F, lh = f.lineHeight(size);
        float maxW = Math.min(220, screenW * 0.45F);
        List<String> wrapped = new ArrayList<>();
        for (String l : lines) {
            List<String> w = TextLayout.wrap(l, maxW, 0, f.measure(size));
            if (w.isEmpty()) wrapped.add("");
            wrapped.addAll(w);
        }
        float w = 0;
        for (String l : wrapped) w = Math.max(w, f.width(l, size));
        float pad = 4, bw = w + 2 * pad, bh = wrapped.size() * lh + 2 * pad;
        float x = mx + 10, y = my - 4;
        if (x + bw > screenW - 2) x = mx - 10 - bw;
        if (y + bh > screenH - 2) y = screenH - 2 - bh;
        if (y < 2) y = 2;
        org.lwjgl.opengl.GL11.glPushMatrix();
        org.lwjgl.opengl.GL11.glTranslatef(0, 0, 300);
        Ui.round(x, y, x + bw, y + bh, 3, 0xF0000000 | th.raised);
        Ui.roundOutline(x, y, x + bw, y + bh, 3, 0.75, 0xFF000000 | th.accent);
        for (int i = 0; i < wrapped.size(); i++) f.draw(wrapped.get(i), x + pad, y + pad + i * lh, size, 0xFF000000 | th.text);
        org.lwjgl.opengl.GL11.glPopMatrix();
    }
}
