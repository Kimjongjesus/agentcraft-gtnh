"""A generic mock ops source: fixture data for demos and tests.

Nothing here describes a real installation: hosts are ``host-a``..``host-c``, services
``service-1``.., providers ``provider-a``/``provider-b`` and every address is an RFC 5737
documentation address (192.0.2.0/24). Some strings deliberately carry an address, a credential URL
or a token so a demo shows the adapter's privacy filter at work.

The data moves through an 8-step cycle (one step per collect), so a connected client sees real
incremental ``ops.*`` upserts: host-c and service-3 go down at step 3 and recover at step 6 (with a
matching critical alert that opens and then resolves), CPU and job runs tick, usage drains and
resets. Timestamps hang off a fixed anchor (the first collect), so an unchanged entity stays
byte-identical between collects and produces no message.
"""

from __future__ import annotations

import time
from typing import Any, Callable

CYCLE = 8
DOWN_FROM, UP_AT = 3, 6  # down during phases 3, 4, 5; recovered from phase 6


class MockOpsSource:
    id = "mock"
    name = "Mock ops feed"

    def __init__(self, interval: float = 10.0, clock: Callable[[], float] = time.time, start_step: int = 0) -> None:
        self.interval = float(interval)
        self.timeout = 5.0
        self.clock = clock
        self.step = int(start_step)
        self.anchor: float | None = None

    def collect(self) -> dict[str, Any]:
        now = self.clock()
        if self.anchor is None:
            self.anchor = float(int(now))
        step = self.step
        self.step += 1
        return build(step, self.anchor, self.interval)

    def close(self) -> None:
        return None


def build(step: int, anchor: float, interval: float = 10.0) -> dict[str, Any]:
    """The fixture batch for ``step`` (pure: same inputs, same output)."""
    phase, cycle = step % CYCLE, step // CYCLE
    cycle_start = anchor + cycle * CYCLE * interval
    t = anchor + step * interval  # "now" of this step
    down = DOWN_FROM <= phase < UP_AT

    def outage(c: int) -> tuple[float, float]:  # (went down, recovered) of cycle c
        start = anchor + c * CYCLE * interval
        return start + DOWN_FROM * interval, start + UP_AT * interval

    if down:
        since = outage(cycle)[0]
    elif phase >= UP_AT:
        since = outage(cycle)[1]
    elif cycle > 0:
        since = outage(cycle - 1)[1]
    else:
        since = anchor - 3 * 86400

    services = [
        {"id": "host-a", "name": "host-a", "group": "hosts", "state": "up", "since": anchor - 3 * 86400,
         "detail": f"load {0.2 + 0.1 * (phase % 4):.1f}", "cpu": 20 + 5 * (phase % 4), "mem": 41.5, "disk": 55},
        {"id": "host-b", "name": "host-b", "group": "hosts", "state": "degraded", "since": anchor - 7200,
         "detail": "disk 91% on /data", "cpu": 12, "mem": 63, "disk": 91},
        {"id": "host-c", "name": "host-c", "group": "hosts", "state": "down" if down else "up", "since": since,
         "detail": "no answer from 192.0.2.30 for 2 checks" if down else "load 0.1",
         **({} if down else {"cpu": 4, "mem": 22, "disk": 31})},
        {"id": "service-1", "name": "service-1", "group": "services", "state": "up", "detail": "HTTP 200 via proxy"},
        {"id": "service-2", "name": "service-2", "group": "services", "state": "up", "detail": "HTTP 200 via proxy"},
        {"id": "service-3", "name": "service-3", "group": "services", "state": "down" if down else "up", "since": since,
         "detail": "probe of http://192.0.2.31:8080/health timed out" if down else "HTTP 200 via proxy"},
        {"id": "desktop-1", "name": "desktop-1", "group": "desktops", "state": "unknown",
         "detail": "no report for 3h (may be powered off)"},
    ]
    jobs = [
        {"id": "job-1", "name": "job-1", "schedule": "every 5m", "lastRun": t - 20, "lastStatus": "ok",
         "nextRun": t + 280, "durationMs": 1200 + 100 * phase},
        {"id": "job-2", "name": "job-2", "schedule": "daily 06:30", "lastRun": anchor - 3 * 3600, "lastStatus": "failed",
         "nextRun": anchor + 21 * 3600, "durationMs": 30_000, "detail": "connect 192.0.2.15:443 timed out"},
        {"id": "job-3", "name": "job-3", "schedule": "every 1h", "lastRun": cycle_start,
         "lastStatus": "running" if phase in (2, 6) else "ok", "nextRun": cycle_start + 3600, "durationMs": 64_000},
        {"id": "job-4", "name": "job-4", "schedule": "weekly mon 09:00", "enabled": False, "lastStatus": "ok",
         "lastRun": anchor - 5 * 86400, "detail": "paused"},
    ]
    usage = [
        {"id": "provider-a-session", "provider": "provider-a", "window": "session",
         "remainingPct": 100 - 12 * phase, "resetsAt": cycle_start + CYCLE * interval},
        {"id": "provider-a-week", "provider": "provider-a", "window": "week", "remainingPct": 58 - phase,
         "resetsAt": anchor + 4 * 86400},
        {"id": "provider-b-credit", "provider": "provider-b", "window": "credit", "remainingPct": 46.3,
         "detail": "$4.63 of $10.00"},
    ]
    alerts = [
        {"id": "host-b-disk", "ts": anchor - 7200, "severity": "warn", "source": "metrics",
         "title": "disk 91% on host-b", "state": "open"},
        {"id": "host-a-updates", "ts": anchor - 86400, "severity": "info", "source": "updates",
         "title": "packages updated on host-a", "state": "resolved", "resolvedAt": anchor - 86400},
        {"id": "webhook-failed", "ts": anchor - 600, "severity": "warn", "source": "notifier",
         "title": "alert delivery failed",
         "detail": "POST https://bot:pa55word@192.0.2.40/hook?token=abc123 returned 500", "state": "open"},
    ]
    # the service-3 outage: open while down; afterwards (and until the next one) shown as resolved
    shown = cycle if phase >= DOWN_FROM else cycle - 1
    if shown >= 0:
        went_down, recovered = outage(shown)
        open_now = shown == cycle and down
        alerts.insert(0, {
            "id": f"service-3-down-{shown}", "ts": went_down, "severity": "critical", "source": "probe",
            "title": "service-3 DOWN", "detail": "probe of http://192.0.2.31:8080/health timed out",
            "state": "open" if open_now else "resolved", **({} if open_now else {"resolvedAt": recovered}),
        })
    return {"services": services, "jobs": jobs, "usage": usage, "alerts": alerts}
