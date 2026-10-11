"""The real process: `python3 -m hermes_control serve` (dry run, fixture board), SIGHUP reload, SIGTERM."""

from __future__ import annotations

import os
import signal
import subprocess
import sys
import threading
import time
import unittest
from pathlib import Path

from test_control_common import ACTOR, FIXTURE, Env, base_policy, control_client

ADAPTER = Path(__file__).resolve().parent.parent


class ServeProcessTest(unittest.TestCase):
    def setUp(self):
        self.env = Env()
        self.addCleanup(self.env.close)
        pol = base_policy(self.env)
        self.env.write_policy(pol)
        argv = [sys.executable, "-m", "hermes_control", "serve", "--policy", str(self.env.policy_path), "--key-file", str(self.env.key_path),
                "--state-dir", str(self.env.state), "--dry-run", "--board-fixture", str(FIXTURE), "--port", "0"]
        self.proc = subprocess.Popen(argv, cwd=ADAPTER, env={**os.environ, "PYTHONPATH": str(ADAPTER)}, stderr=subprocess.PIPE, text=True)
        self.addCleanup(self.stop)
        self.lines: list[str] = []
        self.port = None
        ready = threading.Event()

        def pump():
            for line in self.proc.stderr:
                self.lines.append(line)
                if "listening on ws://" in line and not ready.is_set():
                    self.port = int(line.split("ws://127.0.0.1:")[1].split("/")[0])
                    ready.set()

        self.pump = threading.Thread(target=pump, daemon=True)
        self.pump.start()
        self.assertTrue(ready.wait(15), "".join(self.lines))

    def stop(self):
        if self.proc.poll() is None:
            self.proc.send_signal(signal.SIGTERM)
            try:
                self.proc.wait(10)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait(5)
        self.pump.join(5)
        self.proc.stderr.close()

    def client(self):
        c = control_client.ControlClient("127.0.0.1", self.port, self.env.key, ACTOR, "ProcPlayer", quiet=True)
        self.addCleanup(c.ws.sock.close)
        return c

    def test_dry_run_round_trip_sighup_and_sigterm(self):
        c = self.client()
        got = c.handshake()
        self.assertTrue(got[2][1]["dryRun"])
        self.assertTrue(got[2][1]["revision"].endswith(".0"))
        rid, prompt, res = self.env.prompt(c, {"card": "t-demo-1", "board": "main", "profile": "builder-a"})
        self.assertEqual(res["status"], "prompted")
        c.send("action.confirm", {"actor": c.actor, "token": prompt["token"]})
        done = self.env.result_for(c, rid)
        self.assertEqual((done["status"], done["dryRun"]), ("applied", True))
        self.assertEqual(self.env.hermes_calls(), [])
        # SIGHUP: policy re-read, revision bumped, clients get the new action.policy
        pol = base_policy(self.env)
        pol["capabilities"]["cron.run"]["enabled"] = False
        self.env.write_policy(pol)
        self.proc.send_signal(signal.SIGHUP)
        end, seen = time.time() + 6, None
        while time.time() < end and seen is None:
            t, p = c.recv(2)
            if t == "action.policy":
                seen = p
        self.assertTrue(seen["revision"].endswith(".1"))
        self.assertFalse(seen["capabilities"]["cron.run"]["enabled"])
        # an invalid policy on SIGHUP is ignored; the process keeps serving the old one
        bad = base_policy(self.env)
        bad["capabilities"]["rm.rf"] = {"enabled": True}
        self.env.write_policy(bad)
        self.proc.send_signal(signal.SIGHUP)
        time.sleep(0.5)
        self.assertIsNone(self.proc.poll())
        r = self.env.ask(c, "card.create", {"board": "main", "title": "still serving"})
        self.assertEqual(r["status"], "applied")
        # the audit log sits next to the policy file, owner-only
        audit = self.env.cfg / "hermes-control-audit.jsonl"
        self.assertTrue(audit.exists())
        self.assertEqual(oct(audit.stat().st_mode & 0o777), "0o600")
        self.proc.send_signal(signal.SIGTERM)
        self.assertEqual(self.proc.wait(10), 0)

    def test_second_process_on_a_bad_policy_refuses_to_start(self):
        bad = base_policy(self.env)
        bad["capabilities"]["decision.answer"]["permissionApprove"] = True
        self.env.write_policy(bad)
        out = subprocess.run([sys.executable, "-m", "hermes_control", "serve", "--policy", str(self.env.policy_path), "--key-file", str(self.env.key_path),
                              "--state-dir", str(self.env.state), "--dry-run", "--port", "0"], cwd=ADAPTER, env={**os.environ, "PYTHONPATH": str(ADAPTER)},
                             capture_output=True, text=True, timeout=30)
        self.assertEqual(out.returncode, 2)
        self.assertIn("permissionApprove", out.stderr + out.stdout)
        self.assertIn("not supported", out.stderr + out.stdout)


if __name__ == "__main__":
    unittest.main()
