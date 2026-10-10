package dev.agentcraft.gtnh.write.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;

import dev.agentcraft.gtnh.ui.TextLayout;
import dev.agentcraft.gtnh.ui.Ui;
import dev.agentcraft.gtnh.ui.UiFont;
import dev.agentcraft.gtnh.ui.Widgets;

/**
 * Base of the write screens: a centred panel with a header, a body the subclass draws, and at the bottom the
 * write status strip with the one-click Lock button (so every write screen shows the state and can stop
 * writes). Buttons are rebuilt every frame in {@link #body}; a click uses the ones of the last frame.
 */
abstract class WriteScreen extends GuiScreen {

    protected final GuiScreen parent;
    protected final List<WUi.Btn> btns = new ArrayList<WUi.Btn>();
    protected final WUi.Btn lock = new WUi.Btn("lock", "Lock writes").on(new Runnable() {

        @Override
        public void run() {
            WriteClient.lock("locked from the game screen");
        }
    });
    protected float px0, py0, px1, py1, bx0, by0, bx1, by1;
    /** the clock of the screen (ms) when it was opened; for fade-ins and "sent a moment ago" logic */
    protected final long openedAt = System.currentTimeMillis();

    WriteScreen(GuiScreen parent) {
        this.parent = parent;
    }

    abstract String title();

    /** Header note on the right (e.g. a DRY RUN badge); null = none. */
    String note() {
        return null;
    }

    abstract int panelWidth();

    abstract int panelHeight();

    /** Draw the body inside (bx0, by0, bx1, by1) and fill {@link #btns}. */
    abstract void body(int mx, int my);

    /** Called from {@link #updateScreen} on the client thread. */
    void tick() {}

    /** Esc / the Close button. */
    void close() {
        mc.displayGuiScreen(parent);
    }

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
        tick();
    }

    @Override
    public void drawScreen(int mx, int my, float partial) {
        drawRect(0, 0, width, height, 0xE8000000 | (WUi.TH.bg & 0xFFFFFF));
        float w = Math.min(width - 12, panelWidth()), h = Math.min(height - 12, panelHeight());
        px0 = (width - w) / 2;
        py0 = (height - h) / 2;
        px1 = px0 + w;
        py1 = py0 + h;
        UiFont bold = UiFont.bold(), reg = UiFont.regular(), heavy = UiFont.heavy();
        float bodyTop = Ui.panel(heavy, reg, WUi.TH, px0, py0, px1, py1, 5, title(), note(), WUi.TH.accent, Widgets.TITLE);
        bx0 = px0 + 8;
        bx1 = px1 - 8;
        by0 = bodyTop + 6;
        by1 = py1 - 8 - WUi.STRIP_H - 5;
        btns.clear();
        body(mx, my);
        // status strip + lock, always at the bottom of the panel
        Ui.rect(px0 + 8, py1 - 8 - WUi.STRIP_H - 3, px1 - 8, py1 - 8 - WUi.STRIP_H - 2.5F, 0xFF000000 | WUi.TH.line);
        WUi.strip(px0 + 8, py1 - 8 - WUi.STRIP_H, px1 - 8, mx, my, lock, null);
        for (WUi.Btn b : btns) b.draw(mx, my);
        WUi.flushTip();
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) {
        super.mouseClicked(mx, my, button);
        if (button != 0) return;
        if (lock.enabled && lock.hit(mx, my)) {
            lock.action.run();
            return;
        }
        for (WUi.Btn b : btns) {
            if (!b.hit(mx, my)) continue;
            if (b.enabled && b.action != null) b.action.run();
            else if (!b.enabled && b.tip != null) ClientWriteState.localRefusal(b.name, b.tip);
            return;
        }
        clicked(mx, my);
    }

    /** A click that hit no button (text boxes). */
    void clicked(int mx, int my) {}

    @Override
    protected void keyTyped(char ch, int key) {
        if (key == Keyboard.KEY_ESCAPE) {
            close();
            return;
        }
        typed(ch, key);
    }

    void typed(char ch, int key) {}

    // ---- drawing helpers for subclasses ----------------------------------------------------------------------

    /** Draws wrapped text; returns the y after it. */
    protected float para(String text, float x, float y, float w, float size, UiFont f, int rgb, int maxLines) {
        List<String> lines = TextLayout.wrap(text, w, maxLines, f.measure(size));
        for (String l : lines) {
            f.draw(l, x, y, size, 0xFF000000 | rgb);
            y += f.lineHeight(size);
        }
        return y;
    }

    /** One result line (colour by outcome) at (x, y); returns the y after it. */
    protected float resultLine(float x, float y, float w) {
        ClientWriteState.ResultInfo r = ClientWriteState.lastResult();
        String s = WUi.recentResultLine();
        if (s == null) return y;
        UiFont reg = UiFont.regular();
        reg.drawFit(s, x, y, Widgets.SMALL + 0.6F, w, 0xFF000000 | WUi.resultColor(r));
        return y + reg.lineHeight(Widgets.SMALL + 0.6F);
    }
}
