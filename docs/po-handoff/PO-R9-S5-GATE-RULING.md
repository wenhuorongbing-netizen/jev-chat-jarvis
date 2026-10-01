# PO ruling R9 — S5 gate PASS, S5 opened (2026-10-01)

Source: PO ChatGPT conversation reply to `_reports/S5_gate_report.md` (Windows 7c18fd1, Android e7fd925). Condensed by the Coordinator.

## Decisions
- `JEV-S5-GATE-RUNTIME-SMOKE` = **CLOSED / PASSED**. The evidence covers the real upgrade chain: HKCU plaintext → DPAPI → ciphertext + binding → private plaintext deleted → fresh process decrypts → route binding enforced before send.
- Deviation accepted: the Owner's real `LLM_API_KEY` was migrated (Owner consent obtained before running).
- No on-screen GUI check does not block the gate; settings text and the route-change message stay for later GUI/runtime acceptance.
- Windows cleanup `7c18fd1` accepted. The JEV migration slot/helpers stay (first real verification just happened).
- `JEV-S5-MANUAL-IMAGE-VERTICAL` = **AUTHORIZED**. S6 NOT authorized.

## Two tracks
| Track | State | Scope |
|---|---|---|
| S5-A platform-independent image request loop | authorized now | generation ownership, image lifecycle, capability/policy, ReplyClient multimodal request, fallback, tests |
| S5-B real WhatsApp image-bubble capture | waits for #7 samples | Android adapter/crop geometry driven by real node/screenshot evidence from the 小米 14 |

They merge for the manual-image vertical acceptance.

## S5-A
1. Image belongs to one generation request: generation N + latest message/image identity + ephemeral image. New message, session switch, cancel, overlay/service teardown, or N+1 replacing N → the old image can never be used by a new request. No base64/bitmap in a long-lived result cache, contact, ChatMemory or history.
2. Capability decision wired into production: owner disabled → `DisabledByPolicy`; Supported → attach; Unsupported → text-only; Unknown → try multimodal, text fallback only on an explicit unsupported. Auth / RateLimited / Timeout / Transport never pretend "image unsupported" and never become a text retry.
3. `ReplyClient` multimodal exit: same generation, same `ModelRoute`, same credential. One multimodal reply request is the default. No automatic "vision describes, then reply" second model call; a two-stage path only if the Owner configured a separate vision route.
4. The "one image reply" test seam is approved. Minimum cases: latest peer image + enabled + Supported → attached; owner disabled → zero image bytes leave the process; Unsupported → text-only; Unknown + accepted → multimodal success; Unknown + explicit unsupported → exactly one text fallback; Unknown + 401/403 → no fallback; Unknown + timeout/rate limit → S4 retry policy, not text fallback; stale generation before retry/fallback → no provider call; image from N never enters N+1; reroll needs legitimate ephemeral ownership, otherwise no "latest image" guessing.
5. Image lifetime is defined as the active reply session (through reroll), not "a while in a cache".

## S5-B (Android)
Waits for #7. Minimal evidence needed from the Owner on the 小米 14: WhatsApp ordinary single chat; peer's latest message is an image; ideally also an image with a caption; accessibility node dump; image bubble bounds and node properties; one redacted screenshot for coordinate mapping; Business edition separately if wanted. No real private chat text in Git. Before samples arrive: adapter interface, fake fixtures, crop contract and test seam are allowed; hard-coding WhatsApp resource-ids, hierarchy or bounds heuristics and claiming support is not.

## Windows
Windows may proceed first (UIA image capture exists for QQ/WhatsApp). S0 rule stands: only images from the current window/screenshot; **never restore QQ local-file scanning**. The code review must search for this regression explicitly.

## Image lifecycle ruling
Raw pixels belong only to the active reply session: capture → crop → request/reroll → release. Forbidden: contact storage, ChatMemory, disk cache, debug artifact, log, crash report, next conversation. The model's image understanding is not persisted either. For reroll the image may live until the reply session ends by a new message, session switch, cancel, result replacement, or app/service teardown.

## S5 exit gate (not closable on fake transport alone)
- Windows: a real, controllable WhatsApp or QQ image session completing one manual image → candidate-reply loop.
- Android: after the WhatsApp sample forensics, at least one evidence of a correct crop; if real provider/key conditions allow, a full multimodal reply. Otherwise state clearly "capture verified" vs "provider vertical not verified".
- Must also show: crop is not the full screen; no adjacent chat image included; old image unusable after a session switch; owner switch off → no image leaves; image not written to disk; capability Unknown/Unsupported behave per S4; no text-only regression.

## Status
S0–S4: CLOSED. S5-GATE: CLOSED/PASS. S5-A: authorized now. S5-B: adapter interface/test seam may start; real Android WhatsApp crop waits for #7. S5-MANUAL-IMAGE-VERTICAL: in execution. S6: NOT authorized.
