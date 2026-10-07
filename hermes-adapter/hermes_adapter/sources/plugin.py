"""Source-plugin interface for ``world.*`` sources (card G1: the factory telemetry source).

World sources use the **same plugin shape and the same loader** as the ``ops.*`` sources of card
5a (``ops.py``, ``../../OPS-PLUGINS.md``); only what ``collect()`` returns differs (a world source
returns the telemetry dict that ``world.py`` normalises, an ops source returns services, jobs,
usage and alerts). The two feeds stay separate: a world source is never loaded as an ops source or
the other way round (separate CLI flags and a separate entry-point group).

A source is any object with:

* ``id`` (str): short stable id (``"factory"``); prefixes everything the source produces.
* ``name`` (str, optional): display name.
* ``interval`` (seconds, optional): time between collects.
* ``timeout`` (seconds, optional): longest a collect may take.
* ``collect()`` -> a plain dict. Called in a worker thread. It must not write anything anywhere:
  sources are read-only by contract, the adapter only reads what they return.
* ``close()`` (optional): called once when the adapter stops.

A plugin is a ``.py`` file, a package directory, ``package.module[:factory]`` or ``ep:<name>``
(entry-point group ``agentcraft_gtnh.world_sources``) with ``create_sources(config)`` returning one
source or a list of them (or a module-level ``SOURCES`` list), exactly as for ops plugins.
"""

from __future__ import annotations

from typing import Any, Callable, Mapping

from .. import ops

ENTRY_POINT_GROUP = "agentcraft_gtnh.world_sources"
KIND = "world"


class SourcePlugin:
    """Base class (optional: any object with these attributes works)."""

    id = "source"
    name = ""
    interval = 60.0
    timeout = 30.0

    def collect(self) -> Mapping[str, Any]:  # pragma: no cover - interface
        raise NotImplementedError

    def close(self) -> None:
        return None


def resolve_factory(spec: str) -> Callable[[dict[str, Any]], Any]:
    """``spec`` -> world plugin factory (the shared ops loader, world entry-point group)."""
    return ops.resolve_factory(spec, kind=KIND, group=ENTRY_POINT_GROUP)


def load_plugin(spec: str, config: Mapping[str, Any] | None = None) -> list[Any]:
    """Load one world plugin and return its sources (validated for the minimal interface)."""
    return ops.load_plugin(spec, config, kind=KIND, group=ENTRY_POINT_GROUP)
