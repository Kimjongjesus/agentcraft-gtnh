package com.robertsnest.aifactory.client;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Properties;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Keyboard;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Client-only entry: local opt-in configuration, HTTP worker, main-thread chat. */
public final class OracleClient {

    private final OracleSession session;
    private Object world;
    private final OracleHud hud = new OracleHud();
    private final KeyBinding openKey = new KeyBinding("Open Factory Oracle", Keyboard.KEY_B, "Factory Oracle");
    private long nextBase;
    private long nextShift;

    private OracleClient(OracleSession session) {
        this.session = session;
    }

    public static void install(File configDir) {
        File file = new File(configDir, "oracle-client.properties");
        if (!file.isFile()) return; // Disabled until the player opts in locally.
        try {
            Properties properties = new Properties();
            try (FileInputStream in = new FileInputStream(file)) {
                properties.load(in);
            }
            if (!Boolean.parseBoolean(properties.getProperty("enabled", "false"))) return;
            Path tokenPath = new File(configDir, properties.getProperty("tokenFile", "oracle-client.token")).toPath();
            if (!Files.isRegularFile(tokenPath) || Files.size(tokenPath) > 4096) throw new IllegalArgumentException();
            if (tokenPath.getFileSystem()
                .supportedFileAttributeViews()
                .contains("posix")) {
                Set<PosixFilePermission> perms = Files.getPosixFilePermissions(tokenPath);
                for (PosixFilePermission p : perms) if (p.name()
                    .startsWith("GROUP_")
                    || p.name()
                        .startsWith("OTHERS_"))
                    throw new IllegalArgumentException();
            }
            String token = new String(Files.readAllBytes(tokenPath), StandardCharsets.UTF_8).trim();
            OracleHttp http = new OracleHttp(properties.getProperty("endpoint", "http://127.0.0.1:8790"), token);
            OracleClient client = new OracleClient(new OracleSession(http::get));
            FMLCommonHandler.instance()
                .bus()
                .register(client);
            ClientCommandHandler.instance.registerCommand(client.new Command());
            MinecraftForge.EVENT_BUS.register(client);
            ClientRegistry.registerKeyBinding(client.openKey);
        } catch (Exception e) {
            com.robertsnest.aifactory.AiFactoryMod.LOG
                .warn("Oracle client disabled: check local endpoint and private token file; details suppressed");
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Object current = Minecraft.getMinecraft().theWorld;
        if (world != current) {
            world = current;
            session.reset();
            hud.reset();
            if (Minecraft.getMinecraft().currentScreen instanceof OracleScreen)
                ((OracleScreen) Minecraft.getMinecraft().currentScreen).worldChanged();
            nextBase = 0;
            nextShift = 0;
        }
        session.drain();
        while (openKey.isPressed()) {
            if (current != null && Minecraft.getMinecraft().currentScreen == null) Minecraft.getMinecraft()
                .displayGuiScreen(new OracleScreen(session));
        }
        if (current == null) return;
        long now = now();
        if (now >= nextBase) {
            if (session.request("/oracle/base", (data, error) -> hud.acceptBase(data, error, now())))
                nextBase = now + 15000;
        } else if (now >= nextShift) {
            if (session.request("/oracle/shift", (data, error) -> hud.acceptShift(data, error, now())))
                nextShift = now + 60000;
        }
    }

    private static long now() {
        return System.nanoTime() / 1000000;
    }

    @SubscribeEvent
    public void overlay(RenderGameOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (event.type != RenderGameOverlayEvent.ElementType.ALL || mc.theWorld == null
            || mc.gameSettings.hideGUI
            || mc.gameSettings.showDebugInfo
            || mc.currentScreen != null) return;
        java.util.List<String> lines = hud.lines(now());
        int width = Math.min(260, event.resolution.getScaledWidth() / 2);
        int x = event.resolution.getScaledWidth() - width - 6;
        Gui.drawRect(x - 4, 4, x + width + 2, 12 + lines.size() * 10, 0xCC18232B);
        int y = 8;
        for (String line : lines) {
            mc.fontRenderer.drawStringWithShadow(
                mc.fontRenderer.trimStringToWidth(line, width - 4),
                x,
                y,
                line.startsWith("CRITICAL") || line.startsWith("FAULT") ? 0xFFB06B : 0xCDE3E8);
            y += 10;
        }
    }

    private static void chat(String text) {
        if (Minecraft.getMinecraft().thePlayer != null) Minecraft.getMinecraft().thePlayer
            .addChatMessage(new ChatComponentText("[Oracle] " + OracleText.clean(text)));
    }

    private final class Command extends CommandBase {

        @Override
        public String getCommandName() {
            return "oracle";
        }

        @Override
        public String getCommandUsage(ICommandSender sender) {
            return OracleRequest.USAGE;
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 0;
        }

        @Override
        public boolean canCommandSenderUseCommand(ICommandSender sender) {
            return true;
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            try {
                String route = OracleRequest.command(args);
                if (!session.request(route, (data, error) -> {
                    if (error != null) chat(error);
                    else {
                        int count = 0;
                        for (String line : OracleText.lines(data)) {
                            if (count++ == 24) {
                                chat("More output available in the Oracle screen.");
                                break;
                            }
                            chat(line);
                        }
                    }
                })) chat("Busy; try again after the current read completes.");
                else chat("Reading Oracle (GET only)...");
            } catch (IllegalArgumentException e) {
                chat(OracleRequest.USAGE);
            }
        }
    }
}
