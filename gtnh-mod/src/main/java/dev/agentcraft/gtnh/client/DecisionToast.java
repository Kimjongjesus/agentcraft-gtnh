package dev.agentcraft.gtnh.client;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import dev.agentcraft.gtnh.AgentCraftGTNH;
import dev.agentcraft.gtnh.Config;
import dev.agentcraft.gtnh.ops.DecisionData;
import dev.agentcraft.gtnh.ops.OpsData;
import dev.agentcraft.gtnh.ops.ToastPolicy;
import dev.agentcraft.gtnh.state.ClientOps;
import dev.agentcraft.gtnh.ui.TextLayout;
import dev.agentcraft.gtnh.ui.Theme;
import dev.agentcraft.gtnh.ui.Ui;
import dev.agentcraft.gtnh.ui.UiFont;
import dev.agentcraft.gtnh.ui.Widgets;
import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Card 5b: the decision toast. When a NEW decision or approval needs the player, a card slides in at
 * the top right of the HUD (title, agent, card, age) with a sound; clicking it (with the chat or any
 * screen open, so the cursor is free) or pressing the "Open decisions" key opens the read-only
 * {@link GuiDecisions} screen. Who toasts is decided by {@link ToastPolicy} (new only, dedupe,
 * no replay after reconnect/restart, cooldown, rate limit, mute). Alerts and ops events never toast.
 * Nothing here can answer a decision.
 */
public final class DecisionToast {

    public static final DecisionToast INSTANCE = new DecisionToast();

    public final ToastPolicy policy = new ToastPolicy();
    public final KeyBinding openKey = new KeyBinding("Open decisions (AgentCraft)", Keyboard.KEY_N, "AgentCraft");
    private final Deque<ToastPolicy.Toast> queue = new ArrayDeque<>();
    private ToastPolicy.Toast current;
    private long shownAt, lastDecisionVersion = -1;
    private float[] rect;
    private boolean mouseWasDown;
    private File seenFile, prefsFile;
    private long lastSave;
    private boolean dirtySeen;
    public long toastsShown, soundsPlayed;
    /** /agentcraft toast actions from the network thread, run on the next client tick. */
    public final java.util.concurrent.ConcurrentLinkedQueue<String> controls = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private DecisionToast() {}

    public void init(File configDir) {
        File dir = new File(configDir, "agentcraftgtnh");
        seenFile = new File(dir, "toast-seen.txt");
        prefsFile = new File(dir, "toast-muted.txt");
        policy.maxAgeMs = Config.toastMaxAgeMinutes * 60_000L;
        policy.cooldownMs = Config.toastCooldownMinutes * 60_000L;
        policy.maxPerMinute = Config.toastPerMinute;
        policy.muted = !Config.toastEnabled || prefsFile.exists();
        try {
            if (seenFile.exists()) policy.load(new String(Files.readAllBytes(seenFile.toPath()), StandardCharsets.UTF_8), System.currentTimeMillis());
        } catch (Exception e) {
            AgentCraftGTNH.LOG.warn("could not read {}: {}", seenFile, e.toString());
        }
        ClientRegistry.registerKeyBinding(openKey);
    }

    private void saveSeen(boolean force) {
        long now = System.currentTimeMillis();
        if (!dirtySeen || seenFile == null || !force && now - lastSave < 10_000L) return;
        try {
            seenFile.getParentFile()
                .mkdirs();
            Files.write(seenFile.toPath(), policy.export()
                .getBytes(StandardCharsets.UTF_8));
            lastSave = now;
            dirtySeen = false;
        } catch (Exception e) {
            AgentCraftGTNH.LOG.warn("could not write {}: {}", seenFile, e.toString());
        }
    }

    /** /agentcraft toast mute|unmute|test|status (sent by the server to this player only). */
    public void control(String action) {
        Minecraft mc = Minecraft.getMinecraft();
        String msg;
        switch (action) {
            case "mute":
                policy.muted = true;
                queue.clear();
                current = null;
                try {
                    prefsFile.getParentFile()
                        .mkdirs();
                    Files.write(prefsFile.toPath(), "muted\n".getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                    // the in-memory mute still holds
                }
                msg = "decision toasts muted (decisions seen while muted never replay)";
                break;
            case "unmute":
                policy.muted = !Config.toastEnabled;
                if (prefsFile != null) prefsFile.delete();
                msg = Config.toastEnabled ? "decision toasts on" : "still off: toast.enabled=false in the config";
                break;
            case "open":
                mc.displayGuiScreen(new GuiDecisions(null));
                return;
            case "test": {
                DecisionData.Decision d = new DecisionData.Decision();
                d.id = "test";
                d.kind = "permission";
                d.agentName = "Test agent";
                d.question = "This is a test toast. Real ones appear when a new decision or approval needs you.";
                d.taskId = "test card";
                d.createdAt = System.currentTimeMillis();
                show(new ToastPolicyTest(d));
                msg = "test toast shown" + (policy.muted ? " (you are muted: real toasts are off)" : "");
                break;
            }
            default:
                msg = "toasts " + (policy.muted ? "MUTED" : "on") + ", volume " + Config.toastVolume + ", sound " + Config.toastSound + ", key "
                    + Keyboard.getKeyName(openKey.getKeyCode()) + ", " + ClientOps.decisionSnapshot.openIds.size() + " open decision(s), "
                    + policy.rememberedCount() + " remembered, " + toastsShown + " shown";
        }
        if (mc.thePlayer != null) mc.thePlayer.addChatMessage(new ChatComponentText("[AgentCraft] " + msg));
    }

    /** A toast that bypasses the policy (test only). */
    private static final class ToastPolicyTest {

        final DecisionData.Decision d;

        ToastPolicyTest(DecisionData.Decision d) {
            this.d = d;
        }
    }

    private void show(ToastPolicyTest t) {
        queue.addFirst(fakeToast(t.d));
        current = null;
    }

    private static ToastPolicy.Toast fakeToast(DecisionData.Decision d) {
        ToastPolicy p = new ToastPolicy();
        List<ToastPolicy.Toast> l = p.offer(java.util.Collections.singletonList(d), d.createdAt);
        return l.get(0);
    }

    // ---- tick: new decisions, key, click ---------------------------------------------------

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        long now = System.currentTimeMillis();
        if (mc.theWorld == null) return;
        for (String a; (a = controls.poll()) != null;) control(a);
        long v = ClientOps.decisionVersion;
        if (v != lastDecisionVersion) {
            lastDecisionVersion = v;
            // only lists from a live adapter link count (an offline/empty list must not reset "open")
            DecisionData.Snapshot snap = ClientOps.decisionSnapshot;
            if (snap.live) {
                List<ToastPolicy.Toast> fresh = policy.offer(snap, now);
                queue.addAll(fresh);
                dirtySeen = true;
            }
        }
        if (policy.summaryDue(now) && queue.isEmpty() && current == null) {
            int n = policy.takeFolded(now);
            DecisionData.Decision d = new DecisionData.Decision();
            d.id = "";
            d.question = n + (n == 1 ? " more decision needs you" : " more decisions need you");
            d.agentName = "AgentCraft";
            d.createdAt = now;
            queue.add(fakeToast(withId(d, "summary-" + now)));
        }
        if (current != null && now - shownAt > Config.toastSeconds * 1000L) current = null;
        if (current == null && !queue.isEmpty()) {
            current = queue.pollFirst();
            shownAt = now;
            toastsShown++;
            playSound(mc);
        }
        saveSeen(false);
        if (mc.currentScreen == null && openKey.isPressed()) open(mc);
        // click on the toast: only possible while a screen (chat, inventory ...) frees the cursor
        boolean down = Mouse.isButtonDown(0);
        if (down && !mouseWasDown && current != null && rect != null && mc.currentScreen != null && !(mc.currentScreen instanceof GuiDecisions)) {
            ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
            int mx = Mouse.getX() * sr.getScaledWidth() / mc.displayWidth;
            int my = sr.getScaledHeight() - Mouse.getY() * sr.getScaledHeight() / mc.displayHeight - 1;
            if (Widgets.inside(mx, my, rect[0], rect[1], rect[2], rect[3])) open(mc);
        }
        mouseWasDown = down;
    }

    private static DecisionData.Decision withId(DecisionData.Decision d, String id) {
        d.id = id;
        return d;
    }

    private void open(Minecraft mc) {
        String sel = current != null && ClientOps.decision(current.decision.id) != null ? current.decision.id : null;
        current = null;
        mc.displayGuiScreen(new GuiDecisions(sel));
    }

    private void playSound(Minecraft mc) {
        if (Config.toastVolume <= 0 || mc.thePlayer == null) return;
        try {
            String s = Config.toastSound == null || Config.toastSound.trim()
                .isEmpty() ? "note.pling" : Config.toastSound.trim();
            mc.getSoundHandler()
                .playSound(
                    new PositionedSoundRecord(
                        new ResourceLocation(s),
                        Config.toastVolume,
                        Config.toastPitch,
                        (float) mc.thePlayer.posX,
                        (float) mc.thePlayer.posY,
                        (float) mc.thePlayer.posZ));
            soundsPlayed++;
        } catch (RuntimeException ex) {
            AgentCraftGTNH.LOG.warn("toast sound '{}' failed: {}", Config.toastSound, ex.toString());
        }
    }

    /** Left the server: drop what is on screen (the seen set stays, so nothing replays). */
    public void reset() {
        queue.clear();
        current = null;
        saveSeen(true);
    }

    // ---- HUD -------------------------------------------------------------------------------

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post e) {
        if (e.type != RenderGameOverlayEvent.ElementType.ALL || current == null) return;
        long now = System.currentTimeMillis();
        long age = now - shownAt;
        float in = Math.min(1F, age / 250F), out = Math.min(1F, Math.max(0F, (Config.toastSeconds * 1000L - age) / 400F));
        draw(e.resolution.getScaledWidth(), current, Math.min(in, out), now);
    }

    private void draw(int screenW, ToastPolicy.Toast t, float alpha, long now) {
        DecisionData.Decision d = t.decision;
        Theme th = Theme.DARK;
        UiFont reg = UiFont.regular(), bold = UiFont.bold();
        float w = Math.min(230, screenW * 0.45F), pad = 6;
        float x1 = screenW - 6 + (1 - alpha) * (w * 0.4F), x0 = x1 - w, y0 = 6;
        List<String> title = TextLayout.wrap(d.question.isEmpty() ? "A decision or approval needs you" : d.question, w - 2 * pad - 6, 2, bold.measure(Widgets.BODY));
        float lh = bold.lineHeight(Widgets.BODY), sh = reg.lineHeight(Widgets.SMALL);
        float h = pad + sh + 2 + title.size() * lh + 3 + sh + pad;
        int a = Math.round(alpha * 235) << 24;
        GL11.glPushMatrix();
        GL11.glTranslatef(0, 0, 300);
        Ui.round(x0, y0, x1, y0 + h, 5, a | th.surface);
        int accent = 0xD97757;
        Ui.round(x0, y0, x0 + 4, y0 + h, 2, a | accent);
        int ta = Math.max(4, Math.round(alpha * 255)) << 24;
        float tx = x0 + pad + 3, y = y0 + pad;
        String head = ("question".equals(d.kind) ? "DECISION NEEDED" : "APPROVAL NEEDED");
        float hw = bold.draw(head, tx, y, Widgets.SMALL, ta | Theme.readable(accent, th.surface));
        String ageText = d.createdAt > 0 ? OpsData.age(d.createdAt, now) : "";
        reg.drawRight(ageText, x1 - pad, y, Widgets.SMALL, ta | th.muted);
        if (t.more > 0) Ui.pill(bold, "+" + t.more + " more", tx + hw + 4, y - 1, Widgets.SMALL * 0.9F, accent);
        y += sh + 2;
        for (String l : title) {
            bold.draw(l, tx, y, Widgets.BODY, ta | th.text);
            y += lh;
        }
        y += 3;
        Ui.dot(tx + 3, y + sh / 2, 2.5, ta | (d.agentColor & 0xFFFFFF));
        String foot = (d.agentName.isEmpty() ? d.agentId : d.agentName) + (d.taskId.isEmpty() ? "" : "  \u00b7  " + d.taskId);
        String hint = "[" + Keyboard.getKeyName(openKey.getKeyCode()) + "] or click to open";
        float hintW = reg.width(hint, Widgets.SMALL);
        reg.drawFit(foot, tx + 8, y, Widgets.SMALL, x1 - pad - tx - 8 - hintW - 6, ta | th.muted);
        reg.drawRight(hint, x1 - pad, y, Widgets.SMALL, ta | Theme.readable(accent, th.surface));
        GL11.glPopMatrix();
        GL11.glColor4f(1, 1, 1, 1);
        rect = new float[] { x0, y0, x1, y0 + h };
    }

    public boolean showing() {
        return current != null;
    }
}
