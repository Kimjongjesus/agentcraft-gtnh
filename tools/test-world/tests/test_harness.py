"""Synthetic-only tests. No Minecraft, remote host, or real world is accessed."""
import argparse
import contextlib
import errno
import importlib.util
import io
import json
import os
from pathlib import Path
import socket
import stat
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location('test_world_harness', Path(__file__).parents[1] / 'harness.py')
h = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(h)

# Symbolic fixture only: not an enrolled/resolved account or a credential.
IDENTITY = {'uuid': '12345678-1234-4234-8234-123456789abc', 'name': 'SyntheticTester'}

# This executable is intentionally NOT Java or Minecraft. It exercises argv,
# current-run stdout, a loopback socket, and a graceful stdin stop/save protocol.
FAKE = r'''#!INTERPRETER
import json, pathlib, socket, sys, time
p = pathlib.Path('.')
props = dict(line.split('=', 1) for line in (p / 'server.properties').read_text().splitlines())
(p / 'received-argv.json').write_text(json.dumps(sys.argv[1:]))
mode = (p / 'fake-mode').read_text() if (p / 'fake-mode').exists() else 'ready'
if mode == 'exit':
    sys.exit(7)
s = None
if mode != 'no-port':
    s = socket.socket()
    s.bind(('127.0.0.1', int(props['server-port'])))
    s.listen()
if mode in ('flood', 'flood-crash'):
    print('x' * 200000, flush=True)
    print('synthetic flood end marker', flush=True)
if mode not in ('no-done', 'silent'):
    print('[Server thread/INFO]: Done (0.125s)! For help, type "help"', flush=True)
if mode in ('crash0', 'flood-crash'):
    time.sleep(0.5)
    if mode == 'flood-crash':
        print('y' * 200000, flush=True)
    print('synthetic crash end marker', flush=True)
    sys.exit(0 if mode == 'crash0' else 7)
for line in sys.stdin:
    if line.strip() == 'stop':
        if mode == 'slow-stop':
            time.sleep(0.8)
        (p / 'saved-by-fake').write_bytes(b'graceful synthetic save')
        print('synthetic stopped', flush=True)
        break
if s:
    s.close()
'''


class HarnessTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='test-world-synthetic-', dir=os.environ['TMPDIR'])
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.source = self.base / 'disposable-source'
        self.source.mkdir()
        self.root = self.base / 'managed'
        for rel, content in {
            'world/region/r.0.0.mca': b'\x00synthetic region\xff',
            'world/DIM-1/region/r.0.0.mca': b'synthetic nether',
            'world/DIM1/region/r.0.0.mca': b'synthetic end',
            'world/DIM42/machines.dat': b'synthetic machine contents=37',
            'config/gregtech.cfg': b'synthetic config',
            'mods/example.jar': b'not an actual jar',
            'server.jar': b'not an actual server',
            'whitelist.json': json.dumps([IDENTITY]).encode(),
            'ops.json': b'[]',
            'eula.txt': b'# Manually accepted synthetic fixture\neula=true\n',
            'java9args.txt': b'# Synthetic opaque fixture, NOT GTNH JVM options\n',
        }.items():
            p = self.source / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(content)
        (self.source / 'empty-directory').mkdir()
        with socket.socket() as s:
            s.bind(('127.0.0.1', 0))
            self.port = s.getsockname()[1]
        (self.source / 'server.properties').write_text(
            'online-mode=true\nwhite-list=true\nserver-ip=127.0.0.1\nserver-port=%d\n'
            'max-players=1\nenable-rcon=false\nenable-query=false\n' % self.port)
        self.fake = self.base / 'fake-java'
        self.fake.write_text(FAKE.replace('INTERPRETER', str(Path(sys.executable).resolve())))
        self.fake.chmod(0o700)
        self.scan = mock.patch.object(h, 'inspect_processes', return_value=[])
        self.scan.start()
        self.addCleanup(self.scan.stop)
        self.mem = mock.patch.object(h, 'memory_available', return_value=100000)
        self.mem.start()
        self.addCleanup(self.mem.stop)
        self.addCleanup(self.finish_run)

    def init(self):
        result = h.initialize(argparse.Namespace(root=str(self.root), source=str(self.source), ack_disposable=True))
        self.assertTrue(result['initialized'])
        self.assertEqual(h.managed(str(self.root)), self.root)
        return h.active(self.root)[1]

    def start_args(self, **overrides):
        args = h.parser().parse_args(['start', '--root', str(self.root), '--java', str(self.fake),
                                     '--jar', 'server.jar', '--xms', '16M', '--xmx', '32M',
                                     '--reserve-mib', '1', '--boot-timeout', '2',
                                     '--stall-timeout', '1', '--idle-confirmed'])
        vars(args).update(overrides)
        return args

    def launch(self, **overrides):
        with h.locked(self.root) as fd:
            return h.start_server(self.start_args(**overrides), self.root, fd)

    def stop(self, timeout=3):
        args = argparse.Namespace(idle_confirmed=True, block_exe=[], stop_timeout=timeout)
        with h.locked(self.root):
            return h.stop_server(args, self.root)

    def finish_run(self):
        # Every synthetic server is stopped through its FIFO, NEVER a signal.
        if not (self.root / 'current-run.json').exists():
            return
        run, state = h.run_record(self.root)
        if state.get('server') and h.alive(state['server']):
            h.console_stop(run, state)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if not any(state.get(k) and h.alive(state[k]) for k in ('server', 'supervisor')):
                break
            time.sleep(0.05)
        self.assertFalse(state.get('server') and h.alive(state['server']), 'synthetic child must exit gracefully')
        if state.get('supervisor'):
            try:
                os.waitpid(state['supervisor']['pid'], 0)
            except ChildProcessError:
                pass

    def test_snapshot_change_reset_verify_preserves_everything(self):
        source_manifest = h.tree_manifest(self.source)
        server = self.init()
        original = h.active(self.root)[0]
        self.assertTrue(h.snapshot(self.root, 'baseline')['verified'])
        snapshot_bytes = h.tree_manifest(self.root / 'snapshots' / 'baseline')
        machine = server / 'world/DIM42/machines.dat'
        machine.write_bytes(b'changed synthetic machine contents=0')
        with self.assertRaises(h.Refused):
            h.verify(self.root, 'baseline')
        result = h.reset(self.root, 'baseline')
        self.assertNotEqual(result['generation'], original)
        self.assertTrue(h.verify(self.root, 'baseline')['active_matches_snapshot'])
        self.assertEqual(machine.read_bytes(), b'changed synthetic machine contents=0')
        self.assertEqual(h.tree_manifest(self.source), source_manifest)
        self.assertEqual(h.tree_manifest(self.root / 'snapshots' / 'baseline'), snapshot_bytes)
        self.assertEqual(len(list((self.root / 'generations').iterdir())), 2)
        self.assertTrue((h.active(self.root)[1] / 'empty-directory').is_dir())

    def test_existing_snapshot_and_root_never_overwritten(self):
        self.init()
        h.snapshot(self.root, 'one')
        before = h.tree_manifest(self.root / 'snapshots' / 'one')
        with self.assertRaises(FileExistsError):
            h.snapshot(self.root, 'one')
        with self.assertRaises(h.Refused):
            self.init()
        self.assertEqual(h.tree_manifest(self.root / 'snapshots' / 'one'), before)

    def test_corrupt_snapshot_refuses_switch(self):
        self.init()
        h.snapshot(self.root, 'good')
        before = h.read_json(self.root / 'active.json')
        (self.root / 'snapshots/good/server/world/DIM42/machines.dat').write_bytes(b'corrupt')
        with self.assertRaises(h.Refused):
            h.reset(self.root, 'good')
        self.assertEqual(h.read_json(self.root / 'active.json'), before)
        self.assertEqual(len(list((self.root / 'generations').iterdir())), 1)

    def test_corrupt_manifest_and_extra_file(self):
        self.init()
        h.snapshot(self.root, 'good')
        (self.root / 'snapshots/good/server/unexpected').write_text('extra')
        with self.assertRaises(h.Refused):
            h.verify(self.root, 'good')
        (self.root / 'snapshots/good/manifest.json').write_text('{}')
        with self.assertRaises(h.Refused):
            h.reset(self.root, 'good')

    def test_no_ack_no_write(self):
        with self.assertRaises(h.Refused):
            h.initialize(argparse.Namespace(root=str(self.root), source=str(self.source), ack_disposable=False))
        self.assertFalse(self.root.exists())

    def test_overlap_unmarked_relative_and_unsafe_paths(self):
        for value in ('relative', '/', str(Path.home()), str(self.base / '..' / 'bad'), str(self.base) + '/./bad'):
            with self.subTest(value=value), self.assertRaises(h.Refused):
                h.absolute(value)
        with self.assertRaises(h.Refused):
            h.initialize(argparse.Namespace(root=str(self.source / 'nested'), source=str(self.source), ack_disposable=True))
        self.assertFalse((self.source / 'nested').exists())
        with self.assertRaises((h.Refused, OSError)):
            h.managed(str(self.source))

    def test_symlink_ancestor_source_and_nested_link_rejected(self):
        alias = self.base / 'alias'
        alias.symlink_to(self.source, target_is_directory=True)
        with self.assertRaises(h.Refused):
            h.absolute(str(alias / 'world'))
        (self.source / 'link').symlink_to(self.base)
        with self.assertRaises(h.Refused):
            self.init()
        self.assertFalse(self.root.exists())

    def test_hardlink_and_special_file_rejected(self):
        os.link(self.source / 'server.jar', self.source / 'second.jar')
        with self.assertRaises(h.Refused):
            self.init()
        # Unlink only fixture attack setup, never harness-managed originals.
        (self.source / 'second.jar').unlink()
        os.mkfifo(self.source / 'pipe')
        with self.assertRaises(h.Refused):
            self.init()
        self.assertFalse(self.root.exists())

    def test_snapshot_path_traversal_and_managed_symlink(self):
        self.init()
        for name in ('../escape', '/escape', '..', '.', 'with space', 'a' * 65):
            with self.subTest(name=name), self.assertRaises(h.Refused):
                h.snapshot(self.root, name)
        (self.root / 'snapshots' / 'link').symlink_to(self.source)
        with self.assertRaises(h.Refused):
            h.reset(self.root, 'link')
        marker = h.read_json(self.root / 'active.json')
        h.atomic_json(self.root / 'active.json', {'generation': '../../escape'})
        with self.assertRaises(h.Refused):
            h.active(self.root)
        h.atomic_json(self.root / 'active.json', marker)

    def test_lifecycle_lock_serializes(self):
        self.init()
        with h.locked(self.root):
            with self.assertRaises(h.Refused):
                with h.locked(self.root):
                    self.fail('lock should exclude a second operation')
        with h.locked(self.root):
            pass

    def test_lock_hardlink_rejected(self):
        self.init()
        os.link(self.root / '.lock', self.base / 'lock-alias')
        with self.assertRaises(h.Refused):
            with h.locked(self.root):
                pass

    def test_source_mutation_refuses_import_without_marker(self):
        real = h.tree_manifest
        calls = 0
        def changing(path):
            nonlocal calls
            calls += 1
            if calls == 3:
                (self.source / 'server.jar').write_bytes(b'changed during copy')
            return real(path)
        with mock.patch.object(h, 'tree_manifest', side_effect=changing):
            with self.assertRaises(h.Refused):
                self.init()
        self.assertFalse((self.root / h.MARKER).exists())
        self.assertTrue((self.root / 'generations').exists())

    def assert_enumeration_failure(self, operation, phase, nested, error=None):
        """Fail one real walk, not a mocked manifest, at a lifecycle phase."""
        self.enumeration_case = getattr(self, 'enumeration_case', 0) + 1
        root = self.base / ('enumeration-case-%d' % self.enumeration_case)
        real_manifest, real_scandir = h.tree_manifest, os.scandir
        source_before = real_manifest(self.source)
        if operation != 'import':
            h.initialize(argparse.Namespace(root=str(root), source=str(self.source), ack_disposable=True))
            if operation in ('reset', 'verify'):
                h.snapshot(root, 'baseline')
        old_generations = list((root / 'generations').iterdir()) if root.exists() else []
        old_trees = {p: real_manifest(p) for p in old_generations}
        active_before = (root / 'active.json').read_bytes() if root.exists() else None
        baseline = root / 'snapshots' / 'baseline'
        baseline_before = real_manifest(baseline) if baseline.exists() else None
        calls, failures = 0, 0

        def manifest(path):
            nonlocal calls, failures
            calls += 1
            if calls != phase:
                return real_manifest(path)
            blocked = path / 'world' / 'DIM42' if nested else path
            if error is None:
                # Current-user real permission denial; never switch users.
                mode = stat.S_IMODE(blocked.stat().st_mode)
                blocked.chmod(0o000)
                try:
                    failures += 1
                    return real_manifest(path)
                finally:
                    blocked.chmod(mode)

            def scandir(target):
                nonlocal failures
                if Path(target) == blocked:
                    failures += 1
                    number = {PermissionError: errno.EACCES, FileNotFoundError: errno.ENOENT}.get(error, errno.EIO)
                    raise error(number, 'synthetic enumeration failure', str(target))
                return real_scandir(target)
            with mock.patch.object(h.os, 'scandir', side_effect=scandir):
                return real_manifest(path)

        with mock.patch.object(h, 'tree_manifest', side_effect=manifest):
            with self.assertRaises(OSError):
                if operation == 'import':
                    h.initialize(argparse.Namespace(root=str(root), source=str(self.source), ack_disposable=True))
                else:
                    {'snapshot': h.snapshot, 'reset': h.reset, 'verify': h.verify}[operation](
                        root, 'failed' if operation == 'snapshot' else 'baseline')
        self.assertEqual(calls, phase, 'must abort at the failing enumeration')
        self.assertEqual(failures, 1, 'must exercise the targeted scandir')
        self.assertEqual(real_manifest(self.source), source_before)
        for generation, before in old_trees.items():
            self.assertEqual(real_manifest(generation), before, 'old generation must be preserved')
        if baseline_before is not None:
            self.assertEqual(real_manifest(baseline), baseline_before)
        if operation == 'import':
            self.assertFalse((root / h.MARKER).exists())
            self.assertFalse((root / 'active.json').exists())
            if phase == 1:
                self.assertFalse(root.exists())
                return
        else:
            self.assertEqual((root / 'active.json').read_bytes(), active_before)
        new_generations = set((root / 'generations').iterdir()) - set(old_generations)
        for generation in new_generations:
            self.assertFalse((generation / 'import-manifest.json').exists())
        if operation == 'snapshot':
            partial = root / 'snapshots' / 'failed'
            self.assertTrue(partial.is_dir())
            self.assertFalse((partial / 'manifest.json').exists())
            destination = partial / 'server'
            copy_finished = phase >= 2
            self.assertFalse(new_generations)
        elif operation in ('import', 'reset') and (operation == 'import' or phase >= 2):
            self.assertEqual(len(new_generations), 1)
            destination = next(iter(new_generations)) / 'server'
            copy_finished = phase >= 3
        else:
            self.assertFalse(new_generations)
            return
        if copy_finished:
            self.assertTrue(destination.is_dir(), 'failed copy must not be deleted')
            self.assertEqual(real_manifest(destination), source_before,
                             'retain all copied files even when later verification fails')
        else:
            self.assertFalse(destination.exists(), 'initial walk must fail before copying')

    def enumeration_matrix(self, operation, phases, real_permissions=False):
        if real_permissions:
            probe = self.base / 'permission-probe'
            probe.mkdir()
            probe.chmod(0o000)
            try:
                try:
                    with os.scandir(probe) as entries:
                        list(entries)
                except PermissionError:
                    pass
                else:
                    self.skipTest('current-user privileges bypass mode-000 scandir denial')
            finally:
                probe.chmod(0o700)
        for phase in phases:
            for nested in (False, True):
                for error in ((None,) if real_permissions else (PermissionError, FileNotFoundError, OSError)):
                    with self.subTest(operation=operation, phase=phase, nested=nested, error=error):
                        self.assert_enumeration_failure(operation, phase, nested, error)

    def test_import_scandir_errors_abort_all_manifest_phases(self):
        # Initial inspection, copy-before, source-after, destination-after.
        self.enumeration_matrix('import', range(1, 5))

    def test_snapshot_scandir_errors_abort_all_manifest_phases(self):
        self.enumeration_matrix('snapshot', range(1, 4))

    def test_reset_scandir_errors_abort_all_manifest_phases(self):
        # Snapshot-before, copy-before/source-after/dest-after, snapshot-after.
        self.enumeration_matrix('reset', range(1, 6))

    def test_verify_scandir_errors_abort_both_trees(self):
        self.enumeration_matrix('verify', range(1, 3))

    def test_import_real_permission_denial_at_all_manifest_phases(self):
        self.enumeration_matrix('import', range(1, 5), real_permissions=True)

    def test_snapshot_real_permission_denial_at_all_manifest_phases(self):
        self.enumeration_matrix('snapshot', range(1, 4), real_permissions=True)

    def test_reset_real_permission_denial_at_all_manifest_phases(self):
        self.enumeration_matrix('reset', range(1, 6), real_permissions=True)

    def test_verify_real_permission_denial_in_both_trees(self):
        self.enumeration_matrix('verify', range(1, 3), real_permissions=True)

    def test_scandir_iteration_error_cannot_return_partial_manifest(self):
        real_scandir = os.scandir
        for blocked in (self.source, self.source / 'world' / 'DIM42'):
            class BrokenIterator:
                def __init__(self, iterator):
                    self.iterator = iterator
                    self.read = False

                def __enter__(self):
                    self.iterator.__enter__()
                    return self

                def __exit__(self, *args):
                    return self.iterator.__exit__(*args)

                def __iter__(self):
                    return self

                def __next__(self):
                    if self.read:
                        raise OSError(errno.EIO, 'synthetic mid-enumeration failure')
                    self.read = True
                    return next(self.iterator)

            def scandir(path):
                iterator = real_scandir(path)
                return BrokenIterator(iterator) if Path(path) == blocked else iterator
            with self.subTest(blocked=blocked), mock.patch.object(h.os, 'scandir', side_effect=scandir):
                with self.assertRaises(OSError):
                    h.tree_manifest(self.source)

    def test_guard_fails_closed_and_idle_not_bypass(self):
        ident = {'pid': 99999999, 'starttime': 10, 'boot_id': 'synthetic'}
        for name in ('java', 'steam', 'gradle', 'codex', 'python3.11', 'node', 'custom-game'):
            with self.subTest(name=name), mock.patch.object(h, 'inspect_processes', return_value=[(ident, name, name)]):
                with self.assertRaises(h.Refused):
                    h.guard(True, 10, ['custom-game'])
        with mock.patch.object(h, 'inspect_processes', side_effect=h.Refused('unknown inspection')):
            with self.assertRaises(h.Refused):
                h.guard(True, 10)
        with mock.patch.object(h, 'memory_available', return_value=9):
            with self.assertRaises(h.Refused):
                h.guard(True, 10)
        with self.assertRaises(h.Refused):
            h.guard(False, 1)

    def test_owned_exclusion_requires_exact_starttime(self):
        live = {'pid': 987654, 'starttime': 20, 'boot_id': 'synthetic'}
        stale = dict(live, starttime=19)
        with mock.patch.object(h, 'proc_identity', return_value=live), \
                mock.patch.object(h, 'inspect_processes', return_value=[(live, 'java', 'java')]):
            self.assertEqual(h.guard(True, 1, owned=[live])['process_check'], 'passed')
            with self.assertRaises(h.Refused):
                h.guard(True, 1, owned=[stale])

    def test_process_inspection_permission_and_hidden_table_fail_closed(self):
        self.scan.stop()
        try:
            with mock.patch.object(Path, 'read_text', side_effect=PermissionError('synthetic denied')):
                with self.assertRaises(h.Refused):
                    h.inspect_processes()
            with mock.patch.object(Path, 'read_text', return_value='proc /proc proc rw,hidepid=2 0 0\n'):
                with self.assertRaises(h.Refused):
                    h.inspect_processes()
        finally:
            self.scan.start()

    def test_cgroup_v1_v2_ancestor_limits_and_unknown_mapping(self):
        mount = self.base / 'synthetic-cgroup'
        leaf = mount / 'branch' / 'leaf'
        leaf.mkdir(parents=True)
        real_read = Path.read_text
        for version in ('cgroup2', 'cgroup'):
            ceiling, used = ('memory.max', 'memory.current') if version == 'cgroup2' else (
                'memory.limit_in_bytes', 'memory.usage_in_bytes')
            (mount / ceiling).write_text('1000')
            (mount / used).write_text('600')
            (leaf / ceiling).write_text('500')
            (leaf / used).write_text('200')
            membership = '0::/branch/leaf\n' if version == 'cgroup2' else '4:memory:/branch/leaf\n'
            mountinfo = '1 0 0:1 / %s rw - %s none rw,memory\n' % (mount, version)
            def read(path, *args, **kwargs):
                if str(path) == '/proc/self/cgroup':
                    return membership
                if str(path) == '/proc/self/mountinfo':
                    return mountinfo
                return real_read(path, *args, **kwargs)
            with mock.patch.object(Path, 'read_text', read):
                self.assertEqual(h.cgroup_headroom(), 300)
                (leaf / ceiling).write_text('2000')
                self.assertEqual(h.cgroup_headroom(), 400)
                membership = '0::/../../hidden\n' if version == 'cgroup2' else '4:memory:/../../hidden\n'
                with self.assertRaises(h.Refused):
                    h.cgroup_headroom()

    def test_effective_memory_is_bounded_by_cgroup(self):
        self.mem.stop()
        try:
            with mock.patch.object(Path, 'read_text', return_value='MemAvailable: 5000 kB\n'), \
                    mock.patch.object(h, 'cgroup_headroom', return_value=1024 * 1024):
                self.assertEqual(h.memory_available(), 1)
            with mock.patch.object(Path, 'read_text', return_value='MemAvailable: 5000 kB\n'), \
                    mock.patch.object(h, 'cgroup_headroom', side_effect=OSError('unknown')):
                with self.assertRaises(h.Refused):
                    h.memory_available()
        finally:
            self.mem.start()

    def test_cli_snapshot_reset_verify_dispatch(self):
        server = self.init()
        def command(*words):
            with contextlib.redirect_stdout(io.StringIO()) as out, contextlib.redirect_stderr(io.StringIO()) as err:
                rc = h.main([words[0], '--root', str(self.root), *words[1:]])
            self.assertEqual(rc, 0, err.getvalue())
            return json.loads(out.getvalue())
        self.assertTrue(command('snapshot', 'cli-copy')['verified'])
        (server / 'world/DIM42/machines.dat').write_bytes(b'changed')
        self.assertTrue(command('reset', 'cli-copy')['verified'])
        self.assertTrue(command('verify', 'cli-copy')['active_matches_snapshot'])

    def test_stop_guard_refusal_preserves_manageable_run(self):
        self.init()
        self.launch()
        ident = {'pid': 987654, 'starttime': 20, 'boot_id': 'synthetic'}
        with mock.patch.object(h, 'inspect_processes', return_value=[(ident, 'custom-game', 'custom-game')]):
            with h.locked(self.root), self.assertRaises(h.Refused):
                h.stop_server(argparse.Namespace(idle_confirmed=True, block_exe=['custom-game'], stop_timeout=1), self.root)
        self.assertTrue(h.health(self.root)['running'])
        self.assertTrue(self.stop()['stopped'])

    def test_online_mode_whitelist_loopback_enforced(self):
        server = self.init()
        for properties in ('online-mode=false\nwhite-list=true\nserver-ip=127.0.0.1\n',
                           'online-mode=true\nwhite-list=false\nserver-ip=127.0.0.1\n',
                           'online-mode=true\nwhite-list=true\nserver-ip=\n',
                           'online-mode=true\nonline-mode=false\nwhite-list=true\n',
                           'online-mode=true\\\nwhite-list=true\n'):
            with self.subTest(properties=properties):
                (server / 'server.properties').write_text(properties)
                with self.assertRaises(h.Refused):
                    h.launch_config(self.start_args(), self.root)

    def assert_properties_rejected_without_launch_or_writes(self, server):
        server_before = h.tree_manifest(server)
        source_before = h.tree_manifest(self.source)
        root_before = h.tree_manifest(self.root)
        for command in ('preflight', 'start'):
            argv = [command, '--root', str(self.root), '--java', str(self.fake),
                    '--jar', 'server.jar', '--xms', '16M', '--xmx', '32M', '--idle-confirmed']
            with self.subTest(command=command), \
                    mock.patch.object(h.os, 'fork', side_effect=AssertionError('must not launch')) as fork, \
                    mock.patch.object(h, 'guard') as guard, \
                    mock.patch.object(h, 'port_open') as port, \
                    contextlib.redirect_stdout(io.StringIO()) as out, \
                    contextlib.redirect_stderr(io.StringIO()) as err:
                self.assertEqual(h.main(argv), 1)
                self.assertFalse(json.loads(err.getvalue())['ok'])
                self.assertEqual(out.getvalue(), '')
                fork.assert_not_called()
                guard.assert_not_called()
                port.assert_not_called()
            self.assertEqual(h.tree_manifest(server), server_before)
            self.assertEqual(h.tree_manifest(self.source), source_before)
            self.assertEqual(h.tree_manifest(self.root), root_before)
            self.assertFalse((self.root / 'current-run.json').exists())
            self.assertEqual(list((self.root / 'runs').iterdir()), [])

    def test_properties_aliases_and_duplicates_refuse_preflight_and_start(self):
        server = self.init()
        properties = (server / 'server.properties').read_bytes() + b'level-name=world\n'
        eula = (server / 'eula.txt').read_bytes()
        for key, unsafe in (('online-mode', 'false'), ('white-list', 'false'),
                            ('server-ip', '0.0.0.0'), ('server-port', '1'),
                            ('level-name', '../../outside'), ('eula', 'false')):
            escaped_key = r'\u%04x' % ord(key[0]) + key[1:]
            aliases = (key + ':' + unsafe + '=ignored', key + ' ' + unsafe + '=ignored',
                       key + '\t' + unsafe + '=ignored', key + '\f' + unsafe + '=ignored',
                       key + ' :' + unsafe + '=ignored', key + ':' + unsafe,
                       key + ' ' + unsafe, key + ' =' + unsafe, key + '= ' + unsafe,
                       escaped_key + '=' + unsafe, key + r'\=alias=' + unsafe,
                       key + '=' + unsafe, ' \t\f' + key + '=' + unsafe)
            for alias in aliases:
                with self.subTest(key=key, alias=alias):
                    (server / 'server.properties').write_bytes(properties)
                    (server / 'eula.txt').write_bytes(eula)
                    target = server / ('eula.txt' if key == 'eula' else 'server.properties')
                    with target.open('ab') as f:
                        f.write(alias.encode('ascii') + b'\n')
                    self.assert_properties_rejected_without_launch_or_writes(server)

    def test_properties_nonjava_whitespace_and_line_breaks_refuse_launch(self):
        server = self.init()
        properties = (server / 'server.properties').read_bytes()
        eula = (server / 'eula.txt').read_bytes()
        for filename, original, key in (('server.properties', properties, b'online-mode'),
                                        ('eula.txt', eula, b'eula')):
            for char in (b'\v', b'\x1c', b'\x1d', b'\x1e', b'\x85', b'\xa0', b'\x00'):
                for payload in (char + key + b'=true\n', key + b'=true' + char + b'\n',
                                key + b'=true' + char + key + b':false=ignored\n'):
                    with self.subTest(filename=filename, payload=payload):
                        (server / 'server.properties').write_bytes(properties)
                        (server / 'eula.txt').write_bytes(eula)
                        (server / filename).write_bytes(original.replace(key + b'=true\n', payload))
                        self.assert_properties_rejected_without_launch_or_writes(server)

    def test_plain_properties_documented_subset_and_literal_values(self):
        path = self.base / 'plain.properties'
        for ending in (b'\n', b'\r', b'\r\n'):
            path.write_bytes(ending.join((b' \t\f# comment', b'\f! comment', b' \t\f',
                                          b' \t\fonline-mode=true', b'empty=',
                                          b'motd=literal : and = in value', b'key_1.name=value', b'')))
            with self.subTest(ending=ending):
                self.assertEqual(h.plain_properties(path),
                                 {'online-mode': 'true', 'empty': '',
                                  'motd': 'literal : and = in value', 'key_1.name': 'value'})
        for payload in (b'key=value \n', b'key=value\t\n', b'key=value\f\n',
                        b'key=\\u0076alue\n', b'key=value\\\ncontinued=other\n',
                        b'key=value\n \t\fkey=other\n', b'key\t=value\n',
                        b'key\f=value\n', b'key:value\n', b'key value\n',
                        'key=value\u2028other=ignored\n'.encode('utf-8')):
            path.write_bytes(payload)
            with self.subTest(payload=payload), self.assertRaises(h.Refused):
                h.plain_properties(path)

    def test_isolation_properties_and_manual_eula_required_without_writes(self):
        server = self.init()
        original = (server / 'server.properties').read_text()
        for key, valid, invalid in (('max-players', '1', '2'), ('enable-rcon', 'false', 'true'),
                                    ('enable-query', 'false', 'true')):
            for properties in (original.replace(key + '=' + valid, key + '=' + invalid),
                               original.replace(key + '=' + valid + '\n', '')):
                with self.subTest(key=key):
                    (server / 'server.properties').write_text(properties)
                    before = h.tree_manifest(server)
                    with self.assertRaises(h.Refused):
                        h.launch_config(self.start_args(), self.root)
                    self.assertEqual(h.tree_manifest(server), before)
        (server / 'server.properties').write_text(original)
        for eula in ('eula=false\n', '# not accepted\n', 'eula=true\neula=false\n'):
            (server / 'eula.txt').write_text(eula)
            before = h.tree_manifest(server)
            with self.assertRaises(h.Refused):
                h.launch_config(self.start_args(), self.root)
            self.assertEqual(h.tree_manifest(server), before)
        (server / 'eula.txt').unlink()  # Remove only attack fixture.
        with self.assertRaises(FileNotFoundError):
            h.launch_config(self.start_args(), self.root)
        self.assertFalse((self.root / 'current-run.json').exists())

    def test_exact_one_canonical_whitelist_identity_and_same_sole_ops(self):
        server = self.init()
        bad_whitelists = ([], [IDENTITY, IDENTITY], {}, [None], [{'uuid': IDENTITY['uuid']}],
                          [dict(IDENTITY, uuid=IDENTITY['uuid'].upper())],
                          [dict(IDENTITY, uuid=IDENTITY['uuid'].replace('-', ''))],
                          [dict(IDENTITY, uuid='not-a-uuid')],
                          [dict(IDENTITY, uuid='00000000-0000-0000-0000-000000000000')],
                          [dict(IDENTITY, name='bad name')], [dict(IDENTITY, name='x' * 17)],
                          [dict(IDENTITY, unexpected=True)])
        for entries in bad_whitelists:
            with self.subTest(entries=entries):
                (server / 'whitelist.json').write_text(json.dumps(entries))
                with self.assertRaises((h.Refused, ValueError)):
                    h.launch_config(self.start_args(), self.root)
        (server / 'whitelist.json').write_text(json.dumps([IDENTITY]))
        for ops in ({}, [IDENTITY, IDENTITY], [dict(IDENTITY, name='OtherTester')],
                    [dict(IDENTITY, uuid='87654321-1234-4234-8234-123456789abc')],
                    [dict(IDENTITY, level=True)], [dict(IDENTITY, bypassesPlayerLimit='false')]):
            with self.subTest(ops=ops):
                (server / 'ops.json').write_text(json.dumps(ops))
                with self.assertRaises(h.Refused):
                    h.launch_config(self.start_args(), self.root)
        for ops in ([], [IDENTITY], [dict(IDENTITY, level=4, bypassesPlayerLimit=False)]):
            (server / 'ops.json').write_text(json.dumps(ops))
            self.assertEqual(h.launch_config(self.start_args(), self.root)['port'], self.port)

    def test_trusted_preexisting_args_file_argv_and_source_untouched(self):
        before = h.tree_manifest(self.source)
        server = self.init()
        self.launch(java_args_file='java9args.txt')
        self.assertEqual(json.loads((server / 'received-argv.json').read_text()),
                         ['@java9args.txt', '-Xms16m', '-Xmx32m', '-jar', 'server.jar', 'nogui'])
        self.assertEqual(h.run_record(self.root)[1]['config']['java_args_file'], 'java9args.txt')
        self.assertTrue(self.stop()['clean'])
        self.assertEqual(h.tree_manifest(self.source), before)

    def test_ambient_java_options_refused_without_exposing_values(self):
        self.init()
        for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
            with self.subTest(key=key), mock.patch.dict(os.environ, {key: '-Xmx999G'}):
                with self.assertRaises(h.Refused) as refusal:
                    h.launch_config(self.start_args(), self.root)
                self.assertNotIn('999', str(refusal.exception))

    def test_args_file_traversal_links_missing_and_nonregular_refused(self):
        server = self.init()
        for name in ('../java9args.txt', '/java9args.txt', './java9args.txt', 'world/../../escape',
                     'world//args.txt', '.', ''):
            with self.subTest(name=name), self.assertRaises(h.Refused):
                h.launch_config(self.start_args(java_args_file=name), self.root)
        with self.assertRaises(FileNotFoundError):
            h.launch_config(self.start_args(java_args_file='missing.txt'), self.root)
        self.assertFalse((server / 'missing.txt').exists())
        with self.assertRaises(h.Refused):
            h.launch_config(self.start_args(java_args_file='config'), self.root)
        alias = server / 'alias.txt'
        alias.symlink_to(self.source / 'java9args.txt')
        with self.assertRaises(h.Refused):
            h.launch_config(self.start_args(java_args_file='alias.txt'), self.root)
        alias.unlink()
        alias.symlink_to(self.source, target_is_directory=True)
        with self.assertRaises(h.Refused):
            h.launch_config(self.start_args(java_args_file='alias.txt/java9args.txt'), self.root)
        self.assertFalse((self.root / 'current-run.json').exists())

    def test_unsafe_jar_java_and_heap_rejected(self):
        self.init()
        for jar in ('../server.jar', '/server.jar', './server.jar', 'server.txt'):
            with self.subTest(jar=jar), self.assertRaises(h.Refused):
                h.launch_config(self.start_args(jar=jar), self.root)
        for overrides in ({'java': 'java'}, {'xms': 64, 'xmx': 32}, {'reserve_mib': 0},
                          {'boot_timeout': -1}, {'boot_timeout': float('nan')},
                          {'stall_timeout': float('inf')}):
            with self.subTest(overrides=overrides), self.assertRaises(h.Refused):
                h.launch_config(self.start_args(**overrides), self.root)
        for value in ('1', '0G', '-1M', '1.5G'):
            with self.assertRaises(argparse.ArgumentTypeError):
                h.heap_mib(value)

    def test_external_world_path_refused(self):
        server = self.init()
        properties = (server / 'server.properties').read_text()
        for world in ('/protected-world', '../protected-world', 'world/../../escape', '.', './world'):
            with self.subTest(world=world):
                (server / 'server.properties').write_text(properties + 'level-name=' + world + '\n')
                with self.assertRaises(h.Refused):
                    h.launch_config(self.start_args(), self.root)

    def test_synthetic_lifecycle_argv_boot_heap_and_graceful_save(self):
        server = self.init()
        ready = self.launch()
        self.assertTrue(ready['ready'])
        self.assertTrue(ready['done_marker'])
        self.assertTrue(ready['port_open'])
        self.assertIsNotNone(ready['boot_seconds'])
        self.assertIsNone(ready['required_heap_mib'])
        self.assertEqual((ready['configured_xms_mib'], ready['configured_xmx_mib']), (16, 32))
        self.assertEqual(json.loads((server / 'received-argv.json').read_text()),
                         ['-Xms16m', '-Xmx32m', '-jar', 'server.jar', 'nogui'])
        with socket.socket() as unrelated:
            unrelated.bind(('127.0.0.1', 0))
            unrelated.listen()
            other_port = unrelated.getsockname()[1]
            self.assertTrue(h.port_open(other_port))
            self.assertFalse(h.owned_port(h.run_record(self.root)[1]['server'], other_port))
        with self.assertRaises(h.Refused):
            h.snapshot(self.root, 'running')
        with self.assertRaises(h.Refused):
            self.launch()
        self.assertTrue(self.stop()['clean'])
        self.assertEqual((server / 'saved-by-fake').read_bytes(), b'graceful synthetic save')
        self.assertFalse(h.health(self.root)['running'])
        run, state = h.run_record(self.root)
        self.assertEqual(h.read_json(run / 'graceful-stop.json'), h.stop_receipt(run, state))
        self.assertTrue(h.snapshot(self.root, 'after-stop')['verified'])
        self.assertTrue(h.verify(self.root, 'after-stop')['active_matches_snapshot'])
        self.assertTrue(h.reset(self.root, 'after-stop')['verified'])

    def test_stale_identity_never_sends_console_stop(self):
        self.init()
        self.launch()
        run, state = h.run_record(self.root)
        original = dict(state['server'])
        state['server'] = dict(original, starttime=original['starttime'] - 1)
        h.atomic_json(run / 'state.json', state)
        try:
            with mock.patch.object(h, 'console_stop') as send:
                with self.assertRaises(h.Refused):
                    self.stop()
                send.assert_not_called()
            self.assertFalse(h.health(self.root)['ready'])
        finally:
            # Supervisor may have refreshed state in the meantime.
            state = h.read_json(run / 'state.json')
            state['server'] = original
            h.atomic_json(run / 'state.json', state)
        self.stop()

    def test_readiness_requires_done_and_port_and_preserves_handle(self):
        server = self.init()
        (server / 'fake-mode').write_text('no-done')
        with self.assertRaises(h.Refused):
            self.launch(boot_timeout=0.5, stall_timeout=2)
        health = h.health(self.root)
        self.assertTrue(health['running'])
        self.assertFalse(health['ready'])
        self.assertEqual(health['failure'], 'boot timeout')
        self.stop()

    def test_done_without_port_times_out(self):
        server = self.init()
        (server / 'fake-mode').write_text('no-port')
        with self.assertRaises(h.Refused):
            self.launch(boot_timeout=0.5, stall_timeout=2)
        self.assertTrue(h.health(self.root)['done_marker'])
        self.assertFalse(h.health(self.root)['ready'])
        self.stop()

    def test_current_run_does_not_reuse_previous_done_marker(self):
        server = self.init()
        self.launch()
        previous = h.run_record(self.root)[0]
        self.stop()
        (server / 'fake-mode').write_text('no-done')
        with self.assertRaises(h.Refused):
            self.launch(boot_timeout=0.5, stall_timeout=2)
        current = h.run_record(self.root)[0]
        self.assertNotEqual(current, previous)
        self.assertIn(b'Done (', (previous / 'console.log').read_bytes())
        self.assertFalse(h.health(self.root)['done_marker'])
        self.stop()

    def test_log_stall_remains_unclean_after_stop(self):
        server = self.init()
        (server / 'fake-mode').write_text('silent')
        with self.assertRaises(h.Refused):
            self.launch(stall_timeout=0.3)
        self.assertEqual(h.health(self.root)['failure'], 'boot log stall timeout')
        self.assertFalse(self.stop()['clean'])
        with self.assertRaises(h.Refused):
            h.snapshot(self.root, 'unclean')

    def test_real_fake_flood_retains_bounded_head_and_end_tail(self):
        server = self.init()
        (server / 'fake-mode').write_text('flood')
        self.launch(log_bytes=4096)
        run, state = h.run_record(self.root)
        self.assertLessEqual((run / 'console.log').stat().st_size, 4096)
        self.assertGreater(state['log_dropped_bytes'], 0)
        self.assertTrue(state['done_marker'])
        self.assertTrue(self.stop()['clean'])
        state = h.run_record(self.root)[1]
        head, tail = (run / 'console.log').read_bytes(), (run / 'console-tail.log').read_bytes()
        self.assertEqual(head, b'x' * 4096)
        self.assertEqual(len(tail), 4096)
        self.assertIn(b'synthetic flood end marker', tail)
        self.assertIn(b'synthetic stopped', tail)
        self.assertEqual(state['log_captured_bytes'], len(head))
        self.assertEqual(state['log_tail_captured_bytes'], len(tail))
        self.assertEqual(state['log_tail_dropped_bytes'], state['log_dropped_bytes'])

    def assert_unclean_operations_refused(self):
        before = h.read_json(self.root / 'active.json')
        for operation in (h.snapshot, h.reset, h.verify):
            with self.subTest(operation=operation.__name__), self.assertRaises(h.Refused):
                operation(self.root, 'baseline')
        self.assertEqual(h.read_json(self.root / 'active.json'), before)

    def test_boot_timeout_eventual_exit_never_clean_for_snapshot_reset_verify(self):
        server = self.init()
        h.snapshot(self.root, 'baseline')
        (server / 'fake-mode').write_text('no-done')
        with self.assertRaises(h.Refused):
            self.launch(boot_timeout=0.3, stall_timeout=2)
        self.assert_unclean_operations_refused()
        self.assertFalse(self.stop()['clean'])
        run, state = h.run_record(self.root)
        self.assertEqual(state['phase'], 'exited')
        self.assertEqual(state['exit_code'], 0)
        self.assertEqual(state['failure'], 'boot timeout')
        self.assertFalse((run / 'graceful-stop.json').exists())
        self.assert_unclean_operations_refused()

    def test_crash_exit_zero_without_parent_stop_receipt_is_unclean(self):
        server = self.init()
        h.snapshot(self.root, 'baseline')
        (server / 'fake-mode').write_text('crash0')
        self.launch()
        self.finish_run_after_exit()
        run, state = h.run_record(self.root)
        self.assertEqual(state['phase'], 'exited')
        self.assertEqual(state['exit_code'], 0)
        self.assertIsNone(state['failure'])
        self.assertFalse((run / 'graceful-stop.json').exists())
        with self.assertRaises(h.Refused):
            self.stop()
        self.assert_unclean_operations_refused()

    def finish_run_after_exit(self):
        deadline = time.monotonic() + 4
        while time.monotonic() < deadline:
            run, state = h.run_record(self.root)
            if not any(state.get(k) and h.alive(state[k]) for k in ('server', 'supervisor')):
                return
            time.sleep(0.05)
        self.fail('synthetic crash must exit without a stop request')

    def test_real_fake_crash_flood_tail_includes_exit_marker(self):
        server = self.init()
        h.snapshot(self.root, 'baseline')
        (server / 'fake-mode').write_text('flood-crash')
        self.launch(log_bytes=4096)
        self.finish_run_after_exit()
        run, state = h.run_record(self.root)
        self.assertEqual(state['exit_code'], 7)
        self.assertEqual((run / 'console.log').read_bytes(), b'x' * 4096)
        tail = (run / 'console-tail.log').read_bytes()
        self.assertEqual(len(tail), 4096)
        self.assertTrue(tail.endswith(b'synthetic crash end marker\n'))
        self.assertGreater(state['log_dropped_bytes'], 0)
        self.assert_unclean_operations_refused()

    def test_mismatched_receipt_refused(self):
        self.init()
        self.launch()
        self.assertTrue(self.stop()['clean'])
        run, _ = h.run_record(self.root)
        receipt = h.read_json(run / 'graceful-stop.json')
        receipt['server']['starttime'] -= 1
        h.atomic_json(run / 'graceful-stop.json', receipt)
        self.assert_unclean_operations_refused()

    def test_stop_timeout_retains_live_handle_without_kill(self):
        server = self.init()
        (server / 'fake-mode').write_text('slow-stop')
        self.launch()
        with self.assertRaises(h.Refused):
            self.stop(timeout=0.05)
        self.assertTrue(h.health(self.root)['running'])
        deadline = time.monotonic() + 3
        while h.health(self.root)['running'] and time.monotonic() < deadline:
            time.sleep(0.05)
        self.assertFalse(h.health(self.root)['running'])
        self.assertTrue((server / 'saved-by-fake').exists())
        self.assertTrue((self.root / 'current-run.json').exists())
        self.finish_run_after_exit()
        self.assertFalse((h.run_record(self.root)[0] / 'graceful-stop.json').exists())
        self.assert_unclean_operations_refused()

    def test_server_exit_before_ready(self):
        server = self.init()
        h.snapshot(self.root, 'baseline')
        (server / 'fake-mode').write_text('exit')
        with self.assertRaises(h.Refused):
            self.launch()
        health = h.health(self.root)
        self.assertFalse(health['running'])
        self.assertEqual(health['exit_code'], 7)
        self.assertIsNone(health['boot_seconds'])
        self.finish_run_after_exit()
        self.assert_unclean_operations_refused()

    def test_launch_gate_record_failure_does_not_exec_server(self):
        server = self.init()
        original = h.atomic_json
        def fail_record(path, data):
            if path.name == 'state.json' and data.get('phase') == 'booting':
                raise OSError('synthetic metadata write failure')
            return original(path, data)
        with mock.patch.object(h, 'atomic_json', side_effect=fail_record):
            with self.assertRaises(h.Refused):
                self.launch()
        self.assertFalse((server / 'received-argv.json').exists())
        self.assertTrue((self.root / 'current-run.json').exists())
        with self.assertRaises(h.Refused):
            h.stopped(self.root)

    def test_supervisor_failure_leaves_independent_graceful_stop(self):
        server = self.init()
        original = h.atomic_json
        saves = 0
        def supervisor_failure(path, data):
            nonlocal saves
            if path.name == 'state.json' and data.get('phase') == 'ready':
                saves += 1
                if saves > 1:
                    raise OSError('synthetic supervisor failure')
            return original(path, data)
        with mock.patch.object(h, 'atomic_json', side_effect=supervisor_failure):
            self.launch()
        deadline = time.monotonic() + 3
        while h.health(self.root)['supervisor_running'] and time.monotonic() < deadline:
            time.sleep(0.05)
        health = h.health(self.root)
        self.assertFalse(health['supervisor_running'])
        self.assertTrue(health['running'])
        self.assertFalse(health['ready'])
        self.assertFalse(self.stop()['clean'])
        self.assertEqual((server / 'saved-by-fake').read_bytes(), b'graceful synthetic save')
        self.assertFalse((h.run_record(self.root)[0] / 'graceful-stop.json').exists())
        self.assert_unclean_operations_refused()

    def test_preflight_never_launches(self):
        self.init()
        argv = ['preflight', '--root', str(self.root), '--java', str(self.fake), '--jar', 'server.jar',
                '--java-args-file', 'java9args.txt', '--idle-confirmed', '--xms', '16M', '--xmx', '32M']
        with contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(h.main(argv), 0)
        self.assertTrue(json.loads(out.getvalue())['ok'])
        self.assertFalse((self.root / 'current-run.json').exists())

    def test_cli_help_and_read_only_health(self):
        for command in ('init', 'preflight', 'snapshot', 'reset', 'start', 'stop', 'health', 'verify'):
            result = subprocess.run([sys.executable, str(SPEC.origin), command, '--help'],
                                    capture_output=True, text=True, check=True)
            self.assertIn('--root', result.stdout)
        result = subprocess.run([sys.executable, str(SPEC.origin), 'init', '--root', str(self.root),
                                 '--source', str(self.source), '--ack-disposable'], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        result = subprocess.run([sys.executable, str(SPEC.origin), 'health', '--root', str(self.root)],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)['phase'], 'never-started')


if __name__ == '__main__':
    unittest.main()
