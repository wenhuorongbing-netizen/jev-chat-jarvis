# PO ruling R4 — JEV-S1 (received 2026-09-30, condensed by the Coordinator)

Source: PO ChatGPT conversation, reply to `_reports/S1_report.md`. This is a condensed record, not a verbatim copy.

## Verdicts
- Deviation 1 (Windows `BINDING_UNAVAILABLE` -> REFUSE): **ACCEPT**, becomes the formal design. `BINDING_UNAVAILABLE` is permanently fail-closed; S3 may change the recovery UX, never "read failed -> send".
- **JEV-S1 = CLOSED / ACCEPTED.** Real-device runtime stays NOT VERIFIED.
- **JEV-S2-CONVERSATION-OWNERSHIP = AUTHORIZED.** S3-S6 NOT authorized.

## S2 scope (tightened)
Goal: who owns the current conversation state; which conversation / message version does a candidate belong to; when does an async result become invalid.
- A. Minimal `ConversationRef` / `CapturedSnapshot` / `GenerationRequest` concepts (per-platform classes, no big DTO layer). A candidate traces back to its request/snapshot.
- B. One state owner per platform (Android: `analyzing`, `pendingSnapshot`, cache/current key, OCR callback, result, fill target in `ChatCaptureService`; Windows: `chats`, `state["app_chat"]`, `rev`, `result_rev`, `result_last`, `busy`, `rerun`, worker events). Only the coordinator advances state; capture, network and UI report events. Not a God object.
- C. Stale async results invalidated by generation identity, not more `if currentTitle == ...`: A gen -> switch to B -> A returns; gen N -> new message N+1 -> N returns; cancel -> late return; late OCR callback; reroll vs new message; app/window reconnect; capture pause/resume.
- D. Windows WeChat must exit S2 as `fresh-verified` or `copy-only`; `legacy-not-fresh-verified` must disappear.
- E. Android OCR-only: evaluate whether a stable conversation proof exists; if not, stay `copy-only`. No weaker standard for WeChat.
- The three `fill_verdict` divergences are NOT aligned one by one: re-judge them under the new ConversationRef semantics; each is either eliminated or kept with an explicit platform reason.
- Contract describes production truth, it does not control it: test asserts production capability == contract declaration.

## Explicitly NOT in S2
Android dependency locking (later release-hardening), production enforcement of the fill-support matrix as a policy engine, DPAPI, contact-store migration, provider refactor, image features, big UI splits, S3-S6.

## S2 exit evidence
Testable state owner on both ends; network input is an immutable snapshot/request; old-generation replies / rerolls / OCR callbacks cannot update a new generation; cancelled results cannot revive; Windows QQ/WhatsApp still fresh-verified; WeChat fresh-verified or copy-only; Android OCR-only safe; fill-support contract updated and verified against production capability; three divergences re-reviewed; S0/S1 tests, both CIs and contract-mirror stay green; measured by state ownership and race reduction, not by rewrite size. The report must include a small **Before -> After state ownership table** (who could write rev / result / current conversation / pending request before, who owns each now).
