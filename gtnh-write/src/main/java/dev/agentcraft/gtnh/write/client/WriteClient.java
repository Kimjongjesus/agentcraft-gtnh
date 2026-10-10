package dev.agentcraft.gtnh.write.client;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.agentcraft.gtnh.write.mc.WriteNet;
import dev.agentcraft.gtnh.write.proto.StrictJson;

/**
 * The calls a GUI makes. Each one only sends a request to the server: the client never chooses an id,
 * nonce, time or actor, and the server decides everything again (owner, arming, lock, rate, policy,
 * presence). The results come back through {@link ClientWriteState#lastResults()}, the Confirm
 * screen through {@link ClientWriteState#pendingPrompt()}. A request made while writes are disarmed or
 * locked is refused here without sending anything (that is only a courtesy to the UI: the server
 * refuses it as well).
 */
public final class WriteClient {

    private WriteClient() {}

    private static boolean send(String cap, Map<String, Object> args) {
        if (!ClientWriteState.canWrite()) {
            ClientWriteState.localRefusal(cap, ClientWriteState.statusLine());
            return false;
        }
        if (WriteNet.CHANNEL == null) {
            ClientWriteState.localRefusal(cap, "write module not initialised");
            return false;
        }
        WriteNet.CHANNEL.sendToServer(new WriteNet.Req(cap, StrictJson.write(args)));
        return true;
    }

    private static Map<String, Object> map() {
        return new LinkedHashMap<String, Object>();
    }

    /** Dispatch a card to a builder profile: starts the Confirm flow (watch {@link ClientWriteState#pendingPrompt()}). */
    public static boolean requestDispatch(String card, String board, String profile) {
        Map<String, Object> a = map();
        a.put("card", card);
        a.put("board", board);
        a.put("profile", profile);
        return send("card.dispatch", a);
    }

    /** The Confirm button: the token of {@link ClientWriteState#pendingPrompt()}. */
    public static void confirm(String token) {
        if (WriteNet.CHANNEL != null && token != null) WriteNet.CHANNEL.sendToServer(new WriteNet.Confirm(token));
    }

    /** The Cancel button (always allowed). */
    public static void cancel(String token) {
        if (WriteNet.CHANNEL != null && token != null) WriteNet.CHANNEL.sendToServer(new WriteNet.Cancel(token));
        ClientWriteState.onPromptClosed(token, "cancelled");
    }

    /** The one-click lock button (ops only on the server; no confirmation). */
    public static void lock(String reason) {
        if (WriteNet.CHANNEL != null) WriteNet.CHANNEL.sendToServer(new WriteNet.Lock(reason == null ? "" : reason));
    }

    /** Ask the server for the current state (done automatically on login). */
    public static void sync() {
        if (WriteNet.CHANNEL != null) WriteNet.CHANNEL.sendToServer(new WriteNet.Sync());
    }

    /** Answer an open decision: pass {@code choice} (one of the offered options) and/or {@code text}; null = not given. */
    public static boolean answerDecision(String card, String decision, String choice, String text) {
        Map<String, Object> a = map();
        a.put("card", card);
        a.put("decision", decision);
        if (choice != null) a.put("choice", choice);
        if (text != null) a.put("text", text);
        return send("decision.answer", a);
    }

    /** New card on an offered board. {@code body} and {@code priority} (0..100) may be null. */
    public static boolean createCard(String board, String title, String body, Integer priority) {
        Map<String, Object> a = map();
        a.put("board", board);
        a.put("title", title);
        if (body != null) a.put("body", body);
        if (priority != null) a.put("priority", Long.valueOf(priority.longValue()));
        return send("card.create", a);
    }

    /** Edit title, body and/or priority of a card (null = leave as is; at least one must be given). */
    public static boolean editCard(String card, String title, String body, Integer priority) {
        Map<String, Object> a = map();
        a.put("card", card);
        if (title != null) a.put("title", title);
        if (body != null) a.put("body", body);
        if (priority != null) a.put("priority", Long.valueOf(priority.longValue()));
        return send("card.edit", a);
    }

    /** A comment on any card. */
    public static boolean comment(String card, String text) {
        Map<String, Object> a = map();
        a.put("card", card);
        a.put("comment", text);
        return send("card.edit", a);
    }

    /** One message in a chat window; {@code conversation} is any id the window keeps (up to 64 chars). */
    public static boolean chat(String agent, String conversation, String text) {
        Map<String, Object> a = map();
        a.put("agent", agent);
        a.put("conversation", conversation);
        a.put("text", text);
        return send("agent.chat", a);
    }

    /** /ask: one question, one answer in {@link ClientWriteState#chatLines()}. */
    public static boolean ask(String agent, String text) {
        Map<String, Object> a = map();
        a.put("agent", agent);
        a.put("text", text);
        return send("agent.ask", a);
    }

    public static boolean restartService(String name) {
        Map<String, Object> a = map();
        a.put("service", name);
        return send("service.restart", a);
    }

    public static boolean runJob(String name) {
        Map<String, Object> a = map();
        a.put("job", name);
        return send("cron.run", a);
    }
}
