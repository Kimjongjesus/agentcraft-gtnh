package dev.agentcraft.gtnh.write.proto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed control -> game messages (docs/action-protocol.md section 4). Immutable after parsing. */
public abstract class Msg {

    public String type = "", id = "", session = "", nonce = "";
    public long ts;

    public static final class Challenge extends Msg {

        public String challenge = "";
    }

    public static final class Ack extends Msg {

        public List<String> features = new ArrayList<>();
    }

    public static final class CapInfo {

        public int tier;
        public boolean confirm, enabled;
        /** limits shown as display text only; the game enforces Caps' own defaults. */
        public Map<String, String> limits = new LinkedHashMap<>();
    }

    public static final class Policy extends Msg {

        public String revision = "";
        public boolean dryRun;
        public List<String> actors = new ArrayList<>(), services = new ArrayList<>(), jobs = new ArrayList<>(), boards = new ArrayList<>(),
            profiles = new ArrayList<>(), agents = new ArrayList<>();
        public Map<String, CapInfo> capabilities = new LinkedHashMap<>();
    }

    public static final class State extends Msg {

        public boolean armed, locked;
        public String lockReason = "", lockedBy = "";
        public long since;
    }

    public static final class Prompt extends Msg {

        public String re = "", token = "";
        public long expiresAt;
        public Map<String, String> summary = new LinkedHashMap<>();
    }

    public static final class Result extends Msg {

        public String re = "", status = "", error = "", audit = "";
        public Map<String, String> result = new LinkedHashMap<>();
        public boolean dryRun;
    }

    public static final class Chat extends Msg {

        public String re = "", conversation = "", agentId = "", text = "";
        public boolean fin;
    }

    public static final class Error extends Msg {

        public String re = "", error = "";
    }

    static final List<String> STATUSES = Collections.unmodifiableList(
        java.util.Arrays.asList("applied", "refused", "queued", "unknown", "prompted", "cancelled"));

    /** Parses the type-specific part of a c2g payload; throws Fields.Bad for anything off-contract. */
    static Msg parseBody(String type, Fields f) throws Fields.Bad {
        switch (type) {
            case "action.challenge": {
                Challenge m = new Challenge();
                m.challenge = f.hex("challenge", 32);
                f.done();
                return m;
            }
            case "ack": {
                Ack m = new Ack();
                Fields r = new Fields(f.obj("result"), "ack.result");
                m.features = r.strings("features", 8, 32);
                r.done();
                f.done();
                return m;
            }
            case "action.policy": {
                Policy m = new Policy();
                m.revision = f.str("revision", 0, 64);
                m.dryRun = f.bool("dryRun");
                for (String a : f.strings("actors", 16, 36)) {
                    if (!Fields.UUID.matcher(a).matches()) throw new Fields.Bad("policy: actor is not a lowercase canonical uuid");
                    m.actors.add(a);
                }
                Map<String, Object> caps = f.obj("capabilities");
                if (caps.size() > 16) throw new Fields.Bad("policy: too many capabilities");
                for (Map.Entry<String, Object> e : caps.entrySet()) {
                    Caps.Cap known = Caps.get(e.getKey());
                    if (known == null) throw new Fields.Bad("policy: unknown capability " + clip(e.getKey()));
                    if (!(e.getValue() instanceof Map)) throw new Fields.Bad("policy: capability entry must be an object");
                    @SuppressWarnings("unchecked")
                    Fields c = new Fields((Map<String, Object>) e.getValue(), "policy.capability");
                    CapInfo ci = new CapInfo();
                    ci.tier = (int) c.integer("tier", 0, 3);
                    if (ci.tier != known.tier) throw new Fields.Bad("policy: tier of " + known.name + " differs from the contract");
                    ci.confirm = c.bool("confirm");
                    ci.enabled = c.bool("enabled");
                    Map<String, Object> lim = c.obj("limits");
                    if (lim.size() > 16) throw new Fields.Bad("policy: too many limits");
                    for (Map.Entry<String, Object> l : lim.entrySet()) {
                        Object v = l.getValue();
                        if (!(v instanceof String || v instanceof Long || v instanceof Boolean || v instanceof Double)) {
                            throw new Fields.Bad("policy: limits values must be scalars");
                        }
                        if (l.getKey().length() > 64 || (v instanceof String && ((String) v).length() > 64)) throw new Fields.Bad("policy: limits entry too long");
                        ci.limits.put(l.getKey(), String.valueOf(v));
                    }
                    c.done();
                    m.capabilities.put(e.getKey(), ci);
                }
                m.services = f.strings("services", 64, 64);
                m.jobs = f.strings("jobs", 64, 64);
                m.boards = f.strings("boards", 64, 64);
                m.profiles = f.strings("profiles", 64, 64);
                m.agents = f.strings("agents", 64, 64);
                f.done();
                return m;
            }
            case "action.state": {
                State m = new State();
                m.armed = f.bool("armed");
                m.locked = f.bool("locked");
                m.lockReason = f.str("lockReason", 0, 200);
                m.lockedBy = f.str("lockedBy", 0, 64);
                m.since = f.integer("since", 0, Long.MAX_VALUE);
                f.done();
                return m;
            }
            case "action.prompt": {
                Prompt m = new Prompt();
                m.re = f.id("re");
                m.token = f.hex("token", 32);
                m.expiresAt = f.integer("expiresAt", 0, Long.MAX_VALUE);
                Fields s = new Fields(f.obj("summary"), "prompt.summary");
                for (String k : new String[] { "card", "title", "board", "profile", "model", "body" }) {
                    m.summary.put(k, s.str(k, 0, 600));
                }
                s.done();
                f.done();
                return m;
            }
            case "action.result": {
                Result m = new Result();
                m.re = f.id("re");
                m.status = f.str("status", 1, 16);
                if (!STATUSES.contains(m.status)) throw new Fields.Bad("result: unknown status");
                m.error = f.str("error", 0, 200);
                Map<String, Object> r = f.obj("result");
                if (r.size() > 16) throw new Fields.Bad("result: too many keys");
                for (Map.Entry<String, Object> e : r.entrySet()) {
                    if (!(e.getValue() instanceof String) || ((String) e.getValue()).length() > 600 || e.getKey().length() > 64) {
                        throw new Fields.Bad("result: values must be strings of at most 600 chars");
                    }
                    m.result.put(e.getKey(), (String) e.getValue());
                }
                m.audit = f.str("audit", 0, 64);
                m.dryRun = f.bool("dryRun");
                f.done();
                return m;
            }
            case "action.chat": {
                Chat m = new Chat();
                m.re = f.id("re");
                m.conversation = f.str("conversation", 0, 64);
                m.agentId = f.str("agentId", 0, 64);
                m.text = f.str("text", 0, 2000);
                m.fin = f.bool("final");
                f.done();
                return m;
            }
            case "error": {
                Error m = new Error();
                m.re = f.idOrEmpty("re");
                m.error = f.str("error", 0, 200);
                f.done();
                return m;
            }
            default:
                throw new Fields.Bad("unknown type");
        }
    }

    private static String clip(String s) {
        return s.length() > 40 ? s.substring(0, 40) : s;
    }
}
