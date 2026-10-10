import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.agentcraft.gtnh.write.client.ClientWriteState;
import dev.agentcraft.gtnh.write.client.DecisionKind;
import dev.agentcraft.gtnh.write.client.DecisionKind.Kind;
import dev.agentcraft.gtnh.write.client.FormLogic;

/** The client side that has no Minecraft types: decision kinds shown by the footer, form checks, the client state. */
public class ClientCheck {

    static Kind k(String adapterKind, String question, String... options) {
        return DecisionKind.classify(adapterKind, question, Arrays.asList(options));
    }

    static String state(boolean armed, boolean locked, boolean dry, String reason, String lockInfo) {
        String json = "{\"armed\":" + armed + ",\"locked\":" + locked + ",\"dryRun\":" + dry + ",\"link\":true,\"reason\":\"" + reason + "\",\"lockInfo\":\"" + lockInfo
            + "\",\"revision\":\"r.1\",\"capabilities\":{\"card.dispatch\":{\"tier\":2,\"confirm\":true,\"enabled\":true},\"card.edit\":{\"tier\":1,\"confirm\":false,\"enabled\":false}},"
            + "\"boards\":[\"main\"],\"profiles\":[\"builder-a\"],\"services\":[\"service-1\"],\"jobs\":[\"job-a1\"],\"agents\":[\"helper-a\"]}";
        return json;
    }

    public static void main(String[] a) {
        // ---- decision kinds (mirror of hermes_control/classify.py; display only) -------------------------------
        Check.ok(k("question", "QUESTION q1: Which option? ", "Option A", "Option B") == Kind.QUESTION, "plain question with choices");
        Check.ok(k("question", "QUESTION q2: What should it be called?") == Kind.QUESTION, "open question without choices");
        Check.ok(k("permission", "PERMISSION p1: run the command on host-a", "Approve", "Deny") == Kind.PERMISSION, "permission halt");
        Check.ok(k("question", "QUESTION q3: may I proceed?", "Approve", "Deny") == Kind.PERMISSION, "an Approve-like choice makes it a permission, whatever the label says");
        Check.ok(k("question", "QUESTION q3: Allow the build to use the network?", "Yes", "No") == Kind.PERMISSION, "permission words in the head win");
        Check.ok(k("question", "QUESTION q4: pick", "Grant access", "No") == Kind.PERMISSION, "grant is approve-like");
        Check.ok(k("question", "DEMO READY h1: the feature is ready for review", "Send to review", "Revise") == Kind.HANDOFF, "demo-ready hand-off");
        Check.ok(k("question", "REVISE r1: please redo it") == Kind.HANDOFF, "revise hand-off");
        Check.ok(k("question", "Something nobody labelled") == Kind.UNKNOWN, "unlabelled decision is read-only");
        Check.ok(k("question", "needs input n1: which colour?") == Kind.QUESTION, "NEEDS INPUT prefix, any case");
        Check.ok(k("question", "QUESTION q1: " + DecisionKind.WITHHELD) == Kind.UNKNOWN, "withheld text is read-only");
        Check.ok(k("question", "QUESTION q1: \u200bPERMISSION to run") == Kind.PERMISSION, "zero-width characters do not hide a permission");
        Check.ok(k("question", "QUESTION q1: \uff21\uff50\uff50\uff52\uff4f\uff56\uff45 the merge") == Kind.PERMISSION, "full-width look-alike is normalised");
        Check.ok(DecisionKind.offersDeny(Arrays.asList("Approve", "Deny")), "Deny offered");
        Check.ok(!DecisionKind.offersDeny(Arrays.asList("Approve", "deny")), "only the exact word Deny counts");
        Check.ok(!DecisionKind.offersDeny(new ArrayList<String>()), "no options, no Deny");
        Check.ok(DecisionKind.readOnlyNote(Kind.PERMISSION).toLowerCase().contains("approve outside the game"), "permission note says approve outside the game");
        Check.ok(DecisionKind.readOnlyNote(Kind.HANDOFF).toLowerCase().contains("outside the game"), "hand-off note says outside the game");
        Check.ok(DecisionKind.readOnlyNote(Kind.UNKNOWN).toLowerCase().contains("outside the game"), "unknown note says outside the game");

        // ---- form logic --------------------------------------------------------------------------------------------
        Check.eq(FormLogic.cardId("main:t-1", "main"), "t-1", "board prefix stripped from a wall id");
        Check.eq(FormLogic.cardId("t-1", "main"), "t-1", "plain id kept");
        Check.eq(FormLogic.cardId("other:t-1", "main"), "other:t-1", "foreign prefix kept");
        Check.eq(FormLogic.pickBoard("main", Arrays.asList("main", "ops")), "main", "own board when offered");
        Check.eq(FormLogic.pickBoard("", Arrays.asList("main")), "main", "the only offered board when the card names none");
        Check.eq(FormLogic.pickBoard("", Arrays.asList("main", "ops")), null, "ambiguous board is not guessed");
        Check.eq(FormLogic.pickBoard("secret", Arrays.asList("main")), null, "a board the policy does not offer is not used");
        Check.eq(FormLogic.pickBoard("main", new ArrayList<String>()), null, "no boards offered");
        Check.eq(FormLogic.parsePriority(""), Integer.MIN_VALUE, "empty priority = not given");
        Check.eq(FormLogic.parsePriority("100"), 100, "priority 100");
        Check.eq(FormLogic.parsePriority("101"), -1, "priority 101 invalid");
        Check.eq(FormLogic.parsePriority("-1"), -1, "negative priority invalid");
        Check.eq(FormLogic.parsePriority("1x"), -1, "non-digit priority invalid");
        FormLogic.Edit e = FormLogic.edit("Old", "old body", 5, "Old", "old body", "5");
        Check.eq(e.error, "Nothing changed.", "an unchanged edit is not sent");
        e = FormLogic.edit("Old", "old body", 5, "New", "old body", "5");
        Check.ok(e.error == null && "New".equals(e.title) && e.body == null && e.priority == null, "only the changed title is sent");
        e = FormLogic.edit("Old", "", 0, "Old", "more", "70");
        Check.ok(e.error == null && e.title == null && "more".equals(e.body) && e.priority != null && e.priority.intValue() == 70, "body and priority sent");
        e = FormLogic.edit("Old", "", 0, "  ", "", "");
        Check.ok(e.error != null, "empty title refused");
        e = FormLogic.edit("Old", "", 0, "x", "", "500");
        Check.ok(e.error != null, "bad priority refused");
        Check.ok(FormLogic.createError("main", "A title", "", "") == null, "create ok");
        Check.ok(FormLogic.createError(null, "A title", "", "") != null, "create needs a board");
        Check.ok(FormLogic.createError("main", " ", "", "") != null, "create needs a title");
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 121; i++) big.append('x');
        Check.ok(FormLogic.createError("main", big.toString(), "", "") != null, "121 char title refused");
        Check.ok(FormLogic.commentError("") != null && FormLogic.commentError("hi") == null, "comment needs text");
        Check.eq(FormLogic.conversationId("helper-a"), "mc-helper-a", "conversation id");
        Check.ok(FormLogic.conversationId("a b/c").matches("[A-Za-z0-9._:-]{1,64}"), "conversation id uses the id charset");
        StringBuilder longAgent = new StringBuilder();
        for (int i = 0; i < 200; i++) longAgent.append('a');
        Check.ok(FormLogic.conversationId(longAgent.toString()).length() <= 64, "conversation id is at most 64 chars");
        Check.eq(FormLogic.secondsLeft(60_000), 60, "60 s shows 60");
        Check.eq(FormLogic.secondsLeft(59_001), 60, "countdown rounds up");
        Check.eq(FormLogic.secondsLeft(1), 1, "1 ms left shows 1 s");
        Check.eq(FormLogic.secondsLeft(0), 0, "0 left");
        Check.eq(FormLogic.secondsLeft(-5), 0, "never negative");
        Check.ok(FormLogic.fractionLeft(30_000, 60_000) == 0.5F, "half the window");
        Check.ok(FormLogic.fractionLeft(90_000, 60_000) == 1F && FormLogic.fractionLeft(-1, 60_000) == 0F, "fraction clamped");
        List<String> fl = FormLogic.firstLines("\n first \r\n\r\n second\nthird\nfourth", 3);
        Check.ok(fl.size() == 3 && fl.get(0).equals("first") && fl.get(2).equals("third"), "first non-empty body lines");
        Check.eq(FormLogic.resultLine("applied", "", true), "applied (dry run: nothing ran)", "dry-run result line");
        Check.eq(FormLogic.resultLine("applied", "", false), "applied", "real result line");
        Check.eq(FormLogic.resultLine("refused", "locked", false), "refused: locked", "refusal line");
        Check.ok(FormLogic.resultLine("unknown", "", false).contains("check outside the game"), "unknown says check outside");

        // ---- the client state the screens read ----------------------------------------------------------------------
        ClientWriteState.reset();
        Check.ok(!ClientWriteState.canWrite(), "fresh state: cannot write");
        Check.ok(ClientWriteState.statusLine().startsWith("writes disarmed: "), "fresh state says disarmed: " + ClientWriteState.statusLine());
        ClientWriteState.onState(state(true, false, true, "", ""));
        Check.ok(ClientWriteState.canWrite(), "armed state: can write");
        Check.ok(ClientWriteState.statusLine().startsWith("writes armed") && ClientWriteState.statusLine().contains("DRY RUN"), "armed + dry run line: " + ClientWriteState.statusLine());
        Check.ok(ClientWriteState.policy.usable("card.dispatch") && !ClientWriteState.policy.usable("card.edit"), "usable follows enabled");
        Check.ok(ClientWriteState.policy.profiles.equals(Arrays.asList("builder-a")), "profiles read");
        ClientWriteState.onState(state(true, true, true, "", "by QAOwner: panic"));
        Check.ok(!ClientWriteState.canWrite() && ClientWriteState.statusLine().startsWith("writes locked: by QAOwner: panic"), "locked line: " + ClientWriteState.statusLine());
        ClientWriteState.onState(state(false, false, false, "online mode is off", ""));
        Check.eq(ClientWriteState.statusLine(), "writes disarmed: online mode is off", "disarmed line has the reason");
        ClientWriteState.onState(state(true, false, false, "", ""));
        Check.eq(ClientWriteState.statusLine(), "writes armed", "armed line");

        // the Confirm prompt: countdown, closed by lock / disarm / close
        ClientWriteState.onPrompt("req-1", "tok-1", 60_000, "{\"card\":\"t-demo-1\",\"title\":\"Add it\",\"board\":\"main\",\"profile\":\"builder-a\",\"model\":\"model-a\",\"body\":\"line 1\\nline 2\"}");
        ClientWriteState.PromptInfo p = ClientWriteState.pendingPrompt();
        Check.ok(p != null && p.token.equals("tok-1") && p.msLeft() > 59_000 && p.msLeft() <= 60_000, "prompt open with about 60 s");
        Check.eq(p.summary.get("profile"), "builder-a", "prompt summary profile");
        Check.eq(p.summary.get("model"), "model-a", "prompt summary model");
        ClientWriteState.onState(state(true, true, false, "", "by QAOwner: panic"));
        Check.ok(ClientWriteState.pendingPrompt() == null, "a lock state drops the open prompt");
        ClientWriteState.onState(state(true, false, false, "", ""));
        ClientWriteState.onPrompt("req-2", "tok-2", 60_000, "{}");
        ClientWriteState.onState(state(false, false, false, "control link down", ""));
        Check.ok(ClientWriteState.pendingPrompt() == null, "a disarm state drops the open prompt");
        ClientWriteState.onState(state(true, false, false, "", ""));
        ClientWriteState.onPrompt("req-3", "tok-3", 60_000, "{}");
        ClientWriteState.onPromptClosed("tok-other", "cancelled");
        Check.ok(ClientWriteState.pendingPrompt() != null, "closing another token leaves this prompt");
        ClientWriteState.onPromptClosed("tok-3", "expired");
        Check.ok(ClientWriteState.pendingPrompt() == null, "server closes the prompt");
        Check.ok(ClientWriteState.lastResult() != null && "cancelled".equals(ClientWriteState.lastResult().status), "a closed prompt leaves a cancelled result");
        ClientWriteState.onPrompt("req-4", "tok-4", 0, "{}");
        Check.ok(ClientWriteState.pendingPrompt() == null, "a prompt with no time left is gone at once");

        // results and chat feed the printers
        long r0 = ClientWriteState.resultSeq.get(), c0 = ClientWriteState.chatSeq.get();
        ClientWriteState.onResult("req-1", "card.dispatch", "applied", "", "{\"mock\":\"recorded\"}", "a-1", true);
        ClientWriteState.onChat("conv", "helper-a", "hello there", true);
        Check.ok(ClientWriteState.resultSeq.get() == r0 + 1 && ClientWriteState.chatSeq.get() == c0 + 1, "sequence numbers move");
        ClientWriteState.ResultInfo ri = ClientWriteState.resultFor("req-1");
        Check.ok(ri != null && ri.dryRun && "applied".equals(ri.status) && !ri.isFailure(), "result by request id, dry run flag");
        Check.ok(ClientWriteState.recentChat(5).get(0).text.equals("hello there"), "newest chat line first");
        Check.ok(ClientWriteState.resultFor("nope") == null, "unknown request id");
        ClientWriteState.localRefusal("card.dispatch", "writes locked");
        Check.ok(ClientWriteState.lastResult().isFailure() && "local".equals(ClientWriteState.lastResult().requestId), "local refusal is a failure result");

        Check.summary("ClientCheck");
    }
}
