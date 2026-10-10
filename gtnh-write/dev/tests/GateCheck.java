import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import dev.agentcraft.gtnh.write.core.Gate;

/** docs/write-path.md 7.1 and open question 6: the arming gate as a pure function. */
public class GateCheck {

    interface Tweak {

        void apply(Check.Facts f);
    }

    static final String V3_OWNER = "00000000-0000-3000-8000-000000000001";

    static Gate.Result run(Tweak t) {
        Check.Facts f = new Check.Facts();
        if (t != null) t.apply(f);
        return Gate.evaluate(f);
    }

    static void fails(String name, String check, Tweak t) {
        Gate.Result r = run(t);
        Check.ok(!r.armed, name + ": disarmed");
        Gate.Check c = r.get(check);
        Check.ok(c != null && c.status == Gate.Status.FAIL, name + ": check " + check + " is FAIL");
        Check.ok(r.reason.startsWith(check) || r.reason.length() > 0, name + ": reason given (" + r.reason + ")");
    }

    public static void main(String[] a) {
        Gate.Result base = run(null);
        Check.ok(base.armed, "baseline: all checks pass -> armed");
        Check.eq(base.reason, "", "baseline: no reason");
        int pass = 0;
        for (Gate.Check c : base.checks) if (c.status == Gate.Status.PASS) pass++;
        Check.ok(pass >= 12 && base.overrideActive == false, "baseline: every check PASS, no override (" + pass + " PASS)");

        fails("not dedicated", "dedicated-server", f -> f.dedicated = false);
        fails("online-mode off", "online-mode", f -> f.online = false);
        fails("whitelist off", "whitelist-enforced", f -> f.wlOn = false);
        fails("whitelist has a second player", "whitelist-is-owner", f -> f.whitelist.add(Check.OTHER));
        fails("whitelist empty", "whitelist-is-owner", f -> f.whitelist.clear());
        fails("whitelist is someone else", "whitelist-is-owner", f -> f.whitelist = new ArrayList<>(Collections.singletonList(Check.OTHER)));
        fails("whitelist entry without uuid", "whitelist-is-owner", f -> f.whitelist = new ArrayList<>(Collections.singletonList("?Steve")));
        fails("another op", "ops-only-owner", f -> f.ops.add(Check.OTHER));
        fails("owner op plus another", "ops-only-owner", f -> f.ops = new ArrayList<>(Arrays.asList(Check.OWNER, Check.OTHER)));
        fails("owner not set", "owner-uuid-wellformed", f -> f.owner = "");
        fails("owner not canonical (uppercase)", "owner-uuid-wellformed", f -> f.owner = "0000000A-0000-4000-8000-00000000000B");
        fails("owner is a name", "owner-uuid-wellformed", f -> f.owner = "Steve");
        fails("v3 owner", "owner-uuid-v4", f -> {
            f.owner = V3_OWNER;
            f.whitelist = new ArrayList<>(Collections.singletonList(V3_OWNER));
            f.actors = new ArrayList<>(Collections.singletonList(V3_OWNER));
        });
        fails("bad variant owner", "owner-uuid-v4", f -> {
            String o = "00000000-0000-4000-c000-000000000001";
            f.owner = o;
            f.whitelist = new ArrayList<>(Collections.singletonList(o));
            f.actors = new ArrayList<>(Collections.singletonList(o));
        });
        fails("link not ready", "control-link", f -> f.linkReady = false);
        fails("policy lists another actor", "policy-actors-equal-owner", f -> f.actors = new ArrayList<>(Collections.singletonList(Check.OTHER)));
        fails("policy lists two actors", "policy-actors-equal-owner", f -> f.actors = new ArrayList<>(Arrays.asList(Check.OWNER, Check.OTHER)));
        fails("policy lists no actors", "policy-actors-equal-owner", f -> f.actors.clear());
        fails("control service not armed", "hermes-armed", f -> f.hArmed = false);
        fails("hermes lock set", "not-locked-hermes", f -> f.hLocked = true);
        fails("game lock set", "not-locked-game", f -> f.gLocked = true);
        fails("audit not writable", "audit-writable", f -> f.audit = false);
        fails("clock fault", "clock", f -> f.clockFault = true);

        // every failure only needs the single cause; and an armed gate stays armed with the owner as the only op
        Check.ok(run(f -> f.ops.add(Check.OWNER)).armed, "the owner may be the only op");
        Check.ok(run(f -> f.ops.clear()).armed, "an empty ops list passes");

        // the dedicated check comes before anything that exists only on a dedicated server (the fake throws otherwise)
        Gate.Result sp = run(f -> f.dedicated = false);
        Check.ok(!sp.armed && sp.get("whitelist-enforced").status == Gate.Status.FAIL, "non-dedicated: whitelist checks fail without being asked");

        // actor per request
        Check.eq(Gate.actorDenied(new Check.Facts(), Check.OWNER), null, "the owner may write");
        Check.ok(Gate.actorDenied(new Check.Facts(), Check.OTHER) != null, "another player is denied");
        Check.ok(Gate.actorDenied(new Check.Facts(), null) != null, "no actor is denied");
        Check.ok(Gate.actorDenied(new Check.Facts() {
            {
                owner = "";
            }
        }, Check.OWNER) != null, "no owner configured: everyone denied");

        override();
        Check.summary("GateCheck");
    }

    static Tweak dev(Tweak more) {
        return f -> {
            f.prop = "loopback-dry-run";
            f.online = false;
            f.serverIp = "127.0.0.1";
            f.controlHost = "127.0.0.1";
            f.dryRun = true;
            f.owner = V3_OWNER; // the offline dev player's UUID
            f.whitelist = new ArrayList<>(Collections.singletonList(V3_OWNER));
            f.actors = new ArrayList<>(Collections.singletonList(V3_OWNER));
            if (more != null) more.apply(f);
        };
    }

    static void ignored(String name, Tweak more) {
        Gate.Result r = run(dev(more));
        Check.ok(!r.overrideActive, name + ": override not active");
        Check.ok(!r.armed, name + ": stays disarmed");
        Check.ok(r.get("online-mode").status == Gate.Status.FAIL, name + ": online-mode is a normal FAIL");
        Check.ok(r.overrideNote.startsWith("devOverride ignored"), name + ": status says the property was ignored (" + r.overrideNote + ")");
    }

    static void override() {
        Gate.Result r = run(dev(null));
        Check.ok(r.armed && r.overrideActive, "override honored when every condition holds");
        Check.eq(r.get("online-mode").status, Gate.Status.OVERRIDDEN, "online-mode reported OVERRIDDEN");
        Check.eq(r.get("owner-uuid-v4").status, Gate.Status.OVERRIDDEN, "v4 check reported OVERRIDDEN (offline dev player)");
        Check.ok(r.get("online-mode").status != Gate.Status.PASS && r.get("owner-uuid-v4").status != Gate.Status.PASS, "overridden checks are never PASS");
        Check.ok(r.get("online-mode").detail.contains("OVERRIDDEN (loopback dry run)"), "the detail names the override");

        ignored("not dedicated", f -> f.dedicated = false);
        ignored("server-ip empty (binds everything)", f -> f.serverIp = "");
        ignored("server-ip 0.0.0.0", f -> f.serverIp = "0.0.0.0");
        ignored("server-ip 127.0.0.2", f -> f.serverIp = "127.0.0.2");
        ignored("server-ip LAN-ish", f -> f.serverIp = "192.0.2.10");
        ignored("controlUrl host not loopback", f -> f.controlHost = "192.0.2.7");
        ignored("controlUrl host localhost name", f -> f.controlHost = "localhost");
        ignored("policy dryRun false", f -> f.dryRun = false);
        ignored("no handshake yet", f -> f.linkReady = false);
        ignored("tainted by a non-dryRun result", f -> f.tainted = true);
        ignored("wrong property value", f -> f.prop = "yes");
        ignored("property with trailing text", f -> f.prop = "loopback-dry-run ");

        // all other checks still apply under a valid override
        Check.ok(!run(dev(f -> f.wlOn = false)).armed, "override: whitelist off still disarms");
        Check.ok(!run(dev(f -> f.whitelist.add(Check.OTHER))).armed, "override: a second whitelisted player still disarms");
        Check.ok(!run(dev(f -> f.ops.add(Check.OTHER))).armed, "override: another op still disarms");
        Check.ok(!run(dev(f -> f.hLocked = true)).armed, "override: Hermes lock still disarms");
        Check.ok(!run(dev(f -> f.gLocked = true)).armed, "override: game lock still disarms");
        Check.ok(!run(dev(f -> f.audit = false)).armed, "override: unwritable audit still disarms");
        Check.ok(!run(dev(f -> f.actors = new ArrayList<>(Arrays.asList(V3_OWNER, Check.OTHER)))).armed, "override: wider policy actors still disarm");
        Check.ok(!run(dev(f -> f.owner = "")).armed, "override: no owner still disarms");
        Check.ok(!run(dev(f -> f.owner = "Steve")).armed, "override: a name instead of a UUID still disarms");
        Check.ok(!run(dev(f -> f.clockFault = true)).armed, "override: clock fault still disarms");
        // no property: a v3 owner and offline mode are plain failures
        Gate.Result plain = run(f -> {
            f.online = false;
            f.owner = V3_OWNER;
        });
        Check.ok(!plain.armed && plain.get("online-mode").status == Gate.Status.FAIL, "without the property offline mode fails");
        Check.ok(plain.get("dev-override") == null, "without the property there is no override line");
    }
}
