# PO R12 — JEV-S6 design ruling: ACCEPT DESIGN / IMPLEMENT B (CONFIRM-FIRST)

Archived summary of the PO reply to `_reports/S6_design_report.md` (condensed, decisions verbatim in meaning).

## Verdict
- S6-DESIGN-AND-EVIDENCE = CLOSED / ACCEPTED.
- **S6-CONFIRM-FIRST = AUTHORIZED, Android WhatsApp 1:1 only.**
- **AUTO-UPLOAD = NOT AUTHORIZED.** No platform has message identity / arrival evidence; even Android WhatsApp cannot exclude "scroll back to an old picture" replay.

## Per-app ruling
| App | Ruling |
|---|---|
| Android WhatsApp 1:1 | CONFIRM-FIRST |
| Android WhatsApp group | KEEP-MANUAL; proactive hint explicitly forbidden (in a group "other" is not an identity) until a sender proof/sample exists |
| Android WhatsApp Business | KEEP-MANUAL / UNVERIFIED |
| Android QQ, Feishu, X, WeChat | KEEP-MANUAL |
| Windows QQ | KEEP-MANUAL / EVIDENCE PENDING (no live client; code inference alone must not open a proactive hint) |
| Windows WhatsApp, Windows WeChat | KEEP-MANUAL (not in B) |

## S6-CONFIRM-FIRST scope
- Hint ("对方发来一张图 · 识别并回复") shown only when: latest row == image AND side == other AND whole picture visible AND conversation valid AND not a group / sender-uncertain.
- Before the click: no encode, no capability query, no network request, no pixels leave, no disk, no auto generation.
- Click goes into the existing S5 path (EphemeralImage → manual generation → capability → multimodal reply). Thin layer; no second image pipeline, no second crop/network implementation, no new long-lived image state.
- Hint lifetime bound to existing identity (ConversationRef + current snapshot/generation + image row identity/signature). Invalidated by: new message, chat switch, picture no longer latest row, leaving chat, overlay/service teardown, owner switch off, after one click, re-qualification failing. Scroll back to an old picture may show the hint again — accepted (failure cost is one extra hint; user still has to click).
- Switch: **no new auto-upload switch.** Existing image switch off → no hint; on → hint allowed, still click-to-send.
- Caption images: do not write unverified caption-specific rules; if the adapter naturally still sees `conversation_row_image + caption child` as an image row the hint may stay, but the report must mark `caption path = structurally inferred / NOT VERIFIED`.

## Acceptance requirements
- qualifying 1:1 incoming latest image → hint appears; hint appearing → provider calls = 0
- click → exactly the existing manual image path
- new message / chat switch / latest row becomes text → hint disappears
- owner image switch off / self-sent image / partial or off-screen image / group or sender uncertain → no hint
- stale hint click → zero image egress
- S5.1 sticky / fallback / manual-only contracts stay green
- no new long-lived image state, disk files, or second crop/network implementation
- Nice to have: one Redmi run: receive test picture → hint appears on its own → click → correct multimodal reply
- 小米 14, caption, Business, Windows QQ may stay UNVERIFIED and do not block the sprint.

## Reopening AUTO-UPLOAD
Only on new platform evidence: a stable per-message ID / arrival token, plus reliable sender identity in groups. Do not add hash / cooldown / seen-set heuristics to approximate safety for A.
