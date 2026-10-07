package com.robertsnest.aifactory.client;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.DefaultResourcePack;
import net.minecraft.client.resources.SimpleReloadableResourceManager;
import net.minecraft.client.resources.data.IMetadataSerializer;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;

/** Real GL/Minecraft GUI classes, synthetic Oracle HTTP, no world construction or tick. */
public final class ClientRenderProbe {

    private static final int WIDTH = 960, HEIGHT = 600;

    public static void main(String[] args) throws Exception {
        File out = new File(args[3]);
        OracleHttp http = new OracleHttp(
            args[0],
            new String(Files.readAllBytes(new File(args[1]).toPath()), StandardCharsets.UTF_8).trim());
        OracleSession session = new OracleSession(http::get);
        Display.setDisplayMode(new DisplayMode(WIDTH, HEIGHT));
        Display.setTitle("SYNTHETIC Oracle render probe - no loaded world");
        Display.create();
        try {
            System.out.println("GL renderer: " + GL11.glGetString(GL11.GL_RENDERER));
            // Bypass game startup, authentication, integrated server and world constructors.
            // Only renderer fields are populated; this is not a full Forge client launch.
            Minecraft mc = (Minecraft) allocate(Minecraft.class);
            field(Minecraft.class, "theMinecraft").set(null, mc);
            mc.displayWidth = WIDTH;
            mc.displayHeight = HEIGHT;
            mc.gameSettings = new GameSettings();
            mc.gameSettings.guiScale = 1;
            field(Minecraft.class, "mcLanguageManager")
                .set(mc, new net.minecraft.client.resources.LanguageManager(new IMetadataSerializer(), "en_US"));
            SimpleReloadableResourceManager resources = new SimpleReloadableResourceManager(new IMetadataSerializer());
            resources.reloadResourcePack(new DefaultResourcePack(Collections.emptyMap()));
            field(Minecraft.class, "mcResourceManager").set(mc, resources);
            mc.renderEngine = new TextureManager(resources);
            mc.fontRenderer = new FontRenderer(
                mc.gameSettings,
                new ResourceLocation("textures/font/ascii.png"),
                mc.renderEngine,
                false);
            mc.fontRenderer.onResourceManagerReload(resources);
            GL11.glViewport(0, 0, WIDTH, HEIGHT);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GL11.glOrtho(0, WIDTH, HEIGHT, 0, -1000, 1000);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_DEPTH_TEST);

            java.lang.reflect.Constructor<OracleClient> ctor = OracleClient.class
                .getDeclaredConstructor(OracleSession.class);
            ctor.setAccessible(true);
            OracleClient client = ctor.newInstance(session);
            mc.theWorld = (WorldClient) allocate(WorldClient.class);
            field(OracleClient.class, "world").set(client, mc.theWorld);
            field(OracleClient.class, "nextBase").setLong(client, Long.MAX_VALUE);
            field(OracleClient.class, "nextShift").setLong(client, Long.MAX_VALUE);
            net.minecraft.client.settings.KeyBinding key = (net.minecraft.client.settings.KeyBinding) field(
                OracleClient.class,
                "openKey").get(client);
            if (key.getKeyCode() != org.lwjgl.input.Keyboard.KEY_B) throw new AssertionError("wrong default key");
            net.minecraft.client.settings.KeyBinding.onTick(key.getKeyCode());
            client.tick(
                new cpw.mods.fml.common.gameevent.TickEvent.ClientTickEvent(
                    cpw.mods.fml.common.gameevent.TickEvent.Phase.END));
            if (!(mc.currentScreen instanceof OracleScreen)) throw new AssertionError("B did not open screen");
            System.out.println("SYNTHETIC keybinding B -> actual displayGuiScreen PASS");

            mc.ingameGUI = new net.minecraft.client.gui.GuiIngame(mc);
            mc.thePlayer = (net.minecraft.client.entity.EntityClientPlayerMP) allocate(
                net.minecraft.client.entity.EntityClientPlayerMP.class);
            field(net.minecraft.client.entity.EntityPlayerSP.class, "mc").set(mc.thePlayer, mc);
            java.lang.reflect.Constructor<?> commandCtor = Class
                .forName("com.robertsnest.aifactory.client.OracleClient$Command")
                .getDeclaredConstructor(OracleClient.class);
            commandCtor.setAccessible(true);
            net.minecraftforge.client.ClientCommandHandler.instance
                .registerCommand((net.minecraft.command.ICommand) commandCtor.newInstance(client));
            for (String command : new String[] { "status", "ask power Why idle?", "diagnose EBF", "shift" }) {
                mc.ingameGUI.getChatGUI()
                    .clearChatMessages();
                int result = net.minecraftforge.client.ClientCommandHandler.instance
                    .executeCommand(mc.thePlayer, "/oracle " + command);
                if (result != 1) throw new AssertionError("client command not consumed");
                session.awaitWorkerForTest();
                java.util.List<?> before = (java.util.List<?>) field(
                    net.minecraft.client.gui.GuiNewChat.class,
                    "chatLines").get(mc.ingameGUI.getChatGUI());
                if (before.size() != 1) throw new AssertionError("reply delivered off client tick");
                client.tick(
                    new cpw.mods.fml.common.gameevent.TickEvent.ClientTickEvent(
                        cpw.mods.fml.common.gameevent.TickEvent.Phase.END));
                if (before.size() <= 1) throw new AssertionError("no command chat reply");
                for (Object line : before) {
                    String text = ((net.minecraft.client.gui.ChatLine) line).func_151461_a()
                        .getUnformattedText();
                    if (text.contains("Oracle unavailable") || text.contains("Busy;"))
                        throw new AssertionError("command failed");
                }
                System.out.println(
                    "SYNTHETIC registered command -> client tick -> GuiNewChat PASS: " + command.split(" ")[0]);
            }

            for (String[] command : new String[][] { { "status" }, { "ask", "power", "Why idle?" },
                { "diagnose", "EBF" }, { "shift" } }) {
                final boolean[] delivered = { false };
                if (!session.request(OracleRequest.command(command), (data, error) -> {
                    if (error != null || data == null || data.has("error"))
                        throw new AssertionError("command request failed");
                    delivered[0] = true;
                })) throw new AssertionError("busy");
                session.awaitWorkerForTest();
                if (delivered[0]) throw new AssertionError("off-thread delivery");
                session.drain();
                if (!delivered[0]) throw new AssertionError("missing delivery");
                System.out.println("SYNTHETIC command route + tick-drain PASS: " + command[0]);
            }

            OracleScreen screen = (OracleScreen) mc.currentScreen;
            for (int tab = 0; tab < OracleViews.TABS.length; tab++) {
                screen.actionPerformed(new GuiButton(tab, 0, 0, ""));
                if (tab == 1) ((GuiTextField) field(OracleScreen.class, "first").get(screen)).setText("EBF");
                if (tab == 9) ((GuiTextField) field(OracleScreen.class, "first").get(screen)).setText(args[2]);
                if (tab == 3) ((GuiTextField) field(OracleScreen.class, "first").get(screen)).setText("Steel Ingot");
                screen.actionPerformed(new GuiButton(21, 0, 0, "Read"));
                session.awaitWorkerForTest();
                session.drain();
                Object lines = field(OracleScreen.class, "lines").get(screen);
                if (tab == 5) {
                    screen.mouseClicked(WIDTH / 2 - 25, 234, 0);
                    if (field(OracleScreen.class, "frame").getInt(screen) <= 0)
                        throw new AssertionError("recorder scrub did not select a later frame");
                }
                if (lines.toString()
                    .contains("Oracle unavailable")) throw new AssertionError("screen request failed: " + tab);
                clear();
                screen.drawScreen(0, 0, 0);
                mc.fontRenderer.drawStringWithShadow(
                    "SYNTHETIC FIXTURE / no loaded world / real GuiScreen + GL",
                    8,
                    HEIGHT - 38,
                    0xFFB06B);
                screenshot(new File(out, "tab-" + tab + ".png"));
            }
            mc.theWorld = null;
            client.tick(
                new cpw.mods.fml.common.gameevent.TickEvent.ClientTickEvent(
                    cpw.mods.fml.common.gameevent.TickEvent.Phase.END));
            if (field(OracleScreen.class, "payload").get(screen) != null)
                throw new AssertionError("open screen retained previous world payload on disconnect");
            screen.onGuiClosed();
            mc.currentScreen = null;
            OracleHud hud = (OracleHud) field(OracleClient.class, "hud").get(client);
            long now = System.nanoTime() / 1000000;
            hud.acceptBase(http.get("/oracle/base"), null, now);
            hud.acceptShift(http.get("/oracle/shift"), null, now);
            // Non-null sentinel for the overlay guard only; never construct/read/tick a world.
            mc.theWorld = (WorldClient) allocate(WorldClient.class);
            clear();
            field(OracleClient.class, "world").set(client, mc.theWorld);
            RenderGameOverlayEvent event = new RenderGameOverlayEvent(0, new ScaledResolution(mc, WIDTH, HEIGHT), 0, 0);
            client.overlay(new RenderGameOverlayEvent.Post(event, RenderGameOverlayEvent.ElementType.ALL));
            mc.fontRenderer.drawStringWithShadow(
                "SYNTHETIC FIXTURE / real RenderGameOverlayEvent handler",
                8,
                HEIGHT - 30,
                0xFFB06B);
            screenshot(new File(out, "hud.png"));
            mc.theWorld = null;
            client.tick(
                new cpw.mods.fml.common.gameevent.TickEvent.ClientTickEvent(
                    cpw.mods.fml.common.gameevent.TickEvent.Phase.END));
            if (!hud.lines(now)
                .toString()
                .contains("unknown")) throw new AssertionError("cache not cleared");
            System.out.println("PASS: ten real GuiScreen tabs and HUD event rendered; no world loaded.");
        } finally {
            Display.destroy();
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Object instance = field(unsafe, "theUnsafe").get(null);
        return unsafe.getMethod("allocateInstance", Class.class)
            .invoke(instance, type);
    }

    private static void clear() {
        GL11.glClearColor(0.06f, 0.09f, 0.12f, 1);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL11.glColor4f(1, 1, 1, 1);
    }

    private static void screenshot(File file) throws Exception {
        GL11.glFinish();
        if (GL11.glGetError() != GL11.GL_NO_ERROR) throw new AssertionError("GL render error");
        ByteBuffer pixels = BufferUtils.createByteBuffer(WIDTH * HEIGHT * 4);
        GL11.glReadPixels(0, 0, WIDTH, HEIGHT, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        java.util.Set<Integer> colors = new java.util.HashSet<>();
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
            int i = (y * WIDTH + x) * 4;
            int rgb = ((pixels.get(i) & 255) << 16) | ((pixels.get(i + 1) & 255) << 8) | (pixels.get(i + 2) & 255);
            image.setRGB(x, HEIGHT - 1 - y, rgb);
            colors.add(rgb);
        }
        ImageIO.write(image, "png", file);
        if (file.getName()
            .equals("hud.png")) {
            boolean text = false, fault = false;
            for (int y = 0; y < 130; y++) for (int x = WIDTH - 270; x < WIDTH; x++) {
                int rgb = image.getRGB(x, y) & 0xFFFFFF;
                text |= rgb == 0xCDE3E8;
                fault |= rgb == 0xFFB06B;
            }
            if (!text || !fault) throw new AssertionError("missing HUD text/fault pixels");
        } else if (colors.size() < 8) throw new AssertionError("blank screen");
        if (file.getName()
            .equals("tab-4.png") && !colors.contains(0x58C4A3))
            throw new AssertionError("X-ray has no rendered observed machine markers");
        if (file.getName()
            .equals("tab-5.png") && !colors.contains(0x72B9E8))
            throw new AssertionError("Recorder has no rendered capture timeline");
        if (file.getName()
            .equals("tab-9.png") && !colors.contains(0xB99BE8))
            throw new AssertionError("Blueprint has no rendered ghost geometry");
    }
}
