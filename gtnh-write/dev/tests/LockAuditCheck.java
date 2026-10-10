import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.agentcraft.gtnh.write.core.RateLimits;
import dev.agentcraft.gtnh.write.core.WriteAudit;
import dev.agentcraft.gtnh.write.core.WriteLock;

/** Lock file rules, audit format and rolling, token bucket and per-capability windows. */
public class LockAuditCheck {

    static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    public static void main(String[] a) throws Exception {
        lock();
        audit();
        buckets();
        Check.summary("LockAuditCheck");
    }

    static void lock() throws Exception {
        Check.FakeClock clk = new Check.FakeClock();
        File d = Check.tmpDir("lock");
        File f = new File(d, "write-lock.json");
        WriteLock l = new WriteLock(f, clk);
        l.load();
        Check.ok(!l.isLocked(), "no lock file: unlocked");
        l.lock("Owner/00000000", "because");
        Check.ok(l.isLocked() && f.isFile(), "lock sets and persists");
        Check.eq(Files.getPosixFilePermissions(f.toPath()), PosixFilePermissions.fromString("rw-------"), "lock file is 0600");
        WriteLock l2 = new WriteLock(f, clk);
        l2.load();
        Check.ok(l2.isLocked() && l2.info().contains("because"), "a restart reads the lock back");
        Check.ok(l2.unlock(false, false) != null && l2.isLocked(), "nobody may unlock");
        Check.ok(l2.unlock(true, false) == null && !l2.isLocked() && !f.exists(), "owner unlock clears and removes the file");
        l2.lock("x", "y");
        Check.ok(l2.unlock(false, true) == null && !l2.isLocked(), "console unlock works");

        String[] garbage = { "not json", "[]", "{}", "{\"locked\":false,\"by\":\"\",\"reason\":\"\",\"since\":0}", "{\"locked\":\"yes\",\"by\":\"\",\"reason\":\"\",\"since\":0}",
            "{\"locked\":true,\"by\":\"\",\"reason\":\"\",\"since\":0,\"extra\":1}", "{\"locked\":true,\"locked\":true,\"by\":\"\",\"reason\":\"\",\"since\":0}", "" };
        for (String g : garbage) {
            File gf = new File(d, "bad.json");
            Files.write(gf.toPath(), g.getBytes(StandardCharsets.UTF_8));
            WriteLock gl = new WriteLock(gf, clk);
            gl.load();
            Check.ok(gl.isLocked(), "unreadable or odd lock file counts as LOCKED: '" + g + "'");
        }
        // a file that cannot be read at all (a directory in its place)
        File dirAsFile = new File(d, "dirlock");
        Check.ok(dirAsFile.mkdir(), "mkdir");
        WriteLock dl = new WriteLock(dirAsFile, clk);
        dl.load();
        Check.ok(dl.isLocked() && dl.info().contains("unreadable"), "unreadable lock path: locked");
        // a lock file that appears while running locks
        File late = new File(d, "late.json");
        WriteLock ll = new WriteLock(late, clk);
        ll.load();
        Check.ok(!ll.isLocked(), "unlocked first");
        Files.write(late.toPath(), "{\"locked\":true,\"by\":\"ops\",\"reason\":\"by hand\",\"since\":1}".getBytes(StandardCharsets.UTF_8));
        ll.load();
        Check.ok(ll.isLocked(), "a lock file dropped in by hand locks on the next check");
        // removing the file by hand does not unlock a held lock
        Files.delete(late.toPath());
        ll.load();
        Check.ok(ll.isLocked(), "deleting the file by hand does not release the lock (only unlock does)");
        // lock file not writable: still locked in memory
        File ro = new File(d, "nodir/inner/lock.json");
        File blocker = new File(d, "nodir");
        Files.write(blocker.toPath(), new byte[] { 1 });
        WriteLock rl = new WriteLock(ro, clk);
        String how = rl.lock("x", "y");
        Check.ok(rl.isLocked() && how.contains("memory only"), "unwritable lock file: locked in memory anyway");
    }

    static void audit() throws Exception {
        Check.FakeClock clk = new Check.FakeClock();
        File d = Check.tmpDir("audit");
        File f = new File(d, "agentcraft-write-audit.log");
        WriteAudit au = new WriteAudit(f, 8L * 1024 * 1024, clk);
        Check.ok(au.record("Owner/00000000", "request", "card.dispatch", "-", "-", "card=c1 board=main"), "record writes");
        String line = read(f).trim();
        Check.ok(line.matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ who=Owner/00000000 action=request where=card.dispatch before=- after=- detail=\"card=c1 board=main\""),
            "line format mirrors the card 6 audit: " + line);
        Check.eq(Files.getPosixFilePermissions(f.toPath()), PosixFilePermissions.fromString("rw-------"), "audit file is 0600");
        Check.eq(WriteAudit.utc(0), "1970-01-01T00:00:00Z", "UTC time stamp");
        Check.eq(WriteAudit.who("Owner", "123456789abcdef"), "Owner/12345678", "who is name/uuid8");
        Check.eq(WriteAudit.who(null, null), "console", "console has no uuid");

        au.record("evil\nname\r/x", "re\tquest\u0000", "sec\u00a7tion", "bef\u007fore", "af\u0085ter", "line1\nline2 \"quoted\" \u00a7c");
        String[] lines = read(f).split("\n");
        Check.eq(lines.length, 2, "control characters never split a line");
        Check.ok(!lines[1].contains("\r") && !lines[1].contains("\t") && !lines[1].contains("\u0000") && !lines[1].contains("\u00a7") && !lines[1].contains("\u007f"), "control characters and section signs flattened");
        Check.ok(lines[1].contains("detail=\"line1 line2 'quoted'  c\""), "quotes in detail become apostrophes: " + lines[1]);

        char[] big = new char[5000];
        java.util.Arrays.fill(big, 'x');
        au.record("w", "a", new String(big), "-", "-", new String(big));
        String last = read(f).trim().split("\n")[2];
        Check.ok(last.length() < 1000, "long fields are capped (" + last.length() + ")");
        Check.ok(last.contains("..."), "capped fields end in ...");
        Check.eq(au.recent(10).size(), 3, "recent keeps the lines in memory");
        Check.ok(au.writable(), "writable");

        // roll at the limit
        File f2 = new File(d, "roll.log");
        WriteAudit r = new WriteAudit(f2, 600, clk);
        for (int i = 0; i < 12; i++) r.record("w", "action" + i, "-", "-", "-", "padding padding padding");
        Check.ok(new File(d, "roll.log.1").isFile(), "rolls to .1 past the limit");
        Check.ok(f2.length() <= 600 + 300, "the live file stays near the limit (" + f2.length() + ")");
        Check.eq(Files.getPosixFilePermissions(new File(d, "roll.log").toPath()), PosixFilePermissions.fromString("rw-------"), "the new file after a roll is 0600 too");

        // a file that exists with loose permissions is tightened
        File f3 = new File(d, "loose.log");
        Files.write(f3.toPath(), new byte[0]);
        Files.setPosixFilePermissions(f3.toPath(), PosixFilePermissions.fromString("rw-r--r--"));
        new WriteAudit(f3, 1 << 20, clk).record("w", "a", "-", "-", "-", "-");
        Check.eq(Files.getPosixFilePermissions(f3.toPath()), PosixFilePermissions.fromString("rw-------"), "loose permissions are tightened to 0600");

        // unwritable
        File blocker = new File(d, "blocker");
        Files.write(blocker.toPath(), new byte[] { 1 });
        WriteAudit bad = new WriteAudit(new File(blocker, "audit.log"), 1 << 20, clk);
        Check.ok(!bad.record("w", "a", "-", "-", "-", "-"), "record returns false when the file can't be written");
        Check.ok(!bad.writable() && !bad.lastError().isEmpty(), "writable() false and the error is kept");
    }

    static void buckets() {
        Check.FakeClock clk = new Check.FakeClock();
        RateLimits.Bucket b = new RateLimits.Bucket(clk);
        Check.ok(b.tryTake("p") && b.tryTake("p") && b.tryTake("p"), "burst of 3");
        Check.ok(!b.tryTake("p"), "4th refused");
        Check.ok(b.tryTake("q"), "another player has their own bucket");
        clk.t += 1999;
        Check.ok(!b.tryTake("p"), "1999 ms: not yet");
        clk.t += 1;
        Check.ok(b.tryTake("p"), "2000 ms: one token");
        Check.ok(!b.tryTake("p"), "and only one");
        clk.t += 60_000;
        Check.ok(b.tryTake("p") && b.tryTake("p") && b.tryTake("p") && !b.tryTake("p"), "refill never exceeds the burst of 3");
        clk.t -= 100_000; // a clock step back must not mint tokens
        Check.ok(!b.tryTake("p"), "a clock step back gives no free tokens");
        for (int i = 0; i < 400; i++) b.tryTake("flood" + i);
        Check.ok(b.tryTake("late"), "the bucket table is bounded and still serves new players");

        RateLimits.Windows w = new RateLimits.Windows(clk);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("card", "c1");
        args.put("board", "main");
        args.put("profile", "p");
        Check.eq(w.tryAcquire("card.dispatch", args), null, "dispatch 1");
        Check.ok(w.tryAcquire("card.dispatch", args).contains("per card"), "same card within 10 min refused");
        clk.t += 10 * 60_000L - 1;
        Check.ok(w.tryAcquire("card.dispatch", args) != null, "9:59 later still refused");
        clk.t += 2;
        Check.eq(w.tryAcquire("card.dispatch", args), null, "10:00 later allowed");
        for (int i = 0; i < 4; i++) {
            Map<String, Object> o = new LinkedHashMap<>(args);
            o.put("card", "other" + i);
            Check.eq(w.tryAcquire("card.dispatch", o), null, "dispatch of another card " + i);
        }
        Map<String, Object> over = new LinkedHashMap<>(args);
        over.put("card", "x");
        Check.ok(w.tryAcquire("card.dispatch", over).contains("6 per 1 h"), "7th dispatch in the hour refused: " + w.tryAcquire("card.dispatch", over));
        // a refused request does not use up a slot
        RateLimits.Windows w2 = new RateLimits.Windows(clk);
        Map<String, Object> svc = new LinkedHashMap<>();
        svc.put("service", "s1");
        Check.eq(w2.tryAcquire("service.restart", svc), null, "restart s1");
        for (int i = 0; i < 30; i++) w2.tryAcquire("service.restart", svc);
        clk.t += 10 * 60_000L;
        Check.eq(w2.tryAcquire("service.restart", svc), null, "refused attempts did not extend the window");
        Check.ok(w.tryAcquire("card.dispatch", new LinkedHashMap<String, Object>()) != null, "missing scope key is refused");
        List<String> none = null;
        Check.ok(none == null, "(placeholder)");
    }
}
