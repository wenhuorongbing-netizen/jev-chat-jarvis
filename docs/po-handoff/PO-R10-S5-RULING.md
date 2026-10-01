# PO R10 — S5 ruling (condensed archive; the head of the reply was cut off in the page capture)

Status: S0–S4 CLOSED; **S5 = CORRECT (not yet ACCEPTED)**; **S5.1-IMAGE-EGRESS-CLOSURE = AUTHORIZED**; S6 = NOT AUTHORIZED.

Accepted as-is:
- Android S5 vertical: runtime verified on Redmi / NOT verified on 小米 14 (crop 68,1261,874,2389 of 1280×2772 not full screen; multimodal reply understood the picture; nothing filled or sent; no image in app dir; no base64 in logcat).
- Windows has no real QQ/WhatsApp: accept as **NOT VERIFIED**; code part may close, but Windows manual-image must not be labelled runtime-verified / release-qualified. A real smoke is done once an environment exists; no need to switch machines now.
- Missing caption-image / Business / 小米 14 samples are not blockers; keep UNVERIFIED in the support matrix.
- Windows reroll not surfacing `image_use`: not a blocker; reuse an existing "no picture used" hint if cheap, otherwise UX debt.

Rejected / to fix (the S5.1 scope — exactly three items):
1. **Sticky unsupported (retry × fallback).** Per generation at most one image-upload stage: IMAGE_PENDING → IMAGE_REJECTED_UNSUPPORTED → TEXT_ONLY. After an explicit unsupported, the generation/session is text-only for good; a later RateLimited/Timeout/Transport on the text fallback may retry per S4 policy but only the text request. Auth → no fallback. Timeout/Transport before the provider explicitly refuses the image → may retry multimodal per S4. Stale generation → image and text fallback both stop. A mutation test must go red when the sticky state is removed.
2. **Narrow the 4xx → unsupported mapping.** Generic 400/404/413/422 are no longer "image unsupported". Only capability evidence (provider capability already Unsupported → text-only, no image) or a reliable structured unsupported signal triggers the image→text fallback; the signal may only be stored as a local enum, never the raw body. Ambiguous 4xx fail closed (Unknown + ambiguous 4xx → fail, not guessed as unsupported). 413 never falls back; report a size problem.
3. **Windows manual-only.** Automatic generation never carries an image; only an explicit user action ("look at this picture and reply" / manual image entry) grants the image session for that generation. The settings switch only means "I may use the image feature manually", not a standing upload authorization. Owner switch stays the master switch. Automatic must-see (S6) stays nonexistent. Android already complies; do not rewrite it for symmetry.

S5.1 acceptance (tests at least):
- auto generation + image available + opt-in=true → 0 image uploads
- manual generation → image eligible
- explicit unsupported → image call once → text fallback
- fallback transient failure → retry text; total image uploads stays 1
- ambiguous 400/404/413/422 → no text fallback; 413 → no fallback; Auth → no fallback
- stale generation before text retry → no retry
- owner off → capability query 0, image upload 0
- queued/reroll session-release tests stay green; QQ local-file scanning still absent
Windows real runtime smoke is not an S5.1 blocker (keep recording NOT VERIFIED).

After S5.1 closes the three items with full CI green, the PO expects S5 = CLOSED / ACCEPTED; S6 (whether, and for which apps, a "must-see" automatic mode) is a separate ruling.
