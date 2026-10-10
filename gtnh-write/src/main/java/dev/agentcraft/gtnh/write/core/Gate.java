package dev.agentcraft.gtnh.write.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The arming gate of docs/write-path.md section 7.1 as a pure function of {@link GateFacts}.
 * Every failing check keeps the module disarmed. The dev override (open question 6) turns the
 * online-mode and version 4 checks into OVERRIDDEN, and only when the server is dedicated and bound to
 * 127.0.0.1, the control URL is loopback and the control service reports dryRun:true; every other
 * check still applies, and without those conditions the property is ignored.
 */
public final class Gate {

    public static final String OVERRIDE_VALUE = "loopback-dry-run";
    public static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    public static final Pattern UUID_V4 = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    public enum Status {
        PASS,
        FAIL,
        OVERRIDDEN,
        INFO
    }

    public static final class Check {

        public final String name;
        public final Status status;
        public final String detail;

        Check(String name, Status status, String detail) {
            this.name = name;
            this.status = status;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return status + " " + name + (detail.isEmpty() ? "" : " - " + detail);
        }
    }

    public static final class Result {

        public final List<Check> checks = new ArrayList<>();
        public boolean armed;
        /** First failing check as "name: detail", or "" when armed. */
        public String reason = "";
        /** The dev override is in force (online mode and v4 reported OVERRIDDEN). */
        public boolean overrideActive;
        /** What the dev override did or why it was ignored ("" when not requested). */
        public String overrideNote = "";

        public Check get(String name) {
            for (Check c : checks) if (c.name.equals(name)) return c;
            return null;
        }
    }

    private Gate() {}

    public static boolean isLoopbackHost(String h) {
        if (h == null) return false;
        String t = h.trim();
        return t.equals("127.0.0.1") || t.equals("[::1]") || t.equals("::1");
    }

    /** null when the actor may write, else why not. Called on every request with the sender's authenticated UUID. */
    public static String actorDenied(GateFacts f, String actorUuid) {
        String owner = f.owner();
        if (owner == null || !UUID.matcher(owner).matches()) return "write.owner is not set to a lowercase canonical UUID";
        if (actorUuid == null || !owner.equals(actorUuid.toLowerCase(java.util.Locale.ROOT))) return "only the configured owner may write";
        return null;
    }

    public static Result evaluate(GateFacts f) {
        Result r = new Result();
        boolean dedicated = f.dedicated();
        String owner = f.owner() == null ? "" : f.owner();
        boolean ownerWellFormed = UUID.matcher(owner).matches();

        // dev override (decided first because it changes two checks)
        String prop = f.devOverrideProperty();
        boolean requested = prop != null && !prop.isEmpty();
        if (requested) {
            String why = null;
            if (!OVERRIDE_VALUE.equals(prop)) why = "unknown value (only " + OVERRIDE_VALUE + " exists)";
            else if (!dedicated) why = "not a dedicated server";
            else if (!"127.0.0.1".equals(f.serverIp() == null ? "" : f.serverIp().trim())) why = "server-ip is not exactly 127.0.0.1 (server not bound to loopback)";
            else if (!isLoopbackHost(f.controlHost())) why = "controlUrl host is not loopback";
            else if (f.overrideTainted()) why = "a control result without dryRun was seen; override disabled until restart";
            else if (!f.linkReady()) why = "control service dryRun not confirmed (no handshake yet)";
            else if (!f.policyDryRun()) why = "control service policy does not say dryRun:true";
            if (why == null) {
                r.overrideActive = true;
                r.overrideNote = "loopback dry run override ACTIVE: nothing real can execute";
            } else {
                r.overrideNote = "devOverride ignored: " + why;
            }
        }
        if (requested) r.checks.add(new Check("dev-override", Status.INFO, r.overrideNote));

        r.checks.add(
            dedicated ? new Check("dedicated-server", Status.PASS, "")
                : new Check("dedicated-server", Status.FAIL, "writes arm only on a dedicated server (never single player or a LAN-opened world)"));

        if (r.overrideActive) r.checks.add(new Check("online-mode", Status.OVERRIDDEN, "OVERRIDDEN (loopback dry run)"));
        else r.checks.add(f.onlineMode() ? new Check("online-mode", Status.PASS, "") : new Check("online-mode", Status.FAIL, "online-mode=false: a player's UUID is a hash of the name they type"));

        if (!dedicated) {
            r.checks.add(new Check("whitelist-enforced", Status.FAIL, "not a dedicated server"));
            r.checks.add(new Check("whitelist-is-owner", Status.FAIL, "not a dedicated server"));
            r.checks.add(new Check("ops-only-owner", Status.FAIL, "not a dedicated server"));
        } else {
            r.checks.add(
                f.whitelistEnforced() ? new Check("whitelist-enforced", Status.PASS, "")
                    : new Check("whitelist-enforced", Status.FAIL, "the whitelist is off (white-list=false or /whitelist off)"));
            List<String> wl = f.whitelist();
            if (!ownerWellFormed) {
                r.checks.add(new Check("whitelist-is-owner", Status.FAIL, "write.owner is not a valid UUID"));
            } else if (wl.size() == 1 && owner.equals(wl.get(0))) {
                r.checks.add(new Check("whitelist-is-owner", Status.PASS, "1 entry"));
            } else {
                r.checks.add(new Check("whitelist-is-owner", Status.FAIL, "the whitelist must hold exactly the owner (" + wl.size() + " entries)"));
            }
            String badOp = null;
            for (String o : f.ops()) if (!owner.equals(o)) badOp = o;
            r.checks.add(
                badOp == null ? new Check("ops-only-owner", Status.PASS, f.ops().isEmpty() ? "no ops" : "the owner only")
                    : new Check("ops-only-owner", Status.FAIL, "an op other than the owner exists (ops skip the whitelist)"));
        }

        r.checks.add(
            ownerWellFormed ? new Check("owner-uuid-wellformed", Status.PASS, "")
                : new Check("owner-uuid-wellformed", Status.FAIL, owner.isEmpty() ? "write.owner is not set" : "write.owner must be a lowercase canonical UUID"));
        if (r.overrideActive && ownerWellFormed) r.checks.add(new Check("owner-uuid-v4", Status.OVERRIDDEN, "OVERRIDDEN (loopback dry run)"));
        else r.checks.add(
            UUID_V4.matcher(owner).matches() ? new Check("owner-uuid-v4", Status.PASS, "")
                : new Check("owner-uuid-v4", Status.FAIL, "write.owner is not a version 4 UUID (version 3 is an offline-mode name hash)"));

        r.checks.add(
            ownerWellFormed ? new Check("identity-per-request", Status.PASS, "enforced on every request: the sender's authenticated UUID must equal write.owner")
                : new Check("identity-per-request", Status.FAIL, "needs a valid write.owner"));

        if (f.clockFault()) r.checks.add(new Check("clock", Status.FAIL, "the clock moved backwards by more than 60 s; restart the server"));
        else r.checks.add(new Check("clock", Status.PASS, ""));

        boolean ready = f.linkReady();
        r.checks.add(ready ? new Check("control-link", Status.PASS, "signed handshake ok") : new Check("control-link", Status.FAIL, f.linkNote()));
        List<String> actors = f.policyActors();
        if (!ready || actors == null) {
            r.checks.add(new Check("policy-actors-equal-owner", Status.FAIL, "no policy from the control service"));
        } else if (ownerWellFormed && actors.size() == 1 && owner.equals(actors.get(0))) {
            r.checks.add(new Check("policy-actors-equal-owner", Status.PASS, ""));
        } else {
            r.checks.add(new Check("policy-actors-equal-owner", Status.FAIL, "the control service's actors must be exactly [owner] (" + actors.size() + " listed)"));
        }
        if (ready && !f.hermesArmed()) r.checks.add(new Check("hermes-armed", Status.FAIL, "the control service reports armed=false"));
        else r.checks.add(new Check("hermes-armed", ready ? Status.PASS : Status.FAIL, ready ? "" : "no state from the control service"));

        r.checks.add(
            f.gameLocked() ? new Check("not-locked-game", Status.FAIL, "write lock set " + f.gameLockInfo()) : new Check("not-locked-game", Status.PASS, ""));
        r.checks.add(
            ready && f.hermesLocked() ? new Check("not-locked-hermes", Status.FAIL, "Hermes-side lock set: " + f.hermesLockReason())
                : new Check("not-locked-hermes", ready ? Status.PASS : Status.FAIL, ready ? "" : "no state from the control service"));
        r.checks.add(
            f.auditWritable() ? new Check("audit-writable", Status.PASS, "") : new Check("audit-writable", Status.FAIL, "game audit log cannot be written: " + f.auditError()));

        for (Check c : r.checks) {
            if (c.status == Status.FAIL) {
                r.reason = c.name + ": " + c.detail;
                break;
            }
        }
        r.armed = r.reason.isEmpty();
        return r;
    }
}
