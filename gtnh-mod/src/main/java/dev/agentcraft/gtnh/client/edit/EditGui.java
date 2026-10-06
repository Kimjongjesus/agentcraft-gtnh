package dev.agentcraft.gtnh.client.edit;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import dev.agentcraft.gtnh.edit.Json;
import dev.agentcraft.gtnh.ui.TextLayout;
import dev.agentcraft.gtnh.ui.Theme;
import dev.agentcraft.gtnh.ui.Ui;
import dev.agentcraft.gtnh.ui.UiFont;
import dev.agentcraft.gtnh.ui.Widgets;

/**
 * Shared base of the card-6 editor screens, on the card-4 toolkit: immediate-mode buttons (drawn
 * and hit-tested from the same frame), text fields, a scroll list per screen, the server's last
 * result as a status strip, and the lock banner. Every action is a request to the server.
 */
public abstract class EditGui extends GuiScreen {

    protected final Theme th = Theme.DARK;
    private final List<Object[]> hits = new ArrayList<>(); // {x0, y0, x1, y1, Runnable}
    private final List<Object[]> nextHits = new ArrayList<>();
    protected final List<Widgets.SearchField> fields = new ArrayList<>();
    protected final List<Widgets.ScrollList> scrolls = new ArrayList<>();
    protected long seenResult = ClientEdit.resultSeq;
    protected int mx, my;
    private List<String> tip;

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public void updateScreen() {
        for (Widgets.SearchField f : fields) f.tick();
    }

    protected Widgets.SearchField field(String placeholder, int max) {
        Widgets.SearchField f = new Widgets.SearchField(placeholder);
        f.maxLen = max;
        f.focused = false;
        fields.add(f);
        return f;
    }

    protected Widgets.ScrollList scroll() {
        Widgets.ScrollList s = new Widgets.ScrollList();
        scrolls.add(s);
        return s;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partial) {
        mx = mouseX;
        my = mouseY;
        nextHits.clear();
        tip = null;
        draw(partial);
        hits.clear();
        hits.addAll(nextHits);
        if (tip != null) Widgets.tooltip(th, tip, mx, my, width, height);
    }

    protected abstract void draw(float partial);

    protected void tooltip(List<String> lines) {
        tip = lines;
    }

    /** A pill button; returns its width. Disabled buttons draw muted and do nothing. */
    protected float button(String label, float x, float y, float h, boolean enabled, boolean accent, Runnable onClick) {
        float w = UiFont.bold()
            .width(label, Math.min(Widgets.BODY, h * 0.62F)) + h * 1.2F;
        buttonW(label, x, y, w, h, enabled, accent, onClick);
        return w;
    }

    protected void buttonW(String label, float x, float y, float w, float h, boolean enabled, boolean accent, Runnable onClick) {
        Widgets.Button b = new Widgets.Button(label).at(x, y, w, h);
        b.selected = accent && enabled;
        b.enabled = enabled;
        if (!enabled) {
            Ui.round(x, y, x + w, y + h, h / 2, 0xFF000000 | Theme.mix(th.surface, th.bg, 0.5F));
            UiFont.regular()
                .drawCentered(label, x + w / 2, y + (h - UiFont.regular()
                    .lineHeight(Math.min(Widgets.BODY, h * 0.62F))) / 2 + 0.3F, Math.min(Widgets.BODY, h * 0.62F), 0xFF000000 | th.muted);
            return;
        }
        b.draw(th, mx, my);
        if (onClick != null) nextHits.add(new Object[] { x, y, x + w, y + h, onClick });
    }

    /** A clickable region (rows, cards). */
    protected void region(float x0, float y0, float x1, float y1, Runnable onClick) {
        nextHits.add(new Object[] { x0, y0, x1, y1, onClick });
    }

    protected boolean hover(float x0, float y0, float x1, float y1) {
        return Widgets.inside(mx, my, x0, y0, x1, y1);
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        if (button != 0) return;
        for (Widgets.SearchField f : fields) f.click(x, y);
        // last registered = drawn on top
        for (int i = hits.size() - 1; i >= 0; i--) {
            Object[] h = hits.get(i);
            if (x >= (Float) h[0] && x < (Float) h[2] && y >= (Float) h[1] && y < (Float) h[3]) {
                mc.getSoundHandler()
                    .playSound(net.minecraft.client.audio.PositionedSoundRecord.func_147674_a(new net.minecraft.util.ResourceLocation("gui.button.press"), 1.0F));
                ((Runnable) h[4]).run();
                return;
            }
        }
    }

    @Override
    protected void keyTyped(char c, int key) {
        for (Widgets.SearchField f : fields) {
            if (f.focused && key != Keyboard.KEY_ESCAPE) {
                if (key == Keyboard.KEY_RETURN) {
                    onEnter(f);
                    return;
                }
                if (f.key(c, key)) onFieldChanged(f);
                return;
            }
        }
        super.keyTyped(c, key);
    }

    protected void onEnter(Widgets.SearchField f) {}

    protected void onFieldChanged(Widgets.SearchField f) {}

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d == 0) return;
        for (Widgets.ScrollList s : scrolls) if (s.contains(mx, my)) s.wheel(d, 24);
    }

    // ---- shared pieces -----------------------------------------------------------------------

    protected void backdrop() {
        drawRect(0, 0, width, height, 0xD8000000 | (th.bg & 0xFFFFFF));
    }

    /** Title row; returns the y below it. */
    protected float title(String text, String sub) {
        float pad = 8;
        float tw = UiFont.heavy()
            .draw(text, pad, pad, Widgets.H1, 0xFF000000 | th.text);
        if (sub != null) UiFont.regular()
            .draw(sub, pad + tw + 8, pad + 3, Widgets.BODY, 0xFF000000 | th.muted);
        float right = width - pad;
        if (ClientEdit.locked()) {
            right -= Ui.pillRight(UiFont.bold(), "LOCKED \u00b7 /agentcraft edit unlock", right, pad + 1, Widgets.SMALL + 0.6F, th.danger) + 4;
        } else if (!ClientEdit.str("readOnly")
            .isEmpty()) {
                right -= Ui.pillRight(UiFont.bold(), "READ-ONLY layout", right, pad + 1, Widgets.SMALL + 0.6F, th.danger) + 4;
            } else {
                right -= Ui.pillRight(UiFont.bold(), "EDIT MODE", right, pad + 1, Widgets.SMALL + 0.6F, th.accent) + 4;
            }
        return pad + UiFont.heavy()
            .lineHeight(Widgets.H1) + 4;
    }

    /** The server's latest answer (since this screen opened), as a strip at the bottom. */
    protected void statusStrip() {
        java.util.Map<String, Object> r = ClientEdit.result;
        if (r == null || ClientEdit.resultSeq == seenResult) {
            UiFont.regular()
                .draw(hint(), 8, height - 14, Widgets.SMALL, 0xFF000000 | th.muted);
            return;
        }
        boolean ok = Json.bool(r, "ok", false);
        String msg = Json.str(r, "msg", "");
        float h = 13;
        Ui.round(6, height - h - 4, width - 6, height - 4, 4, 0xFF000000 | (ok ? Theme.mix(th.surface, 0x8FA98B, 0.35F) : Theme.mix(th.surface, th.danger, 0.45F)));
        UiFont.bold()
            .drawFit((ok ? "Done: " : "Refused: ") + msg, 11, height - h - 4 + 2.5F, Widgets.SMALL + 0.6F, width - 22, 0xFF000000 | th.text);
    }

    protected String hint() {
        return "Every change is undoable (Layouts tab, or /agentcraft edit undo). Esc closes.";
    }

    protected float wrapped(String text, float x, float y, float w, float size, int color, int maxLines) {
        UiFont f = UiFont.regular();
        List<String> l = TextLayout.wrap(text, w, maxLines, f.measure(size));
        for (int i = 0; i < l.size(); i++) f.draw(l.get(i), x, y + i * f.lineHeight(size), size, 0xFF000000 | color);
        return y + l.size() * f.lineHeight(size);
    }

    protected void card(float x0, float y0, float x1, float y1) {
        Ui.round(x0, y0, x1, y1, 4, 0xFF000000 | th.surface);
        Ui.roundOutline(x0, y0, x1, y1, 4, 0.75, 0xFF000000 | th.line);
    }

    protected float sectionLabel(String text, float x, float y) {
        UiFont.bold()
            .draw(text, x, y, Widgets.SMALL + 0.8F, 0xFF000000 | th.muted);
        return y + UiFont.bold()
            .lineHeight(Widgets.SMALL + 0.8F) + 2;
    }
}
