# PO ruling R7 — S3 + S3.1 accepted, S4 authorized (2026-10-01)

Source: PO ChatGPT conversation reply to `_reports/S3.1_report.md` (Android 9dbe269, Windows 803058a). Condensed by the Coordinator.

## Decisions
- JEV-S3.1 = **ACCEPT**. JEV-S3 (with S3.1) = **CLOSED / ACCEPTED**. S0, S0.1, S1, S2 stay CLOSED / ACCEPTED.
- Permanent rule: plaintext may be kept to avoid data loss, but without a successful DPAPI store + route binding it is never a network credential. `LEGACY_UNBOUND` is only a history/migration state, never a send entitlement. S4 must not reintroduce a similar fallback (no plaintext/env credential compatibility).
- Name similarity is a candidate identity, not an identity fact (Android unbound contacts).
- The read-back-mismatch fix (process value must equal the stored/decrypted value, otherwise scrub) accepted as correct. The two remaining risks (child processes inherit plaintext before the scrub but never send; a path bypassing main.py is still stopped by the binding gate) do not justify an S3.2.
- Real HKCU migration smoke, Windows GUI and 小米 14 stay NOT VERIFIED; the bounded real Windows migration smoke must be done **before S5 starts**, not now.

## JEV-S4-MODEL-ROUTING-CONTRACT: AUTHORIZED (S5–S6 NOT authorized)
Goal: one model request uses one immutable, explainable route from "which provider/model, what it can do, which credential, what input" to the result.
- S4-A immutable ModelRoute / RequestRoute snapshot: provider + protocol + base/destination + model + capability evidence + credential binding. After a generation starts, the background call chain does not re-read mutable settings for any of these. Android likewise. Platforms need not share a class schema; behaviour goes into contract vectors.
- S4-B capability four states Supported / Unsupported / Unknown / DisabledByPolicy. Vision must not be "query failed → false → unsupported": distinguish provider says no, field absent, /models request failed, unknown JSON shape, owner disabled images. Capability cache carries route/model identity + evidence/source + lifetime; a provider/base/model change never reuses an old entry.
- S4-C provider-specific capability adapters: each declares only what it really knows, else Unknown. No model-name substring whitelist as final truth; a fallback policy is allowed but must be marked as inference, not provider evidence.
- S4-D reply contract closure, deterministic vectors on both sides: normal success; fewer than three; malformed JSON; bare-string compatibility; bilingual text + Chinese gloss; analysis separate from the fillable text; provider refusal; capability unknown; cancellation/stale request. Do not fabricate duplicates or placeholders to reach three.
- S4-E retire the Windows legacy Jev judgment runtime path (judgment → draft → rank). Keep the config migration compatibility. Search call sites and the old settings migration before deleting. Windows ends on the same bilingual/reply contract as Android.
- S4-F error/retry/cancellation classes: Auth / RateLimited / Timeout / Unsupported / InvalidResponse / Cancelled / Transport; no heavy framework. Retries bounded; 401/403, route mismatch, Unsupported never retried; 429/some 5xx/transport per an explicit policy; an invalidated S2 generation does not keep retrying. No raw provider body in logs/errors (S0 stays).
- Forbidden in S4: S5 image vertical (capability checks and fake transports are fine, no real chat screenshots to a vision model), S6 required vision, UI redesign, Android dependency locking, reopening plaintext/env credential compatibility, a broad engine.py/main.py teardown beyond removing the old Jev mode.

## Acceptance checks the PO will make
1. Changing provider/model/base settings does not alter an in-flight request after the snapshot is made.
2. Credential destination and snapshot destination come from the same fact.
3. Unknown and Unsupported are never mixed up.
4. Capability cache does not leak across model/base/provider changes.
5. Provider schema with missing/unknown fields has tests.
6. Reply-contract vectors stay identical on both platforms.
7. Windows old Jev runtime path has no production caller.
8. Stale/cancelled generation does not keep retrying.
9. Raw provider/model output still not in logs or errors.
10. S0–S3.1 full tests, both CIs, contract mirror stay green.
The S4 report must include a small **setting → route snapshot → credential → transport → result** before/after data-flow table, showing that "re-reading config mid-call" is really gone and not just a new ModelRoute type.

## State
S0 / S1 / S2 / S3(+S3.1) CLOSED. S4 AUTHORIZED. S5–S6 NOT AUTHORIZED.
