package dev.agentcraft.gtnh.write.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;

import dev.agentcraft.gtnh.ui.TextLayout;
import dev.agentcraft.gtnh.ui.Theme;
import dev.agentcraft.gtnh.ui.Ui;
import dev.agentcraft.gtnh.ui.UiFont;
import dev.agentcraft.gtnh.ui.Widgets;

/**
 * Drawing helpers shared by every write screen and the footer strips, built on the core toolkit
 * ({@link Ui}, {@link UiFont}, {@link Widgets}, {@link Theme}): buttons (normal / primary / danger),
 * a text box, the write status strip with its one-click lock button, and a tooltip. Client only. Nothing
 * here decides anything: every action ends in a {@link WriteClient} call and the server decides again.
 */
final class WUi {

    private WUi() {}

    static final Theme TH = Theme.DARK;
    static final int GREEN = 0x7BC47F, AMBER = 0xE8C547, ORANGE = 0xD97757, RED = 0xE5645A;

    /** Dev automation only: the buttons drawn in the last frames, by name (see WriteDevAuto). Empty otherwise. */
    static final boolean DEV = System.getProperty("agentcraft.dev.writeAuto") != null;
    static final Map<String, float[]> HITS = new LinkedHashMap<String, float[]>();

    static void reg(String name, float x0, float y0, float x1, float y1) {
        if (DEV && name != null) HITS.put(name, new float[] { x0, y0, x1, y1 });
    }

    // ---- tooltip (one per frame, flushed by the screen at the end of its draw) ----------------------------

    private static volatile List<String> tip;
    private static int tipX, tipY;

    static void tip(String text, int mx, int my) {
        if (text == null || text.isEmpty()) return;
        List<String> l = new ArrayList<String>();
        l.add(text);
        tip = l;
        tipX = mx;
        tipY = my;
    }

    static void flushTip() {
        List<String> t = tip;
        tip = null;
        if (t == null) return;
        GuiScreen s = Minecraft.getMinecraft().currentScreen;
        if (s != null) Widgets.tooltip(TH, t, tipX, tipY, s.width, s.height);
    }

    // ---- button ------------------------------------------------------------------------------------------------

    static final int NORMAL = 0, PRIMARY = 1, DANGER = 2;

    static final class Btn {

        final String name;
        String label, tip;
        int style = NORMAL;
        boolean enabled = true;
        Runnable action;
        float x0, y0, x1, y1;

        Btn(String name, String label) {
            this.name = name;
            this.label = label;
        }

        Btn style(int s) {
            style = s;
            return this;
        }

        Btn on(Runnable r) {
            action = r;
            return this;
        }

        /** Enabled or not; a disabled button keeps a tooltip saying why. */
        Btn enable(boolean e, String why) {
            enabled = e;
            tip = e ? tip : why;
            return this;
        }

        Btn tip(String t) {
            tip = t;
            return this;
        }

        static float widthFor(String label, float h) {
            return UiFont.bold()
                .width(label, Math.min(Widgets.BODY, h * 0.62F)) + h * 1.2F;
        }

        Btn at(float x, float y, float w, float h) {
            x0 = x;
            y0 = y;
            x1 = x + w;
            y1 = y + h;
            return this;
        }

        boolean hit(int mx, int my) {
            return Widgets.inside(mx, my, x0, y0, x1, y1);
        }

        void draw(int mx, int my) {
            boolean hover = hit(mx, my);
            float h = y1 - y0;
            int fill;
            int fg;
            if (!enabled) {
                fill = TH.surface;
                fg = Theme.mix(TH.muted, TH.surface, 0.35F);
            } else if (style == PRIMARY) {
                fill = hover ? Theme.mix(TH.accent, 0xFFFFFF, 0.18F) : TH.accent;
                fg = Theme.contrast(Theme.INK, fill) >= 4.5 ? Theme.INK : 0xFFFFFF;
            } else if (style == DANGER) {
                fill = hover ? Theme.mix(RED, 0xFFFFFF, 0.15F) : RED;
                fg = Theme.contrast(Theme.INK, fill) >= 4.5 ? Theme.INK : 0xFFFFFF;
            } else {
                fill = hover ? TH.raised : TH.surface;
                fg = TH.text;
            }
            Ui.round(x0, y0, x1, y1, h / 2, 0xFF000000 | fill);
            if (style == NORMAL || !enabled) Ui.roundOutline(x0, y0, x1, y1, h / 2, 0.75, 0xFF000000 | (enabled && hover ? TH.accent : TH.line));
            UiFont f = style == NORMAL ? UiFont.regular() : UiFont.bold();
            float size = Math.min(Widgets.BODY, h * 0.62F);
            float tw = Math.min(f.width(label, size), x1 - x0 - h);
            f.drawFit(label, x0 + (x1 - x0 - tw) / 2, y0 + (h - f.lineHeight(size)) / 2 + 0.3F, size, x1 - x0 - h, 0xFF000000 | fg);
            reg(name, x0, y0, x1, y1);
            if (hover && tip != null) WUi.tip(tip, mx, my);
        }
    }

    /** Lay buttons out left to right from x, all h high; returns the x after the last one. */
    static float row(List<Btn> btns, float x, float y, float h, float gap) {
        for (Btn b : btns) {
            float w = Btn.widthFor(b.label, h);
            b.at(x, y, w, h);
            x += w + gap;
        }
        return x;
    }

    // ---- text box ----------------------------------------------------------------------------------------------

    static final class Field {

        final String name;
        float x0, y0, x1, y1;
        String text = "";
        String placeholder = "";
        int maxLen = 200;
        boolean focused, multi, digits;
        private int blink;

        Field(String name, String placeholder, int maxLen) {
            this.name = name;
            this.placeholder = placeholder;
            this.maxLen = maxLen;
        }

        Field at(float x, float y, float w, float h) {
            x0 = x;
            y0 = y;
            x1 = x + w;
            y1 = y + h;
            return this;
        }

        void tick() {
            blink++;
        }

        void draw() {
            float h = y1 - y0;
            Ui.round(x0, y0, x1, y1, multi ? 4 : h / 2, 0xFF000000 | TH.raised);
            Ui.roundOutline(x0, y0, x1, y1, multi ? 4 : h / 2, focused ? 1.0 : 0.75, 0xFF000000 | (focused ? TH.accent : TH.line));
            UiFont f = UiFont.regular();
            float size = multi ? Widgets.BODY : Math.min(Widgets.BODY, h * 0.6F);
            float pad = multi ? 4 : h * 0.5F;
            float tx = x0 + pad, maxW = x1 - tx - pad;
            boolean caret = focused && (blink / 6) % 2 == 0;
            if (!multi) {
                float ty = y0 + (h - f.lineHeight(size)) / 2 + 0.3F;
                if (text.isEmpty()) {
                    f.drawFit(placeholder, tx, ty, size, maxW, 0xFF000000 | TH.muted);
                    if (caret) Ui.rect(tx, ty + size * 0.1, tx + 0.8, ty + f.lineHeight(size) - size * 0.1, 0xFF000000 | TH.text);
                } else {
                    String shown = text.replace('\n', ' ');
                    while (shown.length() > 1 && f.width(shown, size) > maxW - 2) shown = shown.substring(1);
                    float w = f.draw(shown, tx, ty, size, 0xFF000000 | TH.text);
                    if (caret) Ui.rect(tx + w + 0.5, ty + size * 0.1, tx + w + 1.3, ty + f.lineHeight(size) - size * 0.1, 0xFF000000 | TH.text);
                }
            } else {
                int rows = Math.max(1, (int) ((h - 6) / f.lineHeight(size)));
                List<String> lines = new ArrayList<String>();
                if (text.isEmpty()) {
                    lines.add("");
                } else {
                    for (String para : (text + "\u0000").split("\n", -1)) {
                        String p = para.replace("\u0000", "");
                        List<String> w = TextLayout.wrap(p, maxW, 0, f.measure(size));
                        if (w.isEmpty()) lines.add("");
                        else lines.addAll(w);
                    }
                }
                int from = Math.max(0, lines.size() - rows);
                float ty = y0 + 3;
                if (text.isEmpty()) f.drawFit(placeholder, tx, ty, size, maxW, 0xFF000000 | TH.muted);
                float lastX = tx, lastY = ty;
                for (int i = from; i < lines.size(); i++) {
                    float w = f.draw(lines.get(i), tx, ty, size, 0xFF000000 | TH.text);
                    lastX = tx + w;
                    lastY = ty;
                    ty += f.lineHeight(size);
                }
                if (caret) Ui.rect(lastX + 0.5, lastY + size * 0.1, lastX + 1.3, lastY + f.lineHeight(size) - size * 0.1, 0xFF000000 | TH.text);
            }
            reg(name, x0, y0, x1, y1);
        }

        /** @return true when the text changed */
        boolean key(char ch, int key) {
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
                for (char c : clip.toCharArray()) if (accept(c) && b.length() < maxLen) b.append(c);
                text = b.toString();
                return true;
            }
            if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
                if (multi && text.length() < maxLen) {
                    text = text + "\n";
                    return true;
                }
                return false;
            }
            if (accept(ch) && text.length() < maxLen) {
                text = text + ch;
                return true;
            }
            return false;
        }

        private boolean accept(char c) {
            if (digits) return c >= '0' && c <= '9';
            return (c >= 32 && c != 127 && c != '\u00a7') || (multi && c == '\n');
        }

        boolean click(int mx, int my) {
            focused = Widgets.inside(mx, my, x0, y0, x1, y1);
            return focused;
        }
    }

    // ---- status strip ----------------------------------------------------------------------------------------

    /** "armed" / "disarmed" / "locked" for the pill. */
    static String stateWord() {
        return ClientWriteState.locked ? "LOCKED" : !ClientWriteState.armed ? "DISARMED" : "ARMED";
    }

    static int stateColor() {
        return ClientWriteState.locked ? RED : !ClientWriteState.armed ? ORANGE : GREEN;
    }

    /** The sentence next to the pills: "writes armed", "writes disarmed: <reason>", "writes locked: <reason>". */
    static String stateText() {
        if (ClientWriteState.locked) return "writes locked: " + (ClientWriteState.lockInfo.isEmpty() ? "locked" : ClientWriteState.lockInfo);
        if (!ClientWriteState.armed) return "writes disarmed: " + ClientWriteState.reason;
        return "writes armed";
    }

    /**
     * The status strip in the rect (x0, y0, x1, y0 + {@link #STRIP_H}): state pill, DRY RUN badge, the
     * sentence, and on the right the one-click Lock button (no confirmation) and, when {@code actions} is
     * not null, the Write actions button. The buttons are returned so the caller can hit-test them.
     */
    static final float STRIP_H = 14;

    static void strip(float x0, float y0, float x1, int mx, int my, Btn lock, Btn actions) {
        UiFont reg = UiFont.regular(), bold = UiFont.bold();
        float h = STRIP_H;
        float sz = Widgets.SMALL + 0.4F;
        // the buttons on the right first, so the pills and the sentence know how much room they have
        float right = x1;
        lock.label = ClientWriteState.locked ? "Locked" : "Lock writes";
        lock.style(ClientWriteState.locked ? NORMAL : DANGER);
        lock.enable(!ClientWriteState.locked, "Already locked. Unlock: the owner in game, or the server console.");
        if (!ClientWriteState.locked) lock.tip("One click, no confirmation: stops every write at once.");
        float lw = Btn.widthFor(lock.label, h);
        lock.at(right - lw, y0, lw, h);
        right -= lw + 3;
        if (actions != null) {
            float aw = Btn.widthFor(actions.label, h);
            actions.at(right - aw, y0, aw, h);
            right -= aw + 3;
        }
        // pills (the state always; the DRY RUN badge whenever the control service says so), then the sentence
        float x = x0;
        x += Ui.pill(bold, stateWord(), x, y0 + 1, sz, stateColor()) + 3;
        if (ClientWriteState.dryRun && x + pillWidth(bold, "DRY RUN", sz) <= right) x += Ui.pill(bold, "DRY RUN", x, y0 + 1, sz, AMBER) + 3;
        if (ClientWriteState.overridden && x + pillWidth(reg, "dev override", sz) + 70 <= right) x += Ui.pill(reg, "dev override", x, y0 + 1, sz, TH.raised) + 3;
        String t = stateText();
        float maxW = right - x - 2;
        boolean cut = false;
        if (maxW > 40) {
            reg.drawFit(t, x, y0 + (h - reg.lineHeight(Widgets.SMALL + 0.6F)) / 2, Widgets.SMALL + 0.6F, maxW, 0xFF000000 | TH.text);
            cut = reg.width(t, Widgets.SMALL + 0.6F) > maxW;
        }
        if (actions != null) actions.draw(mx, my);
        lock.draw(mx, my);
        if (maxW > 40 && cut && Widgets.inside(mx, my, x, y0, right, y0 + h)) tip(t, mx, my);
    }

    private static float pillWidth(UiFont f, String text, float size) {
        return f.width(text, size) + 2 * size * 0.55F;
    }

    /** What the user should be told about the newest result, or null when it is old (30 s). */
    static String recentResultLine() {
        ClientWriteState.ResultInfo r = ClientWriteState.lastResult();
        if (r == null || System.currentTimeMillis() - r.atMs > 30000L) return null;
        return label(r.capability) + ": " + FormLogic.resultLine(r.status, r.error, r.dryRun);
    }

    static int resultColor(ClientWriteState.ResultInfo r) {
        if (r == null) return TH.muted;
        if ("applied".equals(r.status) || "queued".equals(r.status) || "prompted".equals(r.status)) return Theme.readable(GREEN, TH.surface);
        if ("unknown".equals(r.status) || "cancelled".equals(r.status)) return Theme.readable(AMBER, TH.surface);
        return Theme.readable(RED, TH.surface);
    }

    static String label(String cap) {
        if (cap == null) return "request";
        switch (cap) {
            case "card.dispatch":
                return "dispatch";
            case "card.create":
                return "new card";
            case "card.edit":
                return "card edit";
            case "decision.answer":
                return "answer";
            case "agent.chat":
                return "chat";
            case "agent.ask":
                return "ask";
            case "service.restart":
                return "restart";
            case "cron.run":
                return "job";
            default:
                return cap;
        }
    }

    static GuiScreen current() {
        return Minecraft.getMinecraft().currentScreen;
    }
}
