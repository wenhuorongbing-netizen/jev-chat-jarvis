# PO ruling R5 — S2 accepted, S3 authorized (2026-09-30)

Source: PO ChatGPT conversation reply to `_reports/S2_report.md` (Android 28635e8, Windows ffee01f). Condensed by the Coordinator.

## Decisions
- JEV-S2-CONVERSATION-OWNERSHIP = **CLOSED / ACCEPTED**. S0, S0.1, S1 stay CLOSED / ACCEPTED.
- Divergence (a) is kept on purpose (platform proof differs); (b)/(c) are gone. Windows WeChat copy-only and Android OCR copy-only accepted. Removal of the @-prefix, WeChat fill and `area` message accepted as expired-code cleanup.
- Remaining writers outside the owners (Android `resultCache`, `lastOcrSignature`, `lastGoodTitle`, `relationSettled`, reroll list; Windows `state["hwnd"|"uia"]`) stay as they are. Test: only state that can decide "does an old result become current" belongs in an owner. Do not chase "zero writers outside the owner".
- Windows `cancel_all()` making shown candidates temporarily unfillable: stricter than needed, not blocking. `Coordinator.begin(force=)` has no production caller: delete when convenient, no sprint for it. Android OCR epoch+title proof is weak but bounded by copy-only: known risk.

## S3 — JEV-S3-PERSISTENCE-INTEGRITY: AUTHORIZED (S4–S6 NOT authorized)
Goal: what the user confirmed, what the program really committed, and what the user is told after a failure must agree. Do not touch the S2 coordinators.

- **S3-A Android KbStore commit semantics.** A failed atomic rename must not fall back to overwriting the original and still call it atomic. `saveContact()` / merge / delete failures must not become "saved". Small result type, semantically Committed / Rejected / Failed (name is the Coordinator's). UI success only from a real Committed. No SQLite.
- **S3-B Contact identity / merge authorization.** Separate *match suggestion* from *identity confirmation*: the system may say "looks like existing contact A", but any operation merging two sources' data needs user confirmation. Test: same name different app; same app same name different person; alias collision; two concurrent/consecutive saves; merge interrupted by a write failure; a late task after delete must not resurrect the contact. "Relation proposal never auto-creates a contact" stays.
- **S3-C Android crash-safe persistence.** Test real filesystem failures, not only a mocked false: temp write ok but replace fails; disk/permission error; corrupt original; interruption as far as it can be simulated. After reload either the complete old or the complete new version exists, never half a JSON. Use the platform's normal atomic replace; no DB-grade over-design.
- **S3-D Windows secrets → DPAPI.** Plaintext env/registry secrets retire. New secrets persist only as ciphertext protected by the Windows user identity. Legacy sources are migration inputs only: detect → import → verify protected storage → delete legacy plaintext. On migration failure keep the old value and report; no destructive cleanup. No "DPAPI write failed → save plaintext" fallback. S1's four-state binding stays: `BINDING_UNAVAILABLE` permanently fail-closed; `LEGACY_UNBOUND` gets a defined migration end-point.
- **S3-E Windows config durability.** Non-secret settings may stay JSON. Same commit principle: a failed write must not look like "saved". Cover temp+replace, malformed old config, migration, interrupted write, concurrent-ish UI writes, and no dangerous half-migrated binding/secret combination.

## S3 explicitly NOT in scope
S4 provider/ModelCapabilities refactor; image features; Android dependency locking; big UI splits; Windows/Android contact sync; SQLite/Room (unless evidence that JSON cannot be atomic); forcing resultCache / `state["uia"]` into S2 owners; real-device image #7/#8; a formal release.

## S3 acceptance evidence
1. Fault injection: write/replace/migration failures never report success.
2. Reload evidence: after a failure a re-created store / re-read config still matches expectation.
3. Identity tests: same-name / alias never merges without confirmation.
4. Secret migration tests: legacy plaintext → protected secret → plaintext cleanup, every failure stage fail-safe.
5. UI truthfulness: "saved" appears only after a real commit.
Android/Windows full tests, CI and contract-mirror stay green.
