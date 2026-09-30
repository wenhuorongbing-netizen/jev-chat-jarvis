# PO ruling R3 — JEV-S0.1-BOUNDARY-CLOSURE (2026-09-30)

Verdict: **ACCEPT**. S0 and S0.1 CLOSED. **JEV-S1 AUTHORIZED**; S2–S6 NOT AUTHORIZED.
Reviewed commits: Android `c05de0d`, Windows `d930740`.

## The four corrections
All four CLOSED: Android fill fail-closed; Windows fresh target verification; credential guard at the network exit; no provider/model raw text propagation (the control-character key fix was judged correct).

## Recorded, non-blocking
- Architecture debt: Windows `stored_credential()` lets a legacy key with no binding through, and `_bound_route()` returns None (fail-open) when `app.settings` cannot be imported. No S0.2. S1 defines it as a contract; S3 migrates it.
- Android OCR-only chats (WeChat/Feishu) always copy: **accepted**. Do not reintroduce OCR title / coordinate guessing. Belongs to S2 (ConversationRef / CapturedSnapshot / generation identity): either a verifiable conversation proof that does not persist chat text, or stay copy-only.
- Windows WeChat `fill()`: not in S0.1 scope, but **no permanent exception**. S2 must either build a fresh proof or make it copy-only; "the worker said so a second ago, so pixel-click" is not allowed. Until then it is marked LEGACY / NOT FRESH-VERIFIED.
- Runtime smoke: NOT required from the owner now. State stays: S0 engineering acceptance = PASS; real-device/runtime acceptance = NOT VERIFIED. Verified for real at S5/S6.

## S1 authorization
JEV-S1-REPRODUCIBLE-CONTRACT-RELEASE-BASELINE: test CI + cross-platform behaviour contract / golden vectors + Windows test speed and isolation + reproducible dependencies + release gates.
Out of scope: S2 coordinator split, S3 DPAPI / contact-store migration, S4 provider refactor, #9–#15 image features, splitting OverlayController.kt / overlay.py, blanket dependency upgrades.
Two extra S1 acceptance items:
1. Credential binding states are a tested contract: `BOUND_MATCH / BOUND_MISMATCH / LEGACY_UNBOUND / BINDING_UNAVAILABLE`. `None` must no longer mean both "legacy, not migrated" and "could not read the binding". S1 defines, tests and records behaviour only; no storage migration.
2. The fill support matrix is part of the shared contract: `fresh-verified / copy-only / legacy-not-fresh-verified / unsupported`. At minimum: Android tree-backed adapted chat = signature-verified; Android OCR-only = copy-only; Windows QQ/WhatsApp = fresh-verified; Windows WeChat = legacy-not-fresh-verified.
Report in the same protocol; PO does Accept / Reject / Correct.
