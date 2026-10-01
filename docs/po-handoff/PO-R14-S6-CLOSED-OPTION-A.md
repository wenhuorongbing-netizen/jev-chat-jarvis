# PO R14 — JEV-S6 / S6.1 ruling: ACCEPT, S6 CLOSED, option A

Condensed archive of the PO reply to `_reports/S6.1_runtime_report.md` (Android `dcbaa51`; the PO checked CI for that commit: JVM unit tests and debug build success).

## Verdict
- **JEV-S6-CONFIRM-FIRST = CLOSED / ACCEPTED. JEV-S6.1-RUNTIME-CLOSURE = CLOSED / ACCEPTED.** No S6.2.
- Device swap accepted but must be registered exactly as: **Xiaomi 12 Pro 2201122G / Android 15 / WhatsApp 2.26.37.73 / zh-CN / tested 1:1 incoming-picture scenario**. Not the Redmi, not the 小米 14. The PO did not view the screenshots or rerun the device; the evidence is the Coordinator's field report.
- Allow-list `{"在线"}` accepted (no empty-subtitle fallback). Separate-window layout fix accepted as a root-cause local fix.

## A / B / C
- **A adopted**: the proactive pointer appears only while the current "在线" evidence and the other qualification conditions hold; when evidence is missing it stays quiet and the S5 manual path remains. It is an opportunistic convenience, not a promise that every incoming picture gets a hint.
- **B not authorized**: a per-conversation-key memory of a past "在线" cannot rule out a same-titled conversation.
- **C not implemented for now**: the manual entry stays anyway; an offline counterpart is not locked out of manual recognition (`imageReplyManual()` does not require "在线").

## Registered gaps (stay UNVERIFIED, not blocking)
- Own-picture / image-switch-off negatives: unit tests only, no discriminating device evidence. A later device check must use an unspent picture that would show the pointer with the switch on; any chat sending stays with the Owner.
- Caption, WhatsApp group, Business, 小米 14, other languages of the presence line, and the pill layout with the bubble docked on the left. Right-side layout passing does not cover all positions.
- "A tap that misses the pill opens the picture viewer underneath" is a known interaction, not claimed as a touch blocker; no full-screen scrim.

## Execution boundary
- Android stock WhatsApp 1:1 keeps CONFIRM-FIRST. No cross-session / re-entry 1:1 memory cache, no wider status-text allow-list, no removal of the pointer, **no AUTO-UPLOAD**, and no automatic authority for release or other platforms from S6 closing.
