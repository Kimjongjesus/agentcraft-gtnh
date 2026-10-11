"""File-system safety checks (open question 9): ownership, modes, and "not inside the git checkout"."""

from __future__ import annotations

import os
import stat
from pathlib import Path


class UnsafePath(Exception):
    """A policy / key / state path that the service must not trust. The message is safe to print."""


def repo_root() -> Path | None:
    """The git checkout this package runs from, if any (same rule as the adapter's journal)."""
    here = Path(__file__).resolve()
    for parent in here.parents:
        if (parent / ".git").exists():
            return parent
    return None


def refuse_in_repo(path: Path, what: str, allow_in_repo: bool = False) -> Path:
    resolved = Path(path).expanduser().resolve()
    root = repo_root()
    if root is not None and not allow_in_repo and (resolved == root or root in resolved.parents):
        raise UnsafePath(f"refusing to keep the {what} inside the git checkout")
    return resolved


def check_file(path: Path, what: str, private: bool = False) -> None:
    """Regular file (no symlink), owned by the running uid, not group/other-writable.

    ``private`` (key files): additionally not group/other-readable.
    """
    try:
        st = os.lstat(path)
    except OSError as e:
        raise UnsafePath(f"{what}: cannot stat ({e.strerror or type(e).__name__})") from None
    if stat.S_ISLNK(st.st_mode):
        raise UnsafePath(f"{what}: is a symlink")
    if not stat.S_ISREG(st.st_mode):
        raise UnsafePath(f"{what}: not a regular file")
    if st.st_uid != os.getuid():
        raise UnsafePath(f"{what}: not owned by the running user")
    bad = 0o077 if private else 0o022
    if st.st_mode & bad:
        raise UnsafePath(f"{what}: mode {stat.S_IMODE(st.st_mode):04o} is too open (need no {'group/other access' if private else 'group/other write'})")


def check_dir(path: Path, what: str) -> None:
    """Directory owned by the running uid and not group/other-writable."""
    try:
        st = os.lstat(path)
    except OSError as e:
        raise UnsafePath(f"{what}: cannot stat ({e.strerror or type(e).__name__})") from None
    if stat.S_ISLNK(st.st_mode) or not stat.S_ISDIR(st.st_mode):
        raise UnsafePath(f"{what}: not a directory")
    if st.st_uid != os.getuid():
        raise UnsafePath(f"{what}: not owned by the running user")
    if st.st_mode & 0o022:
        raise UnsafePath(f"{what}: mode {stat.S_IMODE(st.st_mode):04o} is group/other-writable")


def ensure_private_dir(path: Path, what: str) -> None:
    """Create ``path`` (0700) if missing, then require it to be safe."""
    p = Path(path)
    if not os.path.lexists(p):
        os.makedirs(p, mode=0o700, exist_ok=True)
    check_dir(p, what)
