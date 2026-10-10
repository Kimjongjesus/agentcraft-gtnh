import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dev.agentcraft.gtnh.write.core.GateFacts;
import dev.agentcraft.gtnh.write.proto.Clock;
import dev.agentcraft.gtnh.write.proto.Frames;

/** Tiny assertion harness and shared fakes for the pure checks (no Minecraft, no test framework). */
public final class Check {

    public static final String OWNER = "00000000-0000-4000-8000-000000000001";
    public static final String OTHER = "00000000-0000-4000-8000-000000000002";
    public static final byte[] KEY = new byte[32];
    public static final String SESSION = "0123456789abcdef0123456789abcdef";

    static {
        for (int i = 0; i < 32; i++) KEY[i] = (byte) i;
    }

    private static int pass, fail;
    private static final List<String> failures = new ArrayList<>();

    private Check() {}

    public static void ok(boolean cond, String name) {
        if (cond) {
            pass++;
        } else {
            fail++;
            failures.add(name);
            System.out.println("  FAIL " + name);
        }
    }

    public static void eq(Object a, Object b, String name) {
        boolean same = a == null ? b == null : a.equals(b);
        if (!same) System.out.println("    expected <" + b + "> got <" + a + ">");
        ok(same, name);
    }

    public static void summary(String suite) {
        System.out.println(suite + ": " + pass + " checks passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    public static File tmpDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix).toFile();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Mutable clock. */
    public static final class FakeClock implements Clock {

        public long t = 1_800_000_000_000L;

        @Override
        public long now() {
            return t;
        }
    }

    /** A signed control -> game frame; {@code body} is a JSON fragment such as {@code ,"challenge":"..."}. */
    public static String frame(String type, String session, String id, String nonce, long ts, String body) {
        return Frames.encode(KEY, type, "{\"type\":\"" + type + "\",\"session\":\"" + session + "\",\"dir\":\"c2g\",\"id\":\"" + id + "\",\"nonce\":\"" + nonce + "\",\"ts\":" + ts + body + "}");
    }

    public static String nonce(int n) {
        return String.format("%032x", n);
    }

    /** Facts of a healthy, armed server; every field can be flipped. */
    public static class Facts implements GateFacts {

        public boolean dedicated = true, online = true, wlOn = true, linkReady = true, dryRun = false, hArmed = true, hLocked = false, gLocked = false,
            audit = true, clockFault = false, tainted = false;
        public List<String> whitelist = new ArrayList<>(Collections.singletonList(OWNER));
        public List<String> ops = new ArrayList<>();
        public String owner = OWNER, serverIp = "", controlHost = "127.0.0.1", prop = null, note = "connecting";
        public List<String> actors = new ArrayList<>(Arrays.asList(OWNER));

        @Override
        public boolean dedicated() {
            return dedicated;
        }

        @Override
        public boolean onlineMode() {
            return online;
        }

        @Override
        public boolean whitelistEnforced() {
            if (!dedicated) throw new IllegalStateException("whitelistEnforced() called on a non-dedicated server");
            return wlOn;
        }

        @Override
        public List<String> whitelist() {
            if (!dedicated) throw new IllegalStateException("whitelist() called on a non-dedicated server");
            return whitelist;
        }

        @Override
        public List<String> ops() {
            if (!dedicated) throw new IllegalStateException("ops() called on a non-dedicated server");
            return ops;
        }

        @Override
        public String owner() {
            return owner;
        }

        @Override
        public String serverIp() {
            return serverIp;
        }

        @Override
        public String controlHost() {
            return controlHost;
        }

        @Override
        public String devOverrideProperty() {
            return prop;
        }

        @Override
        public boolean linkReady() {
            return linkReady;
        }

        @Override
        public String linkNote() {
            return note;
        }

        @Override
        public List<String> policyActors() {
            return linkReady ? actors : null;
        }

        @Override
        public boolean policyDryRun() {
            return dryRun;
        }

        @Override
        public boolean hermesArmed() {
            return hArmed;
        }

        @Override
        public boolean hermesLocked() {
            return hLocked;
        }

        @Override
        public String hermesLockReason() {
            return "test";
        }

        @Override
        public boolean gameLocked() {
            return gLocked;
        }

        @Override
        public String gameLockInfo() {
            return "test";
        }

        @Override
        public boolean auditWritable() {
            return audit;
        }

        @Override
        public String auditError() {
            return "test";
        }

        @Override
        public boolean clockFault() {
            return clockFault;
        }

        @Override
        public boolean overrideTainted() {
            return tainted;
        }
    }
}
