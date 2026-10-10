"""Dashboard boundary and lifecycle tests; stdlib only, synthetic state and sockets."""
import asyncio
import copy
import ipaddress
import json
import logging
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, Mock, patch

from hermes_adapter import __main__ as cli
from hermes_adapter import dashboard as http
from hermes_adapter import redact
from hermes_adapter.mapping import Mapper
from hermes_adapter.server import AdapterServer
from hermes_adapter.sources import HermesSource
from hermes_adapter.wsclient import WSClient

from fixture import standard


class MappedAdapter:
    """No actual source; touching read/world is an immediate test failure."""
    def __init__(self, *args, **kwargs):
        self.polls = 1
        self.poll_interval = 3.0
        self.last_error = None
        self.ops_error = None
        self.started = False
        self.stopped = False
        self.read = Mock(side_effect=AssertionError("HTTP must not read a source"))
        self.world = Mock()
        self.world.snapshot_message.side_effect = AssertionError("world is not a dashboard route")
        self.state = {
            "type": "snapshot", "foreman": {"readOnly": True, "message": "synthetic demo"},
            "agents": [{"id": "demo-agent", "name": "Demo agent", "status": "idle"}],
            "tasks": [], "decisions": [], "repos": [], "memory": [], "goals": [],
            "feed": [], "logs": [],
        }
        self.ops_state = {
            "type": "ops.snapshot", "services": [], "jobs": [], "usage": [], "alerts": [],
            "sources": [], "limits": {}, "ts": 1700000000000,
        }

    def snapshot(self):
        return copy.deepcopy(self.state)

    def ops_snapshot(self):
        return copy.deepcopy(self.ops_state)

    async def start(self):
        self.started = True
        return 0

    async def stop(self):
        self.stopped = True


async def request(server, target="/api/snapshot", method="GET", headers=None, raw=None):
    reader, writer = await asyncio.open_connection(server.host, server.port)
    if raw is None:
        if headers is None:
            host = f"[{server.host}]" if ":" in server.host else server.host
            headers = [("Host", f"{host}:{server.port}")]
        raw = (f"{method} {target} HTTP/1.1\r\n" +
               "".join(f"{key}: {value}\r\n" for key, value in headers) + "\r\n").encode("ascii")
    writer.write(raw)
    await writer.drain()
    try:
        data = await asyncio.wait_for(reader.read(), 5)
    finally:
        writer.close()
        await writer.wait_closed()
    head, body = data.split(b"\r\n\r\n", 1)
    lines = head.decode("ascii").split("\r\n")
    response_headers = dict(line.lower().split(": ", 1) for line in lines[1:])
    return int(lines[0].split(" ")[1]), response_headers, body


class BindTest(unittest.TestCase):
    def test_numeric_specific_bind_and_lan_acknowledgement(self):
        for host in ("127.0.0.1", "127.0.0.2", "::1", "::ffff:127.0.0.1"):
            self.assertEqual(http.validate_bind(host, 0), str(ipaddress.ip_address(host)))
        for host in ("localhost", "example.test", "", "0.0.0.0", "::", "224.0.0.1", "ff02::1",
                     "255.255.255.255", "::1%lo", "192.0.2.10:8787", "[::1]"):
            for allow_lan in (True, False):
                with self.subTest(host=host, allow_lan=allow_lan), self.assertRaises(http.DashboardError):
                    http.validate_bind(host, 8787, allow_lan)
        for host in ("192.0.2.10", "2001:db8::10", "::ffff:192.0.2.10"):
            with self.assertRaisesRegex(http.DashboardError, "--dashboard-allow-lan"):
                http.validate_bind(host, 8787)
            self.assertTrue(http.validate_bind(host, 8787, True))
        for port in (-1, 65536, True, 8787.0):
            with self.assertRaises(http.DashboardError):
                http.validate_bind("127.0.0.1", port)

    def test_cli_defaults_and_independent_flags(self):
        args = cli.parse_args([])
        self.assertFalse(args.dashboard)
        self.assertEqual((args.dashboard_bind, args.dashboard_port, args.dashboard_allow_lan),
                         ("127.0.0.1", 8787, False))
        args = cli.parse_args(["--dashboard", "--dashboard-bind", "192.0.2.10", "--dashboard-port", "8989",
                               "--dashboard-allow-lan"])
        self.assertEqual((args.bind, args.port), ("127.0.0.1", 7878))
        self.assertEqual((args.dashboard_bind, args.dashboard_port), ("192.0.2.10", 8989))

    def test_cli_invalid_dashboard_fails_before_source_initialization(self):
        with patch.object(cli, "build_ops") as build:
            with self.assertRaises(SystemExit) as result:
                cli.main(["--dashboard", "--dashboard-bind", "192.0.2.10"])
        build.assert_not_called()
        self.assertIn("--dashboard-allow-lan", str(result.exception))


class DashboardTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.adapter = MappedAdapter()
        self.tmp = tempfile.TemporaryDirectory()
        self.static = Path(self.tmp.name)
        self.content = {
            "/": b"<!doctype html><script src='/dashboard.js'></script>",
            "/dashboard.css": b"body { color: #fff; }",
            "/dashboard.js": b"'use strict'; fetch('/api/snapshot');",
        }
        for route, data in self.content.items():
            (self.static / http.ASSETS[route][0]).write_bytes(data)
        self.assets_patch = patch.object(http, "STATIC_ROOT", self.static)
        self.assets_patch.start()
        self.server = http.DashboardServer(self.adapter, port=0, request_timeout=0.15, output_timeout=0.15)
        await self.server.start()

    async def asyncTearDown(self):
        await self.server.stop()
        self.assets_patch.stop()
        self.tmp.cleanup()

    def authority(self):
        return f"127.0.0.1:{self.server.port}"

    async def test_exact_asset_routes_security_headers_and_existing_fonts(self):
        for route, (name, mime, font) in http.ASSETS.items():
            with self.subTest(route=route):
                status, headers, body = await request(self.server, route)
                self.assertEqual(status, 200)
                self.assertEqual(headers["content-type"], mime)
                expected = (http.FONT_ROOT / name).read_bytes() if font else self.content[route]
                self.assertEqual(body, expected)
                self.assertEqual(int(headers["content-length"]), len(body))
                self.assertEqual(headers["cache-control"], "no-store")
                self.assertEqual(headers["x-content-type-options"], "nosniff")
                self.assertEqual(headers["x-frame-options"], "deny")
                self.assertIn("frame-ancestors 'none'", headers["content-security-policy"])
                for directive in ("script-src 'self'", "style-src 'self'", "font-src 'self'", "connect-src 'self'"):
                    self.assertIn(directive, headers["content-security-policy"])
                self.assertNotIn("unsafe-inline", headers["content-security-policy"])
                self.assertNotIn("access-control-allow-origin", headers)

    async def test_snapshot_contract_mapped_data_only_and_no_request_reads(self):
        for _ in range(3):
            status, headers, body = await request(self.server)
            self.assertEqual(status, 200)
            self.assertTrue(headers["content-type"].startswith("application/json"))
            payload = json.loads(body)
            self.assertEqual(set(payload), {"snapshot", "ops", "meta"})
            self.assertEqual(payload["snapshot"], self.adapter.state)
            self.assertEqual(payload["ops"], self.adapter.ops_state)
            meta = payload["meta"]
            self.assertEqual(set(meta), {"generatedAt", "updatedAt", "polls", "stale", "readOnly"})
            self.assertEqual(meta["polls"], 1)
            self.assertTrue(meta["readOnly"])
            self.assertFalse(meta["stale"])
            self.assertGreater(meta["updatedAt"], 0)
            self.assertGreaterEqual(meta["generatedAt"], meta["updatedAt"])
        self.adapter.read.assert_not_called()
        self.adapter.world.snapshot_message.assert_not_called()

    async def test_assets_are_cached_not_read_per_request(self):
        (self.static / "dashboard.js").write_bytes(b"SyntheticChangedAfterStartup")
        with patch.object(Path, "open", side_effect=AssertionError("no request-time file reads")):
            status, _, body = await request(self.server, "/dashboard.js")
            self.assertEqual(status, 200)
            self.assertEqual(body, self.content["/dashboard.js"])
            self.assertEqual((await request(self.server))[0], 200)

    async def test_no_successful_poll_is_stale_and_redaction_does_not_mutate_model(self):
        self.adapter.polls = 0
        other = http.DashboardServer(self.adapter, port=0)
        try:
            await other.start()
            self.adapter.state["tasks"] = [{"id": "password=SyntheticMutableCanary9"}]
            original = copy.deepcopy(self.adapter.state)
            status, _, body = await request(other)
            self.assertEqual(status, 200)
            meta = json.loads(body)["meta"]
            self.assertEqual((meta["polls"], meta["updatedAt"], meta["stale"]), (0, 0, True))
            self.assertEqual(self.adapter.state, original)
            self.assertNotIn(b"SyntheticMutableCanary9", body)
        finally:
            await other.stop()

    async def test_poll_metadata_success_failure_and_age(self):
        before = json.loads((await request(self.server))[2])["meta"]
        self.adapter.last_error = "password=SyntheticErrorCanary"
        failed = json.loads((await request(self.server))[2])["meta"]
        self.assertTrue(failed["stale"])
        self.assertEqual(failed["updatedAt"], before["updatedAt"])
        await asyncio.sleep(0.06)
        self.adapter.polls += 1
        self.adapter.last_error = None
        await asyncio.sleep(0.06)
        after = json.loads((await request(self.server))[2])["meta"]
        self.assertEqual(after["polls"], 2)
        self.assertGreater(after["updatedAt"], before["updatedAt"])
        self.assertFalse(after["stale"])
        self.server._observed_at = time.monotonic() - 100
        self.assertTrue(json.loads((await request(self.server))[2])["meta"]["stale"])
        self.adapter.ops_error = "SyntheticOpsErrorCanary"
        self.assertNotIn(b"SyntheticOpsErrorCanary", (await request(self.server))[2])

    async def test_privacy_applies_to_every_dynamic_string_and_identifier(self):
        token = "sk-test-" + "Ab9" * 12
        secret = "SyntheticPasswordCanary9"
        personal = "SyntheticPersonalCanary"
        self.adapter.state["agents"][0].update({
            "id": token, "name": f"password={secret}",
            "activity": "endpoint 192.0.2.42 [2001:db8::42] demo@example.test",
            "taskId": "/synthetic/private/state/task.txt",
        })
        self.adapter.state["tasks"] = [{"id": f"--api-key {secret}", "board": token,
                                        "title": personal + "\nSource: personal-synthetic.md"}]
        self.adapter.state["memory"] = [{"id": "/synthetic/private/.env", "body": f"Bearer {secret}"}]
        self.adapter.ops_state["services"] = [{"id": token, "name": f"password={secret}",
                                               "detail": "192.0.2.42"}]
        # A future adapter extension cannot add a raw/world endpoint accidentally.
        self.adapter.state["world"] = {"players": [personal]}
        self.adapter.state["raw"] = secret
        self.adapter.ops_state["plugin_config"] = secret
        with redact.ip_policy(True):
            status, _, body = await request(self.server)
        self.assertEqual(status, 200)
        text = body.decode()
        for canary in (token, secret, personal, "192.0.2.42", "2001:db8::42", "demo@example.test",
                       "/synthetic/private"):
            self.assertNotIn(canary, text)
        for marker in ("[redacted]", "[withheld: mentions personal notes]", "[ip]", "[email]", "[path]"):
            self.assertIn(marker, text)
        payload = json.loads(body)
        self.assertNotIn("world", payload["snapshot"])
        self.assertNotIn("raw", payload["snapshot"])
        self.assertNotIn("plugin_config", payload["ops"])

    async def test_post_put_patch_delete_head_options_and_unknown_methods_are_closed(self):
        for method in ("POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE", "CONNECT", "BREW", "get"):
            with self.subTest(method=method):
                status, headers, body = await request(self.server, method=method)
                self.assertEqual(status, 405)
                self.assertEqual(headers["allow"], "get")
                self.assertEqual(headers["cache-control"], "no-store")
                self.assertNotIn("access-control-allow-origin", headers)
                if method == "HEAD":
                    self.assertEqual(body, b"")
        self.adapter.read.assert_not_called()

    async def test_traversal_and_all_unknown_paths_fail_closed(self):
        for path in ("/../dashboard.py", "/%2e%2e/dashboard.py", "/fonts/../dashboard.py", "//dashboard.js",
                     "/dashboard.js?x=1", "/api/snapshot?world=1", "/dashboard.js/", "/index.html",
                     "/fonts/Nunito-ExtraBold.ttf", "/api/world", "/world", "/raw", "/files",
                     "http://example.test/api/snapshot", "/dashboard%2ejs", "/dashboard.js#frag"):
            with self.subTest(path=path):
                status, _, body = await request(self.server, path)
                self.assertEqual(status, 404)
                self.assertNotIn(path.encode(), body)

    async def test_host_exact_authority_no_dns_rebinding_or_wrong_port(self):
        for host in ("example.test", f"example.test:{self.server.port}", "127.0.0.1",
                     "127.0.0.1:1", f"127.0.0.2:{self.server.port}",
                     self.authority() + ".example.test", self.authority() + "@example.test", "[::1]:1", ""):
            with self.subTest(host=host):
                self.assertEqual((await request(self.server, headers=[("Host", host)]))[0], 403)
        self.assertEqual((await request(self.server, headers=[]))[0], 403)
        self.assertEqual((await request(self.server, headers=[("Host", f"localhost:{self.server.port}")]))[0], 200)

    async def test_origins_same_authority_only_no_cors(self):
        for origin in ("null", "", "http://example.test", "https://" + self.authority(),
                       "http://127.0.0.1:1", "http://" + self.authority() + "/",
                       "http://" + self.authority() + "@example.test", f"http://localhost:{self.server.port}"):
            with self.subTest(origin=origin):
                status, headers, _ = await request(self.server, headers=[("Host", self.authority()), ("Origin", origin)])
                self.assertEqual(status, 403)
                self.assertNotIn("access-control-allow-origin", headers)
        headers = [("Host", self.authority()), ("Origin", "http://" + self.authority())]
        self.assertEqual((await request(self.server, headers=headers))[0], 200)
        for site in ("cross-site", "same-site"):
            self.assertEqual((await request(self.server, headers=headers + [("Sec-Fetch-Site", site)]))[0], 403)

    async def test_invalid_headers_bodies_and_request_smuggling_shapes(self):
        prefix = f"GET /api/snapshot HTTP/1.1\r\nHost: {self.authority()}\r\n"
        for extra in ("Host: example.test\r\n", " Origin: http://example.test\r\n", "broken\r\n",
                      "Content-Length: 1\r\n", "Content-Length: -1\r\n", "Content-Length: 00\r\n",
                      "Transfer-Encoding: chunked\r\n", "Upgrade: websocket\r\n", "X-Value: x\x00y\r\n",
                      "Origin: http://example.test\r\nOrigin: http://example.test\r\n"):
            with self.subTest(extra=extra):
                self.assertEqual((await request(self.server, raw=(prefix + extra + "\r\n").encode()))[0], 400)
        for raw in (b"GET / HTTP/1.0\r\n\r\n", b"GET  / HTTP/1.1\r\n\r\n", b"GET / HTTP/1.1\r\nX: \xff\r\n\r\n"):
            self.assertEqual((await request(self.server, raw=raw))[0], 400)

    async def test_bounded_headers_and_request_deadline(self):
        huge = f"GET / HTTP/1.1\r\nHost: {self.authority()}\r\nX: ".encode() + b"x" * http.MAX_HEADERS + b"\r\n\r\n"
        self.assertEqual((await request(self.server, raw=huge))[0], 431)
        many = [("Host", self.authority())] + [(f"X-{n}", "v") for n in range(http.MAX_HEADER_COUNT)]
        self.assertEqual((await request(self.server, headers=many))[0], 431)
        self.assertEqual((await request(self.server, raw=b"GET / HTTP/1.1\r\n"))[0], 408)

    async def test_response_limits_and_internal_errors_never_escape(self):
        self.server.max_response = 1024
        self.adapter.state["feed"] = [{"text": "x" * 3000}]
        status, _, body = await request(self.server)
        self.assertEqual((status, body), (503, b"Service Unavailable\n"))
        self.adapter.snapshot = Mock(side_effect=RuntimeError("SyntheticInternalSecretCanary"))
        with patch.object(logging.getLogger("asyncio"), "error") as error_log:
            status, _, body = await request(self.server)
        self.assertEqual(status, 503)
        self.assertNotIn(b"SyntheticInternalSecretCanary", body)
        error_log.assert_not_called()

    async def test_missing_assets_no_fallback_or_symlink_escape(self):
        outside = self.static / "nested"
        outside.mkdir()
        (outside / "secret.js").write_bytes(b"SyntheticPrivateAssetCanary")
        (self.static / "dashboard.js").unlink()
        (self.static / "dashboard.js").symlink_to(outside / "secret.js")
        await self.server.stop()
        await self.server.start()
        status, _, body = await request(self.server, "/dashboard.js")
        self.assertEqual(status, 503)
        self.assertNotIn(b"SyntheticPrivateAssetCanary", body)

    async def test_client_admission_and_shutdown_with_partial_requests(self):
        self.server.max_clients = 1
        reader, writer = await asyncio.open_connection(self.server.host, self.server.port)
        writer.write(b"GET / HTTP/1.1\r\n")
        await writer.drain()
        await asyncio.sleep(0.01)
        second_reader, second_writer = await asyncio.open_connection(self.server.host, self.server.port)
        try:
            try:
                self.assertEqual(await asyncio.wait_for(second_reader.read(), 1), b"")
            except ConnectionResetError:
                pass
            self.assertEqual(len(self.server._clients), 1)
            port = self.server.port
            await asyncio.wait_for(self.server.stop(), 1)
            self.assertEqual(await asyncio.wait_for(reader.read(), 1), b"")
            self.assertFalse(self.server._clients)
            self.assertIsNone(self.server._observer)
            await self.server.stop()
            with self.assertRaises(OSError):
                await asyncio.open_connection("127.0.0.1", port)
        finally:
            writer.close()
            second_writer.close()
            await writer.wait_closed()
            try:
                await second_writer.wait_closed()
            except ConnectionResetError:
                pass

    async def test_output_deadline(self):
        writer = Mock()
        drained = asyncio.Event()
        async def hang():
            await drained.wait()
        writer.drain = hang
        with self.assertRaises(asyncio.TimeoutError):
            await self.server._send(writer, 200, b"synthetic", "text/plain")
        self.assertEqual(writer.write.call_count, 2)

    async def test_ipv6_loopback(self):
        other = http.DashboardServer(self.adapter, host="::1", port=0)
        try:
            try:
                await other.start()
            except http.DashboardError:
                self.skipTest("IPv6 loopback listener is unavailable")
            self.assertIn(f"[::1]:{other.port}", other.authorities)
            self.assertEqual((await request(other))[0], 200)
            self.assertEqual((await request(other, headers=[("Host", f"::1:{other.port}")]))[0], 403)
        finally:
            await other.stop()

    async def test_start_failure_has_constant_diagnostic_and_no_leaked_listener(self):
        other = http.DashboardServer(self.adapter, port=self.server.port)
        with self.assertRaisesRegex(http.DashboardError, "dashboard listener could not start"):
            await other.start()
        self.assertIsNone(other._server)
        self.assertIsNone(other._observer)
        await other.stop()

    async def test_cancelled_start_closes_partial_listener_and_observer(self):
        other = http.DashboardServer(self.adapter, port=0)
        partial = Mock()
        partial.sockets = [Mock()]
        partial.sockets[0].getsockname.return_value = ("127.0.0.1", 8989)
        entered = asyncio.Event()
        never = asyncio.Event()
        async def start_serving():
            entered.set()
            await never.wait()
        partial.start_serving = start_serving
        partial.wait_closed = AsyncMock()
        with patch.object(http.asyncio, "start_server", new=AsyncMock(return_value=partial)):
            task = asyncio.create_task(other.start())
            await entered.wait()
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task
        partial.close.assert_called_once()
        partial.wait_closed.assert_awaited_once()
        self.assertIsNone(other._server)
        self.assertIsNone(other._observer)

    async def test_websocket_contract_unchanged_with_dashboard_enabled(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = standard(Path(directory), int(time.time()))
            source = HermesSource(fixture.home)
            adapter = AdapterServer(source.read, Mapper(), port=0, poll_interval=60)
            dashboard = http.DashboardServer(adapter, port=0)
            try:
                await adapter.start()
                await dashboard.start()
                status, _, body = await request(dashboard)
                self.assertEqual(status, 200)
                self.assertEqual(json.loads(body)["snapshot"]["type"], "snapshot")
                def websocket_exchange():
                    client = WSClient("127.0.0.1", adapter.port)
                    try:
                        client.send({"type": "hello", "protocol": 1})
                        snapshot = client.recv()
                        client.send({"type": "task.action", "id": "synthetic-intent"})
                        return snapshot, client.recv()
                    finally:
                        client.close()
                snapshot, ack = await asyncio.to_thread(websocket_exchange)
                self.assertEqual((snapshot["v"], snapshot["type"]), (1, "snapshot"))
                self.assertEqual(ack["type"], "ack")
                self.assertFalse(ack["ok"])
            finally:
                await dashboard.stop()
                await adapter.stop()


class CliLifecycleTest(unittest.TestCase):
    def run_cli(self, enable=True, fail_dashboard=False):
        real_run = asyncio.run
        instances, dashboards, events, result = [], [], [], []
        class FakeServer(MappedAdapter):
            def __init__(self, *args, **kwargs):
                super().__init__()
                instances.append(self)
            async def start(self):
                events.append("adapter-start")
                await super().start()
            async def stop(self):
                events.append("adapter-stop")
                await super().stop()
        class ObservedDashboard(http.DashboardServer):
            def __init__(self, *args, **kwargs):
                super().__init__(*args, **kwargs)
                dashboards.append(self)
            async def start(self):
                events.append("dashboard-start")
                if fail_dashboard:
                    raise http.DashboardError("dashboard listener could not start")
                port = await super().start()
                result.append(await request(self))
                return port
            async def stop(self):
                events.append("dashboard-stop")
                await super().stop()
        def run_with_signal(coro):
            async def wrapped():
                loop = asyncio.get_running_loop()
                def register(sig, callback):
                    loop.call_soon(callback)
                with patch.object(loop, "add_signal_handler", side_effect=register), \
                     patch.object(loop, "remove_signal_handler"):
                    await coro
            return real_run(wrapped())
        with tempfile.TemporaryDirectory() as directory:
            argv = ["--hermes-home", directory, "--port", "0"]
            if enable:
                argv += ["--dashboard", "--dashboard-port", "0"]
            with patch.object(cli, "AdapterServer", FakeServer), \
                 patch.object(cli, "DashboardServer", ObservedDashboard), \
                 patch.object(cli.asyncio, "run", side_effect=run_with_signal):
                if fail_dashboard:
                    with self.assertRaisesRegex(SystemExit, "dashboard listener could not start"):
                        cli.main(argv)
                else:
                    self.assertEqual(cli.main(argv), 0)
        return instances, dashboards, events, result

    def test_cli_starts_separate_listener_then_stops_both(self):
        instances, dashboards, events, result = self.run_cli()
        self.assertEqual(events, ["adapter-start", "dashboard-start", "dashboard-stop", "adapter-stop"])
        self.assertEqual(result[0][0], 200)
        self.assertTrue(instances[0].started and instances[0].stopped)
        self.assertIsNone(dashboards[0]._server)
        self.assertFalse(dashboards[0]._clients)

    def test_dashboard_disabled_preserves_adapter_only_lifecycle(self):
        instances, dashboards, events, _ = self.run_cli(enable=False)
        self.assertEqual(events, ["adapter-start", "adapter-stop"])
        self.assertEqual(dashboards, [])
        self.assertTrue(instances[0].stopped)

    def test_dashboard_startup_failure_stops_adapter(self):
        instances, dashboards, events, _ = self.run_cli(fail_dashboard=True)
        self.assertEqual(events, ["adapter-start", "dashboard-start", "dashboard-stop", "adapter-stop"])
        self.assertTrue(instances[0].stopped)
        self.assertIsNone(dashboards[0]._server)


if __name__ == "__main__":
    unittest.main()
