"""world.* over the WebSocket server: opt-in, ordering, broadcast only to opted-in clients, action.* refused."""

import tempfile
import time
import unittest
from pathlib import Path

from hermes_adapter.mapping import Mapper
from hermes_adapter.server import AdapterServer
from hermes_adapter.sources import HermesSource
from hermes_adapter.world_hub import WorldHub
from hermes_adapter.wsclient import WSClient

from factory_fixture import capture, machine
from fixture import standard
from test_server import ServerThread


class SteppedSource:
    """Serves a scripted list of captures, one per collect (the last one repeats)."""

    id = "factory"
    name = "scripted"
    interval = 0.2

    def __init__(self, captures):
        self.captures = list(captures)
        self.calls = 0

    def collect(self):
        c = self.captures[min(self.calls, len(self.captures) - 1)]
        self.calls += 1
        return {"health": {"protocol": "ai-factory/v2", "captureSequence": c["captureSequence"],
                           "lastCaptureMillis": c["capturedAtMillis"], "nowMillis": c["capturedAtMillis"] + 500},
                "capture": c}


class WorldServerTest(unittest.TestCase):
    def start(self, hub):
        self.tmp = tempfile.TemporaryDirectory()
        f = standard(Path(self.tmp.name), int(time.time()))
        self.srv = AdapterServer(HermesSource(f.home).read, Mapper(), host="127.0.0.1", port=0, poll_interval=0.5,
                                 ping_interval=5, world=hub, world_tick=0.05)
        self.st = ServerThread(self.srv)

    def tearDown(self):
        self.st.stop()
        self.tmp.cleanup()

    def client(self):
        return WSClient("127.0.0.1", self.srv.port)

    def recv_types(self, c, n, timeout=5.0):
        out = []
        end = time.time() + timeout
        while len(out) < n and time.time() < end:
            out.append(c.recv(timeout=max(0.1, end - time.time())))
        return out

    def wait_for(self, c, pred, timeout=5.0):
        end = time.time() + timeout
        while time.time() < end:
            m = c.recv(timeout=max(0.1, end - time.time()))
            if pred(m):
                return m
        raise AssertionError("timed out")

    def drain(self, c, seconds):
        """Every message that arrives within ``seconds`` (stops at the first quiet read timeout)."""
        out = []
        end = time.time() + seconds
        while time.time() < end:
            try:
                out.append(c.recv(timeout=max(0.1, end - time.time())))
            except OSError:  # read timeout: nothing more arrived
                break
        return out

    def test_opt_in_order_and_ack_result(self):
        self.start(WorldHub(None))
        c = self.client()
        c.send({"type": "hello", "id": "h1", "protocol": 1, "client": "test", "features": ["world", "nope", 7]})
        msgs = self.recv_types(c, 3)
        self.assertEqual([m["type"] for m in msgs], ["snapshot", "world.snapshot", "ack"])
        self.assertEqual(msgs[1]["source"]["state"], "off")
        self.assertEqual(msgs[2]["result"], {"features": ["world"]})
        c.close()

    def test_plain_hello_is_byte_compatible_and_gets_no_world(self):
        hub = WorldHub(SteppedSource([capture(seq=1), capture([machine(1, "idle")], seq=2)]))
        self.start(hub)
        c = self.client()
        c.send({"type": "hello", "id": "h1", "protocol": 1, "client": "upstream"})
        msgs = self.recv_types(c, 2)
        self.assertEqual([m["type"] for m in msgs], ["snapshot", "ack"])
        self.assertNotIn("result", msgs[1])
        later = self.drain(c, 1.5)  # several world polls happen meanwhile
        self.assertGreaterEqual(hub.polls, 2)
        self.assertEqual([m["type"] for m in later if m["type"].startswith("world.")], [])
        c.close()

    def test_world_messages_stream_to_opted_in_clients_only(self):
        caps = [capture(seq=1), capture(seq=1), capture([machine(1, "idle"), machine(2, "idle"), machine(3, "maintenance")], seq=2)]
        src = SteppedSource(caps)
        src.interval = 0.6
        hub = WorldHub(src)
        self.start(hub)
        a, b = self.client(), self.client()
        a.send({"type": "hello", "id": "a", "protocol": 1, "features": ["world"]})
        b.send({"type": "hello", "id": "b", "protocol": 1})
        ev = self.wait_for(a, lambda m: m["type"] == "world.event", timeout=8)
        self.assertEqual(ev["event"]["kind"], "machine.stopped")
        self.assertEqual(ev["v"], 1)
        self.assertEqual([m["type"] for m in self.drain(b, 0.8) if m["type"].startswith("world.")], [])
        # a new hello without the feature ends the stream for that connection
        a.send({"type": "hello", "id": "a2", "protocol": 1})
        self.wait_for(a, lambda m: m["type"] == "ack" and m["re"] == "a2")
        self.assertEqual(len(self.srv.world_clients), 0)
        a.close()
        b.close()

    def test_action_messages_are_refused(self):
        self.start(WorldHub(None))
        c = self.client()
        c.send({"type": "hello", "id": "h", "protocol": 1})
        self.recv_types(c, 2)
        c.send({"type": "action.craft", "id": "x1", "item": "anything"})
        ack = self.wait_for(c, lambda m: m["type"] == "ack")
        self.assertFalse(ack["ok"])
        self.assertIn("read-only", ack["error"])
        c.send({"type": "action." + "z" * 5000, "id": "x2"})
        ack = self.wait_for(c, lambda m: m["type"] == "ack" and m["re"] == "x2")
        self.assertLess(len(ack["error"]), 200, "a huge type is not echoed back")
        c.close()


if __name__ == "__main__":
    unittest.main()
