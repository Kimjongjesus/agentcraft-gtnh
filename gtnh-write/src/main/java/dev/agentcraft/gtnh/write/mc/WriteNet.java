package dev.agentcraft.gtnh.write.mc;

import java.nio.charset.StandardCharsets;

import net.minecraft.entity.player.EntityPlayerMP;

import dev.agentcraft.gtnh.write.client.ClientWriteState;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * The write module's own channel, separate from the core's. Client -> server: a capability request
 * (name + arguments as JSON, nothing else: the server picks id, nonce, time and actor), confirm,
 * cancel, lock, sync. Server -> client: state, confirm prompt, prompt closed, result, chat, open-chat.
 * Every string is length-checked on read; the server re-validates everything.
 */
public final class WriteNet {

    public static final String CHANNEL_NAME = "acwrite";
    public static SimpleNetworkWrapper CHANNEL;

    private WriteNet() {}

    public static void init() {
        CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel(CHANNEL_NAME);
        CHANNEL.registerMessage(ReqHandler.class, Req.class, 0, Side.SERVER);
        CHANNEL.registerMessage(ConfirmHandler.class, Confirm.class, 1, Side.SERVER);
        CHANNEL.registerMessage(CancelHandler.class, Cancel.class, 2, Side.SERVER);
        CHANNEL.registerMessage(LockHandler.class, Lock.class, 3, Side.SERVER);
        CHANNEL.registerMessage(SyncHandler.class, Sync.class, 4, Side.SERVER);
        CHANNEL.registerMessage(StateHandler.class, State.class, 5, Side.CLIENT);
        CHANNEL.registerMessage(PromptHandler.class, Prompt.class, 6, Side.CLIENT);
        CHANNEL.registerMessage(PromptClosedHandler.class, PromptClosed.class, 7, Side.CLIENT);
        CHANNEL.registerMessage(ResultHandler.class, Result.class, 8, Side.CLIENT);
        CHANNEL.registerMessage(ChatHandler.class, Chat.class, 9, Side.CLIENT);
        CHANNEL.registerMessage(OpenChatHandler.class, OpenChat.class, 10, Side.CLIENT);
    }

    public static void sendTo(IMessage m, EntityPlayerMP p) {
        if (CHANNEL != null) CHANNEL.sendTo(m, p);
    }

    static String read(ByteBuf buf, int max) {
        int n = buf.readUnsignedShort();
        if (n > max * 4 || n > buf.readableBytes()) throw new IllegalArgumentException("text too long");
        byte[] b = new byte[n];
        buf.readBytes(b);
        String s = new String(b, StandardCharsets.UTF_8);
        if (s.length() > max) throw new IllegalArgumentException("text too long");
        return s;
    }

    static void write(ByteBuf buf, String s, int max) {
        String t = s == null ? "" : s.length() > max ? s.substring(0, max) : s;
        byte[] b = t.getBytes(StandardCharsets.UTF_8);
        if (b.length > 60000) b = new byte[0];
        buf.writeShort(b.length);
        buf.writeBytes(b);
    }

    static EntityPlayerMP sender(MessageContext ctx) {
        return ctx.getServerHandler() == null ? null : ctx.getServerHandler().playerEntity;
    }

    // ---- client -> server ----------------------------------------------------------------------------

    public static final int MAX_ARGS = 8000;

    public static final class Req implements IMessage {

        public String cap = "", args = "{}";

        public Req() {}

        public Req(String cap, String args) {
            this.cap = cap;
            this.args = args;
        }

        @Override
        public void fromBytes(ByteBuf b) {
            cap = read(b, 32);
            args = read(b, MAX_ARGS);
        }

        @Override
        public void toBytes(ByteBuf b) {
            write(b, cap, 32);
            write(b, args, MAX_ARGS);
        }
    }

    public static final class ReqHandler implements IMessageHandler<Req, IMessage> {

        @Override
        public IMessage onMessage(Req m, MessageContext ctx) {
            WriteRuntime.enqueue(sender(ctx), WriteRuntime.REQ, m.cap, m.args);
            return null;
        }
    }

    public static final class Confirm implements IMessage {

        public String token = "";

        public Confirm() {}

        public Confirm(String token) {
            this.token = token;
        }

        @Override
        public void fromBytes(ByteBuf b) {
            token = read(b, 32);
        }

        @Override
        public void toBytes(ByteBuf b) {
            write(b, token, 32);
        }
    }

    public static final class ConfirmHandler implements IMessageHandler<Confirm, IMessage> {

        @Override
        public IMessage onMessage(Confirm m, MessageContext ctx) {
            WriteRuntime.enqueue(sender(ctx), WriteRuntime.CONFIRM, m.token, "");
            return null;
        }
    }

    public static final class Cancel implements IMessage {

        public String token = "";

        public Cancel() {}

        public Cancel(String token) {
            this.token = token;
        }

        @Override
        public void fromBytes(ByteBuf b) {
            token = read(b, 32);
        }

        @Override
        public void toBytes(ByteBuf b) {
            write(b, token, 32);
        }
    }

    public static final class CancelHandler implements IMessageHandler<Cancel, IMessage> {

        @Override
        public IMessage onMessage(Cancel m, MessageContext ctx) {
            WriteRuntime.enqueue(sender(ctx), WriteRuntime.CANCEL, m.token, "");
            return null;
        }
    }

    public static final class Lock implements IMessage {

        public String reason = "";

        public Lock() {}

        public Lock(String reason) {
            this.reason = reason;
        }

        @Override
        public void fromBytes(ByteBuf b) {
            reason = read(b, 200);
        }

        @Override
        public void toBytes(ByteBuf b) {
            write(b, reason, 200);
        }
    }

    public static final class LockHandler implements IMessageHandler<Lock, IMessage> {

        @Override
        public IMessage onMessage(Lock m, MessageContext ctx) {
            WriteRuntime.enqueue(sender(ctx), WriteRuntime.LOCK, m.reason, "");
            return null;
        }
    }

    public static final class Sync implements IMessage {

        @Override
        public void fromBytes(ByteBuf b) {}

        @Override
        public void toBytes(ByteBuf b) {}
    }

    public static final class SyncHandler implements IMessageHandler<Sync, IMessage> {

        @Override
        public IMessage onMessage(Sync m, MessageContext ctx) {
            WriteRuntime.enqueue(sender(ctx), WriteRuntime.SYNC, "", "");
            return null;
        }
    }

    // ---- server -> client ----------------------------------------------------------------------------

    public static final class State implements IMessage {

        public String json = "{}";

        public State() {}

        public State(String json) {
            this.json = json;
        }

        @Override
        public void fromBytes(ByteBuf b) {
            json = read(b, 8000);
        }

        @Override
        public void toBytes(ByteBuf b) {
            write(b, json, 8000);
        }
    }

    public static final class StateHandler implements IMessageHandler<State, IMessage> {

        @Override
        public IMessage onMessage(State m, MessageContext ctx) {
            ClientWriteState.onState(m.json);
            return null;
        }
    }

    public static final class Prompt implements IMessage {

        public String requestId = "", token = "", summaryJson = "{}";
        public int msLeft;

        public Prompt() {}

        public Prompt(String requestId, String token, long msLeft, String summaryJson) {
            this.requestId = requestId;
            this.token = token;
            this.msLeft = (int) Math.max(0, Math.min(msLeft, 120_000));
            this.summaryJson = summaryJson;
        }

        @Override
        public void fromBytes(ByteBuf b) {
            requestId = read(b, 64);
            token = read(b, 32);
            msLeft = b.readInt();
            summaryJson = read(b, 4000);
        }

        @Override
        public void toBytes(ByteBuf b) {
            write(b, requestId, 64);
            write(b, token, 32);
            b.writeInt(msLeft);
            write(b, summaryJson, 4000);
        }
    }

    public static final class PromptHandler implements IMessageHandler<Prompt, IMessage> {

        @Override
        public IMessage onMessage(Prompt m, MessageContext ctx) {
            ClientWriteState.onPrompt(m.requestId, m.token, m.msLeft, m.summaryJson);
            return null;
        }
    }

    public static final class PromptClosed implements IMessage {

        public String token = "", why = "";

        public PromptClosed() {}

        public PromptClosed(String token, String why) {
            this.token = token;
            this.why = why;
        }

        @Override
        public void fromBytes(ByteBuf b) {
            token = read(b, 32);
            why = read(b, 200);
        }

        @Override
        public void toBytes(ByteBuf b) {
            write(b, token, 32);
            write(b, why, 200);
        }
    }

    public static final class PromptClosedHandler implements IMessageHandler<PromptClosed, IMessage> {

        @Override
        public IMessage onMessage(PromptClosed m, MessageContext ctx) {
            ClientWriteState.onPromptClosed(m.token, m.why);
            return null;
        }
    }

    public static final class Result implements IMessage {

        public String requestId = "", cap = "", status = "", error = "", resultJson = "{}", audit = "";
        public boolean dryRun;

        public Result() {}

        @Override
        public void fromBytes(ByteBuf b) {
            requestId = read(b, 64);
            cap = read(b, 32);
            status = read(b, 16);
            error = read(b, 200);
            resultJson = read(b, 8000);
            audit = read(b, 64);
            dryRun = b.readBoolean();
        }

        @Override
        public void toBytes(ByteBuf b) {
            write(b, requestId, 64);
            write(b, cap, 32);
            write(b, status, 16);
            write(b, error, 200);
            write(b, resultJson, 8000);
            write(b, audit, 64);
            b.writeBoolean(dryRun);
        }
    }

    public static final class ResultHandler implements IMessageHandler<Result, IMessage> {

        @Override
        public IMessage onMessage(Result m, MessageContext ctx) {
            ClientWriteState.onResult(m.requestId, m.cap, m.status, m.error, m.resultJson, m.audit, m.dryRun);
            return null;
        }
    }

    public static final class Chat implements IMessage {

        public String conversation = "", agentId = "", text = "";
        public boolean fin;

        public Chat() {}

        public Chat(String conversation, String agentId, String text, boolean fin) {
            this.conversation = conversation;
            this.agentId = agentId;
            this.text = text;
            this.fin = fin;
        }

        @Override
        public void fromBytes(ByteBuf b) {
            conversation = read(b, 64);
            agentId = read(b, 64);
            text = read(b, 2000);
            fin = b.readBoolean();
        }

        @Override
        public void toBytes(ByteBuf b) {
            write(b, conversation, 64);
            write(b, agentId, 64);
            write(b, text, 2000);
            b.writeBoolean(fin);
        }
    }

    public static final class ChatHandler implements IMessageHandler<Chat, IMessage> {

        @Override
        public IMessage onMessage(Chat m, MessageContext ctx) {
            ClientWriteState.onChat(m.conversation, m.agentId, m.text, m.fin);
            return null;
        }
    }

    public static final class OpenChat implements IMessage {

        public String agentId = "";

        public OpenChat() {}

        public OpenChat(String agentId) {
            this.agentId = agentId;
        }

        @Override
        public void fromBytes(ByteBuf b) {
            agentId = read(b, 64);
        }

        @Override
        public void toBytes(ByteBuf b) {
            write(b, agentId, 64);
        }
    }

    public static final class OpenChatHandler implements IMessageHandler<OpenChat, IMessage> {

        @Override
        public IMessage onMessage(OpenChat m, MessageContext ctx) {
            ClientWriteState.requestOpenChat(m.agentId);
            return null;
        }
    }
}
