# Card 8 — read-only dashboard candidate

Status: built and verified locally; awaiting owner demo acceptance and the ordered independent review. Not merged, pushed, installed as a service, or deployed to production.

## What was built

- Optional Python-standard-library HTTP listener alongside the adapter. `--dashboard` enables it on loopback port 8787; a specific non-loopback address additionally requires `--dashboard-allow-lan`.
- Warm Studio interface with bundled Nunito and JetBrains Mono fonts: agent states, board summaries and bounded task columns, open decisions, fleet, scheduled jobs, provider usage, alerts, and source freshness.
- The dashboard consumes the existing mapped snapshots, not a second source reader. Its JSON boundary applies the existing whole-string privacy filter again. It has no world telemetry, file browser, write endpoint, or command interface.
- Exact GET routes, exact Host authority checks, same-origin browser policy, CSP, no CORS, no-store responses, capped clients/headers/output and request/output deadlines. Other methods, including HEAD, are refused.
- Polling, manual refresh and page-local pause; stale/error states preserve the last good view. Empty sources and unknown usage are not presented as healthy or unlimited.
- Existing game WebSocket behavior remains unchanged. The only shared integration change is an import, optional flags, validation and listener lifecycle in `hermes_adapter/__main__.py`. No changes to the parallel write module or protocol definitions.

## Verification

Run from `hermes-adapter/`:

    python3 -m unittest discover -s tests
    node --check hermes_adapter/dashboard_static/dashboard.js
    node --test tests/test_dashboard_js.js
    git diff --check

Observed final execution:

- Python: `Ran 272 tests in 43.075s`, `OK`, exit 0. Includes 26 new dashboard boundary/lifecycle tests, IPv6 loopback and concurrent WebSocket read-only compatibility.
- JavaScript: eight tests passed, zero failed; syntax check exit 0. Node is test-only: the adapter has no new runtime dependencies.
- Eleven live HTTP checks passed for routes, methods, Host/Origin rejection and response headers. Synthetic fixture file hashes were unchanged by those checks.
- Chromium: all required panels rendered; all three local font faces loaded; no page-wide horizontal overflow at 320, 390 and 1440 CSS pixels. Browser error/CSP capture was empty on the normal page load.
- Verified failed-refresh retention, recovery and page-local pause. A page-local synthetic response stub verified empty states and rendering an HTML-looking agent name as text, without creating an image element. The live preview source was not altered by that stub.
- Synthetic history confirmed main-board totals of 43 done all-time versus three done in the recent three-day window, with both labels visible.
- Public-content scan and whitespace checks passed. No runtime fixture, evidence directory, real board data, private address, or installation path is included in this commit.

Private demo evidence is deliberately outside the checkout: `verification.txt`, `browser-checks.json`, `http-checks.json`, plus `desktop.png`, `boards.png`, `operations.png`, `schedule-usage.png`, `mobile.png`, and `stale.png`. The task handoff carries the actual preview URL and evidence locations. The preview uses only the existing synthetic HQ fixture plus mock ops source.

## Risks and limitations

- No authentication or TLS by design. Every device that can reach an explicitly exposed listener can read the filtered data. Redaction is not full anonymization: ordinary task titles and operational details remain visible. Use a trusted network only.
- `--allow-ip-text` intentionally affects the dashboard as well as the game clients. The safe default remains IP-text redaction.
- Source freshness and adapter freshness are separate; an active adapter does not prove every underlying service is current. The interface labels retained and unknown source data.
- The UI intentionally caps display rows and reports omissions. Recent task cards are not the all-time history. Cancelled cards are hidden.
- Static assets are loaded at listener startup. Restart only the isolated preview after asset edits; a browser refresh alone does not replace those cached server bytes.
- The adapter checkout must retain the mod font resource directory. Missing assets fail closed with an unavailable response.
- Browser verification used local Chromium and emulated mobile viewport sizes, not a physical phone, external LAN client, screen reader, or additional browser engine. No game/world test was needed for these HTTP-only changes. No load-soak or integration with the parallel write-path branch was performed.
- Required workflow test/build timing commands were attempted but refused by a delegated-child classification in the shell environment. That guard was not bypassed. This affects timing metadata, not the real test results above.

## Decisions still required

1. Owner accepts the synthetic UI demo or requests changes.
2. Intake orders the independent review of this exact clean committed candidate.
3. Owner chooses whether to merge or leave the branch. Any live deployment, real-source exposure, firewall/proxy change, or service installation is a separate approval; none is implied by this candidate.
