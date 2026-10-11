package dev.agentcraft.gtnh.write.mc;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;

import dev.agentcraft.gtnh.write.proto.StrictJson;

/**
 * {@code /ask <agent> <question>}: one question to one agent, the answer comes back into the chat. It is
 * only a way to make an {@code agent.ask} request from the keyboard: it goes through exactly the same queue,
 * gate, owner check, policy, limits and audit as a request from a screen. Anyone may type it; everyone but
 * the owner (or while writes are disarmed, locked, or the policy has agent.ask off) gets a refusal.
 */
public final class AskCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "ask";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/ask <agent> <question>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayerMP)) {
            sender.addChatMessage(new ChatComponentText("\u00a7c[AgentCraft] /ask is for players (it asks as you)"));
            return;
        }
        if (args.length < 2) {
            sender.addChatMessage(new ChatComponentText("\u00a7c[AgentCraft] usage: /ask <agent> <question>"));
            return;
        }
        StringBuilder q = new StringBuilder();
        for (int i = 1; i < args.length; i++) q.append(i > 1 ? " " : "")
            .append(args[i]);
        if (q.length() > 2000) {
            sender.addChatMessage(new ChatComponentText("\u00a7c[AgentCraft] the question is longer than 2000 characters"));
            return;
        }
        Map<String, Object> a = new LinkedHashMap<String, Object>();
        a.put("agent", args[0]);
        a.put("text", q.toString());
        if (!WriteRuntime.enqueue((EntityPlayerMP) sender, WriteRuntime.REQ, "agent.ask", StrictJson.write(a))) {
            sender.addChatMessage(new ChatComponentText("\u00a7c[AgentCraft] the write module is not running here, or too many requests are waiting"));
            return;
        }
        sender.addChatMessage(new ChatComponentText("[AgentCraft] asking " + args[0] + "\u2026 (the answer shows here)"));
    }
}
