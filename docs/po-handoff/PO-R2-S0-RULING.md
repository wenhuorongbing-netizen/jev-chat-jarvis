# PO ruling on S0 — CORRECT (2026-09-30)

Source: PO conversation, reply to `_reports/S0_trust_boundary_report.md` (Android 6b337db, Windows fc231d9). Condensed by the Coordinator; the ChatGPT thread is the primary source.

## Verdict per item
- S0-C image boundary: **Accept**
- S0-A baseline: **Accept** (runtime evidence still owed)
- S0-B fill boundary: **Correct**
- S0-D credential boundary: **Correct**
- Error-body boundary: **Correct**, folded into S0.1
- S1–S6: **not authorised**

## Answers to the three questions
1. Fill check: title + window + adapter is **not** enough. Safety over usability: false refusal is acceptable, false fill is not. Formal ConversationRef + revision comes in S2.
2. Error bodies: **drop** the provider response body everywhere (UI, exception text, log, acceptance output). Keep only provider/route + HTTP status + fixed local hint (+ request id only if known to hold no user data). No regex redactor as the primary defence; a literal secret handed to the request layer counts as a secret regardless of length.
3. Accept S0? No: CORRECT. Authorised only `JEV-S0.1-BOUNDARY-CLOSURE`.

## JEV-S0.1-BOUNDARY-CLOSURE (four items only)
1. Android fill fail-closed: same title must not pass without a signature/generation proof; keep the existing multi-point pre-write checks; false refusal OK.
2. Windows fresh verification: candidate carries conversation/revision from generation; check at tap; re-query the target window's current conversation right before `fill_uia` (not the ~1 s stale worker cache); cannot verify fresh -> copy only.
3. Credential guard at the network boundary: `draft.py` / `jev_client.py` must not read a bare `_api_key()`; route + credential form one immutable call snapshot; add a fake-transport test that calling the low-level client directly cannot send a key to an unbound origin.
4. Stop propagating provider raw error bodies; synthetic chat-marker test; no complex error taxonomy.

## Evidence required
- Existing 146 (Android) / 179 (Windows) tests still pass, plus new regression tests.
- Small integration smoke, no real key: Android (existing adb phone) synthetic text — one correct-target fill, one refused after a chat switch; Windows — one normal fill, one refusal after a switch. Report only ALLOW / REFUSED / copied. If a platform cannot do it without private chats, write `RUNTIME NOT VERIFIED`.
- Same report protocol. Deviations left non-blocking: `findEditable` first editable node, Android no-adapter -> copy, Windows image opt-in reset, `FillIntent` not yet production type.
