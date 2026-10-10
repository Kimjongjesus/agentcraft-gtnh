package dev.agentcraft.gtnh.write.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import dev.agentcraft.gtnh.client.BoardView;
import dev.agentcraft.gtnh.ui.TextLayout;
import dev.agentcraft.gtnh.ui.Theme;
import dev.agentcraft.gtnh.ui.Ui;
import dev.agentcraft.gtnh.ui.UiFont;
import dev.agentcraft.gtnh.ui.Widgets;

/**
 * A small chat window with one agent, opened by right-clicking its NPC (the owner only; the server decides).
 * Messages go out as {@code agent.chat} with a stable conversation id so the agent keeps the thread; the
 * agent's answer arrives as chat lines. The words you send are kept per agent while the game runs. Chat is
 * off in the shipped policy; with it off this window says so and sends nothing.
 */
public class GuiChatWindow extends WriteScreen {

    private static final class Mine {

        final String text;
        final long at;

        Mine(String text, long at) {
            this.text = text;
            this.at = at;
        }
    }

    private static final Map<String, List<Mine>> SENT = new HashMap<String, List<Mine>>();

    private final String agent;
    private final WUi.Field input = new WUi.Field("field.chat", "Say something\u2026", 1500);
    private final Widgets.ScrollList scroll = new Widgets.ScrollList();
    private long seenSeq = -1;
    private long lastSentAt;
    private boolean stick = true;

    public GuiChatWindow(String agent, GuiScreen parent) {
        super(parent);
        this.agent = agent == null ? "" : agent;
        input.focused = true;
    }

    @Override
    String title() {
        return "Chat with " + BoardView.agentName(agent);
    }

    @Override
    int panelWidth() {
        return 330;
    }

    @Override
    int panelHeight() {
        return 260;
    }

    static void clearHistory() {
        SENT.clear();
    }

    private String blocked() {
        if (!ClientWriteState.canWrite()) return WUi.stateText();
        if (!ClientWriteState.policy.usable("agent.chat")) return "Chat is not offered or is switched off in the policy.";
        if (!ClientWriteState.policy.agents.contains(agent)) return "The policy does not offer chat with " + agent + ".";
        return null;
    }

    private void send() {
        String t = input.text.trim();
        if (t.isEmpty() || blocked() != null) return;
        if (WriteClient.chat(agent, FormLogic.conversationId(agent), t)) {
            List<Mine> l = SENT.get(agent);
            if (l == null) {
                l = new ArrayList<Mine>();
                SENT.put(agent, l);
            }
            l.add(new Mine(t, System.currentTimeMillis()));
            lastSentAt = System.currentTimeMillis();
            input.text = "";
            stick = true;
        }
    }

    @Override
    void tick() {
        input.tick();
    }

    @Override
    void body(int mx, int my) {
        UiFont reg = UiFont.regular(), bold = UiFont.bold();
        float x = bx0, w = bx1 - bx0;
        float inputH = 16;
        float areaBottom = by1 - inputH - 18;
        scroll.bounds(x, by0, bx1, areaBottom);
        Ui.round(x, by0, bx1, areaBottom, 4, 0xFF000000 | WUi.TH.surface);
        // merge both sides of the conversation by time
        final List<Object[]> msgs = new ArrayList<Object[]>(); // [mine?, text, at]
        List<Mine> mine = SENT.get(agent);
        if (mine != null) for (Mine m : mine) msgs.add(new Object[] { Boolean.TRUE, m.text, Long.valueOf(m.at) });
        List<ClientWriteState.ChatLine> theirs = ClientWriteState.chatLines(agent);
        long lastTheirs = 0;
        for (ClientWriteState.ChatLine c : theirs) {
            msgs.add(new Object[] { Boolean.FALSE, c.text, Long.valueOf(c.atMs) });
            lastTheirs = Math.max(lastTheirs, c.atMs);
        }
        Collections.sort(msgs, new Comparator<Object[]>() {

            @Override
            public int compare(Object[] a, Object[] b) {
                return Long.compare((Long) a[2], (Long) b[2]);
            }
        });
        float bubbleMax = (w - 16) * 0.78F, size = Widgets.BODY, lh = reg.lineHeight(size);
        float total = 4;
        List<float[]> geo = new ArrayList<float[]>();
        List<List<String>> wrapped = new ArrayList<List<String>>();
        for (Object[] m : msgs) {
            List<String> lines = new ArrayList<String>();
            for (String para : ((String) m[1]).split("\n")) {
                List<String> wl = TextLayout.wrap(para, bubbleMax - 10, 0, reg.measure(size));
                if (wl.isEmpty()) lines.add("");
                else lines.addAll(wl);
            }
            float bw = 0;
            for (String l : lines) bw = Math.max(bw, reg.width(l, size));
            float bh = lines.size() * lh + 8;
            geo.add(new float[] { total, bh, bw + 10 });
            wrapped.add(lines);
            total += bh + 4;
        }
        boolean waiting = lastSentAt > lastTheirs && System.currentTimeMillis() - lastSentAt < 120000L;
        if (waiting) total += 16;
        scroll.setContent(total + 2);
        long seq = ClientWriteState.chatSeq.get() + msgs.size();
        if (seq != seenSeq || stick) {
            scroll.offset = Math.max(0, scroll.content - scroll.viewH());
            seenSeq = seq;
            stick = false;
        }
        Widgets.clip(this, x, by0, bx1, areaBottom);
        if (msgs.isEmpty()) {
            String blockedNow = blocked();
            reg.draw(blockedNow != null ? blockedNow : "Say hello. " + BoardView.agentName(agent) + " answers here.", x + 6, by0 + 6, Widgets.BODY, 0xFF000000 | (blockedNow != null ? WUi.RED : WUi.TH.muted));
        }
        for (int i = 0; i < msgs.size(); i++) {
            Object[] m = msgs.get(i);
            float[] g = geo.get(i);
            float y0 = by0 + g[0] - scroll.offset, y1 = y0 + g[1];
            if (y1 < by0 || y0 > areaBottom) continue;
            boolean me = (Boolean) m[0];
            float bw = Math.min(g[2], bubbleMax);
            float bx0b = me ? bx1 - 8 - bw : x + 6;
            int fill = me ? WUi.TH.accent : WUi.TH.card;
            Ui.round(bx0b, y0, bx0b + bw, y1, 5, 0xFF000000 | fill);
            int fg = me ? (Theme.contrast(Theme.INK, fill) >= 4.5 ? Theme.INK : 0xFFFFFF) : WUi.TH.cardText;
            float ty = y0 + 4;
            for (String l : wrapped.get(i)) {
                reg.draw(l, bx0b + 5, ty, size, 0xFF000000 | fg);
                ty += lh;
            }
        }
        if (waiting) {
            float dy = by0 + total - 16 - scroll.offset;
            reg.draw("thinking\u2026", x + 8, dy + 3, Widgets.SMALL + 0.6F, 0xFF000000 | WUi.TH.muted);
        }
        Widgets.unclip();
        scroll.drawBar(WUi.TH);
        // input row
        float iy = areaBottom + 4;
        String b = blocked();
        WUi.Btn send = new WUi.Btn("send", "Send").style(WUi.PRIMARY).on(new Runnable() {

            @Override
            public void run() {
                send();
            }
        });
        send.enable(b == null && !input.text.trim().isEmpty(), b);
        float sw = WUi.Btn.widthFor("Send", inputH) + 10;
        send.at(bx1 - sw, iy, sw, inputH);
        btns.add(send);
        input.at(x, iy, w - sw - 4, inputH);
        input.draw();
        float ry = iy + inputH + 2;
        String r = WUi.recentResultLine();
        if (r != null) reg.drawFit(r, x, ry, Widgets.SMALL + 0.4F, w, 0xFF000000 | WUi.resultColor(ClientWriteState.lastResult()));
    }

    @Override
    void clicked(int mx, int my) {
        input.click(mx, my);
    }

    @Override
    void typed(char ch, int key) {
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
            send();
            return;
        }
        input.focused = true;
        input.key(ch, key);
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d != 0) scroll.wheel(d, 18);
    }
}
