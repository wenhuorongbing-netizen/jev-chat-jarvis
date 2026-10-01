# PO R13 — JEV-S6 implementation ruling: CORRECT (architecture accepted, S6 not yet closed)

Condensed archive of the PO reply to `_reports/S6_confirm_first_report.md` (Android `b75290b`).

## Verdict
- S6 architecture / security: **ACCEPTED**. Zero egress before the tap PASS; stale-click and lifecycle PASS; no second image path PASS; no Windows change.
- S6 implementation: **CORRECT** — only an evidence allow-list fix plus a Redmi runtime UI closure remain.
- **JEV-S6.1-RUNTIME-CLOSURE = AUTHORIZED.**

## Required
1. Presence proof becomes an evidence allow-list `{"在线"}` — the only text seen on a real device. Delete `online / 最后上线 / last seen / zuletzt online / 正在输入 / typing`. Extend only after each is sampled on a device (node, group counterpart, UI structure).
2. Full test suite stays green.
3. Redmi back on adb; with a controllable test account/contact send one incoming, non-sensitive picture; verify: hint appears on its own; label fully readable; not clipped by the screen's right edge; hidden while the panel is expanded; tap → hint disappears → original S5 multimodal reply → candidates; no auto fill/send.
4. Small negative smoke: own picture, or image switch off → no hint.
5. If the pill is clipped on device: fix layout only, minimal change (e.g. clamp the hint to screen bounds, with a pure geometry test); do not rebuild OverlayController; do not widen S6.

## Accepted residual risks (no work)
- Hint id = conversation + last-6-text signature + trailing picture count; collisions at worst mean a new picture does not get a fresh hint, never a silent upload (S5 re-reads screen/side/signature/crop at tap). Do not add image hashing (it would read/encode pixels before the click).
- No service-level unit test (no Robolectric / instrumentation infrastructure wanted); device smoke is the right evidence layer.
- Caption (UNVERIFIED), WhatsApp group (KEEP-MANUAL), Business (KEEP-MANUAL / UNVERIFIED), 小米 14 (UNVERIFIED) do not block closure. Do not hunt for a group picture to close the sprint.

## Closure
If the Redmi "incoming picture → hint → click → S5 reply" loop succeeds with no severe layout problem, the PO closes S6 directly with no new feature requirements.
