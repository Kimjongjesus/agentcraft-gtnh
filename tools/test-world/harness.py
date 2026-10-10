#!/usr/bin/env python3
"""Local, non-destructive disposable-server harness (Linux, Python stdlib).

Not a sandbox: Java, server jars, and optional JVM argument files are trusted.
Argument files may override JVM settings; their contents are never invented or
sandboxed. Offline manual preparation must supply accepted eula=true and one
canonical whitelist identity; this harness never enrolls/resolves identities.
Failed runs, missing stop receipts (including timed-out stops), and supervisor
failures remain unclean even when all processes eventually exit. No force/reset
bypass exists; only the parent stop operation can record a successful clean stop.
No production-world detection is possible; acknowledgement requires a separate
operator check that the source is a stopped, disposable copy, never a real world.
"""
import argparse
import contextlib
import fcntl
import hashlib
import json
import math
import os
from pathlib import Path
import re
import select
import socket
import stat
import sys
import time
import uuid

VERSION = 1
MARKER = '.test-world.json'
ID = re.compile(r'^[a-f0-9]{32}$')
NAME = re.compile(r'^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$')
DONE = re.compile(rb'Done \([0-9.,]+s\)!')
BLOCKED = frozenset(('java', 'javaw', 'java.exe', 'javaw.exe', 'minecraft',
                     'steam', 'steamwebhelper', 'gamescope', 'wine', 'wine64',
                     'wineserver', 'lutris', 'prismlauncher', 'multimc',
                     'gradle', 'gradlew', 'mvn', 'make', 'ninja', 'cargo',
                     'rustc', 'gcc', 'g++', 'cc1', 'clang', 'cmake', 'npm',
                     'npx', 'node', 'nodejs', 'hermes', 'codex', 'claude',
                     'opencode', 'aider', 'uv', 'pytest'))


class Refused(Exception):
    """A fail-closed refusal; never permission to force or delete anything."""


def require(condition, message):
    if not condition:
        raise Refused(message)


def absolute(value):
    p = Path(value)
    require(p.is_absolute() and '..' not in p.parts, 'path must be absolute without ..')
    # Path does not preserve redundant separators or dots; reject them explicitly.
    require(str(p) == str(value), 'path must use its exact normalized absolute spelling')
    for item in (p, *p.parents):
        if item.exists() or item.is_symlink():
            require(not item.is_symlink(), 'symlink path component refused')
    require(p != Path('/') and p != Path.home(), 'unsafe root/source path')
    return p


def regular(path):
    s = path.lstat()
    require(stat.S_ISREG(s.st_mode) and s.st_nlink == 1,
            'expected a regular, non-hardlinked file')
    return s


def private_dir(path):
    s = path.lstat()
    require(stat.S_ISDIR(s.st_mode) and s.st_uid == os.getuid()
            and not s.st_mode & 0o077, 'managed directory must be owned and private (0700)')


def read_json(path):
    expected = regular(path)
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, 'rb') as f:
        observed = os.fstat(f.fileno())
        require(observed.st_ino == expected.st_ino and observed.st_dev == expected.st_dev
                and observed.st_nlink == 1, 'metadata changed during inspection')
        require(os.fstat(f.fileno()).st_size <= 4 * 1024 * 1024,
                'metadata exceeds safety limit')
        return json.load(f)


def atomic_json(path, data):
    if path.exists() or path.is_symlink():
        regular(path)
    temp = path.with_name('.' + path.name + '.' + uuid.uuid4().hex)
    fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'w') as f:
        json.dump(data, f, sort_keys=True)
        f.write('\n')
        f.flush()
        os.fsync(f.fileno())
    os.replace(temp, path)  # Only mutable control metadata is replaced.
    dfd = os.open(path.parent, os.O_DIRECTORY)
    try:
        os.fsync(dfd)
    finally:
        os.close(dfd)


def token(value):
    require(isinstance(value, str) and ID.fullmatch(value), 'invalid generation/run id')
    return value


def snapshot_name(value):
    require(NAME.fullmatch(value) and value not in ('.', '..'), 'unsafe snapshot name')
    return value


@contextlib.contextmanager
def locked(root):
    private_dir(root)
    path = root / '.lock'
    fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        s = os.fstat(fd)
        require(stat.S_ISREG(s.st_mode) and s.st_nlink == 1 and s.st_uid == os.getuid(),
                'unsafe lifecycle lock')
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise Refused('another lifecycle operation holds the lock') from None
        yield fd
    finally:
        os.close(fd)


def managed(value):
    root = absolute(value)
    private_dir(root)
    marker = read_json(root / MARKER)
    require(marker.get('version') == VERSION and marker.get('disposable') is True
            and marker.get('uid') == os.getuid(), 'unmarked or incompatible managed root')
    for name in ('generations', 'snapshots', 'runs'):
        private_dir(root / name)
    return root


def active(root):
    ident = token(read_json(root / 'active.json')['generation'])
    generation = root / 'generations' / ident
    private_dir(generation)
    server = generation / 'server'
    require(stat.S_ISDIR(server.lstat().st_mode), 'unsafe server directory')
    return ident, server


def tree_manifest(root):
    """Hash every file and include empty directories; follow no links."""
    require(stat.S_ISDIR(root.lstat().st_mode), 'tree root must be a directory')
    result = {}
    for base, dirs, files in os.walk(root, followlinks=False):
        for name in sorted(dirs + files):
            p = Path(base) / name
            s = p.lstat()
            rel = p.relative_to(root).as_posix()
            if stat.S_ISDIR(s.st_mode):
                result[rel] = {'kind': 'directory'}
            else:
                regular(p)
                fd = os.open(p, os.O_RDONLY | os.O_NOFOLLOW)
                with os.fdopen(fd, 'rb') as f:
                    before = os.fstat(f.fileno())
                    require(before.st_ino == s.st_ino and before.st_dev == s.st_dev
                            and before.st_nlink == 1, 'file changed during inspection')
                    h = hashlib.sha256()
                    for chunk in iter(lambda: f.read(1024 * 1024), b''):
                        h.update(chunk)
                    after = os.fstat(f.fileno())
                require((before.st_size, before.st_mtime_ns, before.st_ctime_ns) ==
                        (after.st_size, after.st_mtime_ns, after.st_ctime_ns),
                        'source changed during hashing; stop all writers')
                result[rel] = {'kind': 'file', 'size': s.st_size, 'sha256': h.hexdigest()}
    return result


def copy_tree(source, dest):
    """Read-only on source. New destinations only; partial copies are retained."""
    before = tree_manifest(source)
    dest.mkdir(mode=0o700)
    for rel, entry in sorted(before.items(), key=lambda item: (item[0].count('/'), item[0])):
        src, dst = source / rel, dest / rel
        if entry['kind'] == 'directory':
            dst.mkdir(mode=0o700)
            continue
        regular(src)
        infd = os.open(src, os.O_RDONLY | os.O_NOFOLLOW)
        try:
            info = os.fstat(infd)
            require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1, 'unsafe source file')
            outfd = os.open(dst, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            with os.fdopen(outfd, 'wb') as out, os.fdopen(infd, 'rb', closefd=False) as inp:
                for chunk in iter(lambda: inp.read(1024 * 1024), b''):
                    out.write(chunk)
                out.flush()
                os.fchmod(out.fileno(), stat.S_IMODE(info.st_mode) & 0o700)
                os.fsync(out.fileno())
        finally:
            os.close(infd)
    require(tree_manifest(source) == before, 'source changed during copy; partial copy retained')
    require(tree_manifest(dest) == before, 'copy verification failed; partial copy retained')
    return before


def initialize(args):
    require(args.ack_disposable, '--ack-disposable is required; independently confirm source is disposable and stopped')
    root, source = absolute(args.root), absolute(args.source)
    require(not root.exists(), 'init requires a NEW nonexistent root')
    require(source.is_dir() and root.parent.is_dir(), 'source and root parent must exist')
    require(root not in source.parents and source not in root.parents and root != source,
            'source and managed root must not overlap')
    for p in (source, *source.parents, root.parent, *root.parent.parents):
        require(not (p / MARKER).exists(), 'nested managed roots/sources refused')
    require(len(source.parts) >= 3 and len(root.parts) >= 3, 'unsafe broad root/source')
    tree_manifest(source)  # Reject attacks before creating anything.
    root.mkdir(mode=0o700)
    with locked(root):
        for name in ('generations', 'snapshots', 'runs'):
            (root / name).mkdir(mode=0o700)
        ident = uuid.uuid4().hex
        generation = root / 'generations' / ident
        generation.mkdir(mode=0o700)
        manifest = copy_tree(source, generation / 'server')
        atomic_json(generation / 'import-manifest.json', manifest)
        atomic_json(root / 'active.json', {'generation': ident})
        atomic_json(root / MARKER, {'version': VERSION, 'uid': os.getuid(),
                                   'disposable': True, 'id': uuid.uuid4().hex})
    return {'initialized': True, 'generation': ident,
            'source_writes': False, 'operator_source_check_required': True}


def proc_identity(pid):
    """Never read cmdline/environ. Start ticks plus boot ID prevent PID reuse."""
    try:
        raw = Path('/proc', str(pid), 'stat').read_text()
        fields = raw[raw.rindex(')') + 2:].split()
        if fields[0] == 'Z':
            return None
        return {'pid': int(pid), 'starttime': int(fields[19]),
                'boot_id': Path('/proc/sys/kernel/random/boot_id').read_text().strip()}
    except (FileNotFoundError, ProcessLookupError):
        return None
    except (OSError, ValueError, IndexError):
        raise Refused('process identity inspection unavailable') from None


def alive(identity):
    require(isinstance(identity, dict) and isinstance(identity.get('pid'), int)
            and identity['pid'] > 0 and 'starttime' in identity and 'boot_id' in identity,
            'invalid process identity')
    current = proc_identity(identity['pid'])
    return current is not None and current == identity


def inspect_processes():
    result = []
    try:
        # hidepid can conceal tasks entirely instead of returning EACCES. A
        # successful but incomplete enumeration is not an idle-machine proof.
        mounts = Path('/proc/mounts').read_text().splitlines()
        proc_mounts = [line.split() for line in mounts if len(line.split()) >= 4
                       and line.split()[1] == '/proc']
        require(proc_mounts, 'cannot establish process-table visibility')
        for entry in proc_mounts:
            require(not any(opt.startswith('hidepid=') and opt != 'hidepid=0'
                            for opt in entry[3].split(',')),
                    'hidden process table prevents a complete idle precheck')
        entries = list(Path('/proc').iterdir())
        for p in entries:
            if not p.name.isdecimal():
                continue
            try:
                identity = proc_identity(int(p.name))
                if identity is None:
                    continue
                comm = (p / 'comm').read_text().strip()
                try:
                    exe = Path(os.readlink(p / 'exe')).name
                except FileNotFoundError:
                    # Exited tasks and kernel threads have no executable.
                    raw = (p / 'stat').read_text()
                    fields = raw[raw.rindex(')') + 2:].split()
                    if fields[0] == 'Z' or int(fields[6]) & 0x200000:
                        continue
                    raise Refused('process executable inspection unavailable')
                if proc_identity(int(p.name)) != identity:
                    continue
                result.append((identity, comm, exe))
            except (FileNotFoundError, ProcessLookupError):
                continue
    except OSError:
        raise Refused('process inspection unavailable; refusing lifecycle action') from None
    return result


def cgroup_headroom():
    """Minimum remaining memory across visible v1/v2 ancestor ceilings, bytes.

    None means the visible hierarchy supplies no memory ceiling. Unresolvable
    namespace/mount mappings fail closed rather than treating host RAM as usable.
    """
    memberships = [line.split(':', 2) for line in Path('/proc/self/cgroup').read_text().splitlines()]
    mounts = []
    for line in Path('/proc/self/mountinfo').read_text().splitlines():
        left, right = line.split(' - ', 1)
        fields, fs = left.split(), right.split()
        if fs[0] == 'cgroup2' or (fs[0] == 'cgroup' and 'memory' in fs[2].split(',')):
            decode = lambda s: re.sub(r'\\([0-7]{3})', lambda m: chr(int(m[1], 8)), s)
            mounts.append((fs[0], Path(decode(fields[3])), Path(decode(fields[4]))))
    limits = []
    found_membership = False
    for hierarchy, controllers, location in memberships:
        version = 'cgroup2' if hierarchy == '0' and controllers == '' else 'cgroup'
        if version == 'cgroup' and 'memory' not in controllers.split(','):
            continue
        found_membership = True
        location = Path(location)
        require(location.is_absolute() and '..' not in location.parts,
                'cgroup namespace path cannot be resolved safely')
        candidates = [(base, mount) for kind, base, mount in mounts if kind == version
                      and (location == base or base in location.parents)]
        require(candidates, 'memory cgroup mount is not inspectable')
        for base, mount in candidates:
            current = mount / location.relative_to(base)
            while True:
                require(current.is_dir(), 'memory cgroup directory is not inspectable')
                ceiling = current / ('memory.max' if version == 'cgroup2' else 'memory.limit_in_bytes')
                used = current / ('memory.current' if version == 'cgroup2' else 'memory.usage_in_bytes')
                require(ceiling.exists() == used.exists(), 'incomplete memory cgroup accounting')
                if ceiling.exists():
                    maximum, usage = ceiling.read_text().strip(), int(used.read_text().strip())
                    require(usage >= 0, 'invalid memory cgroup usage')
                    if maximum != 'max':
                        require(int(maximum) >= 0, 'invalid memory cgroup ceiling')
                        limits.append(max(0, int(maximum) - usage))
                if current == mount:
                    break
                current = current.parent
    require(found_membership or not mounts, 'memory cgroup membership is unknown')
    return min(limits) if limits else None


def memory_available():
    try:
        values = dict(line.split(':', 1) for line in Path('/proc/meminfo').read_text().splitlines())
        available = int(values['MemAvailable'].split()[0]) * 1024
        require(available >= 0, 'invalid MemAvailable')
        cgroup = cgroup_headroom()
        return min(available, cgroup if cgroup is not None else available) // (1024 * 1024)
    except (OSError, KeyError, ValueError, IndexError):
        raise Refused('MemAvailable/cgroup inspection unavailable') from None


def guard(idle, required_mib, extra=(), owned=()):
    require(idle, '--idle-confirmed required; it never bypasses automatic checks')
    for value in extra:
        require(value and Path(value).name == value and '/' not in value,
                'blocked executable names must be exact basenames')
    excluded = [i for i in owned if i and alive(i)]
    blocked = []
    for identity, comm, exe in inspect_processes():
        if identity['pid'] == os.getpid() or identity in excluded:
            continue
        names = {comm.lower(), exe.lower()}
        generic = any(re.fullmatch(r'python(?:[0-9]+(?:\.[0-9]+)*)?', n) for n in names)
        if generic or names & BLOCKED or comm in extra or exe in extra:
            blocked.append({'pid': identity['pid'], 'comm': comm, 'exe': exe})
    require(not blocked, 'gaming/build/agent or untracked Java process blocks operation: ' + json.dumps(blocked))
    available = memory_available()
    require(available >= required_mib, 'insufficient MemAvailable for heap/reserve budget')
    return {'available_mib': available, 'required_mib': required_mib,
            'process_check': 'passed', 'idle_confirmed': True}


def run_record(root):
    path = root / 'current-run.json'
    if not path.exists() and not path.is_symlink():
        return None, None
    run = root / 'runs' / token(read_json(path)['run'])
    private_dir(run)
    return run, read_json(run / 'state.json')


def clean_exit(state):
    return (state.get('phase') == 'exited' and type(state.get('exit_code')) is int
            and state['exit_code'] == 0 and state.get('failure', True) is None
            and bool(state.get('server')) and bool(state.get('supervisor')))


def stop_receipt(run, state):
    return {'version': VERSION, 'run': run.name, 'server': state['server'],
            'supervisor': state['supervisor'], 'command': 'stop', 'exit_code': 0}


def stopped(root):
    run, state = run_record(root)
    if state is not None:
        for key in ('server', 'supervisor'):
            if state.get(key):
                require(not alive(state[key]), 'server/supervisor still running; use graceful stop first')
        require(clean_exit(state), 'failed/incomplete/unclean run; lifecycle action refused')
        receipt = run / 'graceful-stop.json'
        require(receipt.exists() and read_json(receipt) == stop_receipt(run, state),
                'no matching successful parent graceful-stop receipt; lifecycle action refused')
    for identity, comm, exe in inspect_processes():
        require(not {comm.lower(), exe.lower()} & {'java', 'javaw', 'java.exe', 'javaw.exe'},
                'untracked Java prevents proving server is stopped')


def snapshot(root, name):
    snapshot_name(name)
    stopped(root)
    _, server = active(root)
    folder = root / 'snapshots' / name
    folder.mkdir(mode=0o700)  # Existing names, including partial snapshots, never overwritten.
    manifest = copy_tree(server, folder / 'server')
    atomic_json(folder / 'manifest.json', manifest)
    return {'snapshot': name, 'verified': True, 'entries': len(manifest)}


def checked_snapshot(root, name):
    folder = root / 'snapshots' / snapshot_name(name)
    private_dir(folder)
    manifest = read_json(folder / 'manifest.json')
    require(tree_manifest(folder / 'server') == manifest, 'snapshot SHA256 manifest mismatch')
    return folder, manifest


def reset(root, name):
    stopped(root)
    folder, manifest = checked_snapshot(root, name)
    ident = uuid.uuid4().hex
    generation = root / 'generations' / ident
    generation.mkdir(mode=0o700)
    copied = copy_tree(folder / 'server', generation / 'server')
    require(copied == manifest, 'snapshot changed while resetting; active generation unchanged')
    checked_snapshot(root, name)
    previous, _ = active(root)
    atomic_json(root / 'active.json', {'generation': ident})
    return {'reset_from': name, 'generation': ident, 'preserved_generation': previous,
            'verified': True}


def verify(root, name):
    stopped(root)
    _, manifest = checked_snapshot(root, name)
    ident, server = active(root)
    require(tree_manifest(server) == manifest, 'active generation differs from snapshot')
    return {'snapshot': name, 'generation': ident, 'snapshot_verified': True,
            'active_matches_snapshot': True, 'entries': len(manifest)}


def plain_properties(path):
    regular(path)
    result = {}
    for line in path.read_text(encoding='iso-8859-1').splitlines():
        line = line.strip()
        if not line or line.startswith(('#', '!')):
            continue
        # Java properties escaping/continuations are deliberately not guessed.
        require('\\' not in line and '=' in line, 'use plain unescaped key=value properties')
        key, value = (s.strip() for s in line.split('=', 1))
        require(key not in result, 'duplicate properties key refused')
        result[key] = value
    return result


def canonical_identity(entry):
    require(isinstance(entry, dict) and isinstance(entry.get('uuid'), str)
            and isinstance(entry.get('name'), str), 'identity requires UUID and name strings')
    parsed = uuid.UUID(entry['uuid'])
    require(str(parsed) == entry['uuid'] and parsed.int != 0,
            'identity UUID must be canonical lowercase hyphenated and nonzero')
    require(re.fullmatch(r'[A-Za-z0-9_]{1,16}', entry['name']), 'invalid Minecraft identity name')


def server_properties(server):
    result = plain_properties(server / 'server.properties')
    require(result.get('online-mode') == 'true' and result.get('white-list') == 'true',
            'startup requires online-mode=true and white-list=true (no offline bypass)')
    require(result.get('server-ip') == '127.0.0.1', 'startup requires server-ip=127.0.0.1')
    require(result.get('max-players') == '1' and result.get('enable-rcon') == 'false'
            and result.get('enable-query') == 'false',
            'startup requires max-players=1 enable-rcon=false enable-query=false')
    require(plain_properties(server / 'eula.txt').get('eula') == 'true',
            'startup requires manually accepted eula=true; harness never accepts it')
    whitelist = read_json(server / 'whitelist.json')
    require(isinstance(whitelist, list) and len(whitelist) == 1,
            'manually prepare exactly one whitelist identity offline')
    canonical_identity(whitelist[0])
    require(set(whitelist[0]) == {'uuid', 'name'}, 'whitelist entry must contain only uuid/name')
    ops = read_json(server / 'ops.json')
    require(isinstance(ops, list) and len(ops) <= 1, 'ops must be empty or the sole whitelist identity')
    if ops:
        canonical_identity(ops[0])
        require(all(ops[0][key] == whitelist[0][key] for key in ('uuid', 'name')),
                'ops must match the sole whitelist UUID/name')
        require(set(ops[0]) <= {'uuid', 'name', 'level', 'bypassesPlayerLimit'},
                'unknown ops identity fields refused')
        require('level' not in ops[0] or (type(ops[0]['level']) is int and 1 <= ops[0]['level'] <= 4),
                'invalid ops level')
        require('bypassesPlayerLimit' not in ops[0] or type(ops[0]['bypassesPlayerLimit']) is bool,
                'invalid ops bypassesPlayerLimit')
    world = result.get('level-name', 'world')
    require(world and not Path(world).is_absolute() and '..' not in Path(world).parts
            and str(Path(world)) == world and world != '.',
            'level-name must stay inside the active server directory')
    port = int(result.get('server-port', '25565'))
    require(1 <= port <= 65535, 'invalid server-port')
    return port


def port_open(port):
    try:
        with socket.create_connection(('127.0.0.1', port), timeout=0.2):
            return True
    except OSError:
        return False


def owned_port(identity, port):
    """A different process grabbing the requested port cannot prove readiness."""
    if not alive(identity):
        return False
    try:
        proc = Path('/proc', str(identity['pid']))
        sockets = set()
        for fd in (proc / 'fd').iterdir():
            try:
                target = os.readlink(fd)
                if target.startswith('socket:['):
                    sockets.add(target[8:-1])
            except FileNotFoundError:
                continue
        for table in ('tcp', 'tcp6'):
            for line in (proc / 'net' / table).read_text().splitlines()[1:]:
                fields = line.split()
                if fields[3] == '0A' and int(fields[1].split(':')[1], 16) == port and fields[9] in sockets:
                    return alive(identity) and port_open(port)
    except (OSError, ValueError, IndexError):
        return False  # Unknown ownership is never readiness.
    return False


def heap_mib(value):
    match = re.fullmatch(r'([1-9][0-9]*)([mMgG])', value)
    if not match:
        raise argparse.ArgumentTypeError('heap must be a positive integer with M or G suffix')
    return int(match[1]) * (1024 if match[2].lower() == 'g' else 1)


def launch_config(args, root):
    require(not any(os.environ.get(key) for key in
                    ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')),
            'ambient Java option variables refused; use the reviewed argument file')
    generation, server = active(root)
    tree_manifest(server)
    java = absolute(args.java)
    regular(java)
    require(os.access(java, os.X_OK), 'Java executable is not executable')
    jar = Path(args.jar)
    require(not jar.is_absolute() and '..' not in jar.parts and str(jar) == args.jar
            and jar.suffix == '.jar', '--jar must be a safe relative .jar path')
    regular(server / jar)
    java_args_file = args.java_args_file
    if java_args_file is not None:
        relative = Path(java_args_file)
        require(java_args_file and not relative.is_absolute() and '..' not in relative.parts
                and str(relative) == java_args_file and java_args_file != '.',
                '--java-args-file must be an exact safe relative file path')
        for component in (server / relative, *(server / relative).parents):
            if component == server:
                break
            require(not component.is_symlink(), 'symlink argument file path refused')
        regular(server / relative)
    require(args.xms <= args.xmx, '-Xms must not exceed -Xmx')
    require(args.reserve_mib > 0 and math.isfinite(args.boot_timeout)
            and math.isfinite(args.stall_timeout) and args.boot_timeout > 0 and args.stall_timeout > 0
            and 4096 <= args.log_bytes <= 32 * 1024 * 1024, 'invalid lifecycle budget')
    port = server_properties(server)
    require(not port_open(port), 'server port is already accepting connections')
    with socket.socket() as probe:
        try:
            probe.bind(('127.0.0.1', port))
        except OSError:
            raise Refused('server port is not available') from None
    return {'generation': generation, 'java': str(java), 'jar': str(jar),
            'java_args_file': java_args_file,
            'xms_mib': args.xms, 'xmx_mib': args.xmx, 'reserve_mib': args.reserve_mib,
            'port': port, 'boot_timeout': args.boot_timeout, 'stall_timeout': args.stall_timeout,
            'log_bytes': args.log_bytes, 'block_exe': args.block_exe}


def console_stop(run, state):
    require(state.get('server') and alive(state['server']), 'tracked server identity is not running')
    fifo = run / 'console.fifo'
    s = fifo.lstat()
    require(stat.S_ISFIFO(s.st_mode) and s.st_uid == os.getuid()
            and not s.st_mode & 0o077 and s.st_nlink == 1, 'unsafe console FIFO')
    fd = os.open(fifo, os.O_WRONLY | os.O_NONBLOCK | os.O_NOFOLLOW)
    try:
        require(alive(state['server']), 'server identity changed before console stop')
        os.write(fd, b'stop\n')
    finally:
        os.close(fd)


def supervise(root, run, config, lock_fd):
    """Supervisor retains no lifecycle lock. FIFO also survives supervisor death.

    A gate prevents the child exec until its PID/starttime has been durably saved.
    If the record cannot be written, closing the gate makes the child exit without
    executing Java. Once released, the private FIFO is the independent stop path.
    """
    os.close(lock_fd)
    os.setsid()
    null = os.open(os.devnull, os.O_RDWR)
    for fd in (0, 1, 2):
        os.dup2(null, fd)
    if null > 2:
        os.close(null)
    state = read_json(run / 'state.json')
    state['supervisor'] = proc_identity(os.getpid())
    atomic_json(run / 'state.json', state)
    gate_r, gate_w = os.pipe()
    output_r, output_w = os.pipe()
    # Keep a writer on the child's own stdin descriptor. If the supervisor dies,
    # the console must not see EOF and abandon the only graceful-stop reader.
    anchor = os.open(run / 'console.fifo', os.O_RDWR | os.O_NONBLOCK)
    console = os.open(run / 'console.fifo', os.O_RDWR)
    start = time.monotonic()
    pid = os.fork()
    if pid == 0:
        try:
            os.close(gate_w)
            os.close(output_r)
            os.close(anchor)
            if os.read(gate_r, 1) != b'1':
                os._exit(125)
            os.close(gate_r)
            os.dup2(console, 0)
            os.dup2(output_w, 1)
            os.dup2(output_w, 2)
            if console > 2:
                os.close(console)
            if output_w > 2:
                os.close(output_w)
            os.chdir(root / 'generations' / config['generation'] / 'server')
            argv = [config['java']]
            if config['java_args_file'] is not None:
                argv.append('@' + config['java_args_file'])
            argv.extend(['-Xms%dm' % config['xms_mib'], '-Xmx%dm' % config['xmx_mib']])
            os.execv(config['java'], argv + ['-jar', config['jar'], 'nogui'])
        except BaseException:
            os._exit(126)
    os.close(gate_r)
    os.close(output_w)
    os.close(console)
    state.update(server=proc_identity(pid), phase='booting', done_marker=False,
                 ready=False, boot_seconds=None, failure=None, log_captured_bytes=0,
                 log_dropped_bytes=0, log_tail_captured_bytes=0, log_tail_dropped_bytes=0)
    try:
        require(state['server'] is not None, 'child vanished before launch record')
        atomic_json(run / 'state.json', state)
        os.write(gate_w, b'1')
    finally:
        os.close(gate_w)
    os.set_blocking(output_r, False)
    last_output, last_save = start, 0
    window = b''
    tail = b''
    log_fd = os.open(run / 'console.log', os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    tail_fd = os.open(run / 'console-tail.log', os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(log_fd, 'wb', buffering=0) as log, os.fdopen(tail_fd, 'wb', buffering=0) as tail_log:
        def capture(chunk):
            nonlocal last_output, window, tail
            last_output = time.monotonic()
            window = (window + chunk)[-131072:]
            state['done_marker'] = state['done_marker'] or bool(DONE.search(window))
            remaining = config['log_bytes'] - state['log_captured_bytes']
            captured = chunk[:max(0, remaining)]
            log.write(captured)
            state['log_captured_bytes'] += len(captured)
            state['log_dropped_bytes'] += len(chunk) - len(captured)
            tail = (tail + chunk)[-config['log_bytes']:]
            state['log_tail_captured_bytes'] = len(tail)
            state['log_tail_dropped_bytes'] = (state['log_captured_bytes']
                                               + state['log_dropped_bytes'] - len(tail))

        def save_tail():
            tail_log.seek(0)
            tail_log.write(tail)
            tail_log.truncate()

        while True:
            now = time.monotonic()
            readable, _, _ = select.select([output_r], [], [], 0.1)
            if readable:
                chunk = os.read(output_r, 65536)
                if chunk:
                    capture(chunk)
                else:
                    # A live process can close stdout: avoid busy-looping on EOF.
                    time.sleep(0.1)
            finished, status = os.waitpid(pid, os.WNOHANG)
            if finished:
                # Drain bytes already buffered at exit; never wait for descendants.
                while True:
                    try:
                        chunk = os.read(output_r, 65536)
                    except BlockingIOError:
                        break
                    if not chunk:
                        break
                    capture(chunk)
                save_tail()
                os.fsync(log.fileno())
                os.fsync(tail_log.fileno())
                state.update(phase='exited', ready=False, exit_code=os.waitstatus_to_exitcode(status))
                if state['boot_seconds'] is None and not state['failure']:
                    state['failure'] = 'server exited before readiness'
                atomic_json(run / 'state.json', state)
                break
            if state['phase'] == 'booting':
                if state['done_marker'] and owned_port(state['server'], config['port']):
                    state.update(phase='ready', ready=True, boot_seconds=round(time.monotonic() - start, 3))
                elif now - start >= config['boot_timeout'] or now - last_output >= config['stall_timeout']:
                    state.update(phase='failed', failure='boot timeout' if now - start >= config['boot_timeout']
                                 else 'boot log stall timeout')
            if now - last_save >= 0.2:
                save_tail()
                atomic_json(run / 'state.json', state)
                last_save = now
    os.close(output_r)
    os.close(anchor)


def start_server(args, root, lock_fd):
    stopped(root)
    config = launch_config(args, root)
    budget = guard(args.idle_confirmed, args.xmx + args.reserve_mib, args.block_exe)
    ident = uuid.uuid4().hex
    run = root / 'runs' / ident
    run.mkdir(mode=0o700)
    os.mkfifo(run / 'console.fifo', 0o600)
    atomic_json(run / 'state.json', {'phase': 'launching', 'config': config,
                                   'server': None, 'supervisor': None, 'precheck': budget})
    atomic_json(root / 'current-run.json', {'run': ident})
    pid = os.fork()
    if pid == 0:
        try:
            supervise(root, run, config, lock_fd)
        except BaseException:
            # A launched server keeps its independent FIFO and recorded identity.
            # Do not kill, erase a handle, or silently start a second server.
            os._exit(1)
        os._exit(0)
    # The launcher waits for readiness but never owns the server's stdin pipe.
    deadline = time.monotonic() + args.boot_timeout + 10
    while time.monotonic() < deadline:
        state = read_json(run / 'state.json')
        if state['phase'] == 'ready':
            return health(root)
        if state['phase'] in ('failed', 'exited'):
            raise Refused('startup failed: ' + str(state.get('failure')) + '; run handle retained; use health/stop')
        finished, _ = os.waitpid(pid, os.WNOHANG)
        if finished:
            raise Refused('supervisor exited; run handle retained; use health/stop; no automatic restart')
        time.sleep(0.1)
    raise Refused('startup observation timeout; run handle retained; use health/stop')


def health(root):
    run, state = run_record(root)
    if state is None:
        return {'running': False, 'ready': False, 'phase': 'never-started'}
    running = bool(state.get('server') and alive(state['server']))
    supervising = bool(state.get('supervisor') and alive(state['supervisor']))
    config = state['config']
    accepting = running and owned_port(state['server'], config['port'])
    return {'run': run.name, 'phase': state['phase'], 'running': running,
            'supervisor_running': supervising,
            'ready': bool(running and supervising and state.get('ready') and accepting),
            'done_marker': state.get('done_marker', False), 'port_open': bool(accepting),
            'boot_seconds': state.get('boot_seconds'), 'configured_xms_mib': config['xms_mib'],
            'configured_xmx_mib': config['xmx_mib'], 'required_heap_mib': None,
            'heap_note': 'configured budget only; minimum required heap is not measured',
            'failure': state.get('failure'), 'exit_code': state.get('exit_code'),
            'log_captured_bytes': state.get('log_captured_bytes', 0),
            'log_dropped_bytes': state.get('log_dropped_bytes', 0),
            'log_tail_captured_bytes': state.get('log_tail_captured_bytes', 0),
            'log_tail_dropped_bytes': state.get('log_tail_dropped_bytes', 0)}


def stop_server(args, root):
    run, state = run_record(root)
    require(state is not None and state.get('server'), 'no tracked server handle')
    require(alive(state['server']), 'stale/stopped server identity; no console command sent')
    config = state['config']
    guard(args.idle_confirmed, config['reserve_mib'],
          list(set(config['block_exe'] + args.block_exe)),
          [state.get('server'), state.get('supervisor')])
    console_stop(run, state)
    deadline = time.monotonic() + args.stop_timeout
    while time.monotonic() < deadline:
        server_alive = alive(state['server'])
        supervisor_alive = bool(state.get('supervisor') and alive(state['supervisor']))
        if not server_alive and not supervisor_alive:
            final = read_json(run / 'state.json')
            clean = (clean_exit(final) and all(final.get(k) == state.get(k)
                                             for k in ('server', 'supervisor')))
            if clean:
                # Only this parent knows it actually requested the console stop.
                # Never synthesize an exit status after supervisor failure.
                atomic_json(run / 'graceful-stop.json', stop_receipt(run, final))
            return {'stopped': True, 'forced': False, 'run': run.name, 'clean': clean}
        time.sleep(0.1)
    raise Refused('graceful stop timed out; handle retained, no forced kill or clean receipt; '
                  'use health and retry stop only while the tracked server remains alive')


def parser():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest='command', required=True)
    for name, help_text in (
        ('init', 'read-only import of an acknowledged, stopped disposable source into a NEW root'),
        ('preflight', 'read-only start checks (no server launch)'),
        ('snapshot', 'copy the full stopped server into a new named snapshot'),
        ('reset', 'verify snapshot, copy to a NEW generation, atomically switch active marker'),
        ('start', 'guarded start; wait for current-run Done marker AND loopback port'),
        ('stop', 'guarded graceful console stop only; never kill'),
        ('health', 'read tracked identity/readiness, boot time and configured heap budget'),
        ('verify', 'verify snapshot SHA256 and exact stopped active-server content')):
        s = sub.add_parser(name, help=help_text, description=help_text)
        s.add_argument('--root', required=True, help='absolute managed root (new nonexistent directory for init)')
        if name == 'init':
            s.add_argument('--source', required=True, help='absolute stopped disposable source; never a real/offline game world')
            s.add_argument('--ack-disposable', action='store_true', help='acknowledge independent operator source check; not proof of safety')
        if name in ('snapshot', 'reset', 'verify'):
            s.add_argument('name', metavar='NAME', help='new/existing snapshot name, 1-64 safe ASCII characters')
        if name in ('start', 'preflight', 'stop'):
            s.add_argument('--idle-confirmed', action='store_true', help='operator confirms idle window; automatic checks still enforced')
            s.add_argument('--block-exe', action='append', default=[], metavar='BASENAME',
                           help='additional exact case-sensitive blocked comm/exe basename (repeatable)')
        if name in ('start', 'preflight'):
            s.add_argument('--java', required=True, help='explicit absolute executable path, no symlink components')
            s.add_argument('--jar', required=True, help='relative server .jar within the active generation')
            s.add_argument('--java-args-file', metavar='RELATIVE',
                           help='trusted pre-existing copied JVM options file, e.g. java9args.txt; passed before explicit heap options and -jar; never a shell or sandbox')
            s.add_argument('--xms', type=heap_mib, default='1G', metavar='SIZE', help='initial heap, integer M/G (default: 1G)')
            s.add_argument('--xmx', type=heap_mib, default='6G', metavar='SIZE', help='maximum heap, integer M/G (default: 6G)')
            s.add_argument('--reserve-mib', type=int, default=2048, help='MemAvailable headroom beyond Xmx; stop needs reserve only (default: 2048)')
            s.add_argument('--boot-timeout', type=float, default=600, help='readiness deadline seconds (default: 600)')
            s.add_argument('--stall-timeout', type=float, default=120, help='pre-ready console silence deadline seconds (default: 120)')
            s.add_argument('--log-bytes', type=int, default=1048576, help='each current-run console head/tail cap, 4096..33554432 (default: 1048576)')
        if name == 'stop':
            s.add_argument('--stop-timeout', type=float, default=120, help='graceful-stop deadline seconds; never escalates (default: 120)')
    return p


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        require(sys.platform.startswith('linux'), 'Linux /proc and FIFO support required')
        if args.command == 'init':
            result = initialize(args)
        else:
            root = managed(args.root)
            with locked(root) as lock_fd:
                if args.command == 'preflight':
                    stopped(root)
                    config = launch_config(args, root)
                    result = {'preflight': guard(args.idle_confirmed, args.xmx + args.reserve_mib, args.block_exe),
                              'configured_xms_mib': config['xms_mib'], 'configured_xmx_mib': config['xmx_mib'],
                              'required_heap_mib': None}
                elif args.command == 'start':
                    result = start_server(args, root, lock_fd)
                elif args.command == 'stop':
                    require(math.isfinite(args.stop_timeout) and args.stop_timeout > 0,
                            'stop-timeout must be finite and positive')
                    result = stop_server(args, root)
                elif args.command == 'health':
                    result = health(root)
                else:
                    result = {'snapshot': snapshot, 'reset': reset, 'verify': verify}[args.command](root, args.name)
        print(json.dumps({'ok': True, **result}, sort_keys=True))
        return 0
    except (Refused, OSError, ValueError, KeyError, TypeError) as exc:
        # Raw application logs/argv/environment are never printed here.
        print(json.dumps({'ok': False, 'error': str(exc)}, sort_keys=True), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
