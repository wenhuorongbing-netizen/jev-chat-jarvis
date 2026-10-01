# PO R11 — S5 ACCEPTED; S6 design-and-evidence authorized (condensed archive)

Status: S0–S5 **CLOSED / ACCEPTED** (S5-MANUAL-IMAGE-VERTICAL and S5.1-IMAGE-EGRESS-CLOSURE). Verified against remotes: Android 4f3aca1, Windows 4104f4f; Android 252 tests / Windows 570, contract mirror + manifest consistent, mutation checks on the three key properties.

Closed corrections: sticky refusal (one image upload per generation; text fallback may retry transient failures but never re-uploads the picture; Android structurally sticky); unsupported classification (generic 400/404/413/422 no longer mean "no images"; only the structured code/type on limited statuses, parsed to one boolean); Windows manual-only (auto generation never carries a picture).

Still NOT VERIFIED (not blocking): Android 小米 14; WhatsApp Business / caption-image; Windows QQ/WhatsApp image vertical (no client on this PC; "OPEN EVIDENCE ITEM", qualification to be added later without reopening S5). No need to rerun the Redmi for S5.1 (only the error/fallback path changed).

**Authorized next: JEV-S6-DESIGN-AND-EVIDENCE** — design and evidence only; **no automatic image upload implementation; do not change production image behaviour this round.**
S6 turns "the user clicks once and this one picture leaves" into "the system decides it needs the picture and uploads" — a new data-egress authorization model, not a QoL tweak.

Coordinator should investigate and report:
- which events prove "the newest message really is the other party's picture";
- what sender/image identity evidence each platform/app can provide (Android / Windows / each app);
- how caption + image defines "newest message";
- how a group chat proves who sent the picture;
- how reconnect / scroll / history replay avoid treating an old picture as new;
- how to rate-limit automatic triggering;
- what the user switch should express as an authorization;
- how an automatic image request differs from an automatic text generation;
- whether "detect picture → offer a one-tap 'look at it and reply'" should replace direct auto-upload;
- which apps lack enough evidence and must stay manual-only forever.
Compare two product options without presupposing: **A.** auto-upload when strict conditions hold; **B.** auto-discover + user confirmation (show "看图回复", picture leaves only after a click). PO's lean: B likely gets most of the UX benefit without widening egress authorization, but wants evidence from real platform capability and existing code.

After the S6 design report the PO decides: AUTO-UPLOAD / CONFIRM-FIRST / PLATFORM-SPECIFIC / KEEP-MANUAL, and which apps qualify for which.
