"""A reference ops.* receiver: applies ops.snapshot / ops.*.upsert / ops.remove to local state.

This is the client half of docs/ops-protocol.md in ~40 lines (the GTNH mod's card-5b OpsSync
follows the same rules in Java). Tests use it to prove that a snapshot plus the incremental
messages always reconstruct exactly the adapter's model; tools can use it to show live state.
"""

from __future__ import annotations

from typing import Any

from .ops import KINDS, PLURAL

ENTITY_KINDS = ("source",) + KINDS


class OpsMirror:
    def __init__(self) -> None:
        self.ready = False  # an ops.snapshot arrived on this connection
        self.state: dict[str, dict[str, dict[str, Any]]] = {k: {} for k in ENTITY_KINDS}

    def apply(self, msg: dict[str, Any]) -> bool:
        """Apply one message; False if it is not an ops.* message this mirror understands."""
        t = msg.get("type")
        if t == "ops.snapshot":
            for kind in ENTITY_KINDS:
                self.state[kind] = {e["id"]: e for e in msg.get(PLURAL[kind], []) if isinstance(e, dict) and "id" in e}
            self.ready = True
            return True
        if t == "ops.remove":
            self.state.get(msg.get("kind"), {}).pop(msg.get("id"), None)
            return True
        if isinstance(t, str) and t.startswith("ops.") and t.endswith(".upsert"):
            kind = t[4:-7]
            e = msg.get(kind)
            if kind in self.state and isinstance(e, dict) and "id" in e:
                self.state[kind][e["id"]] = e  # an upsert replaces the whole entity
                return True
        return False

    def as_model(self) -> dict[str, list[dict[str, Any]]]:
        """Same shape as OpsHub.model(), ordered by id (the client re-sorts for display anyway)."""
        return {PLURAL[k]: [self.state[k][i] for i in sorted(self.state[k])] for k in ENTITY_KINDS}
