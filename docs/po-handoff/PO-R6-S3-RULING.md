# PO ruling R6 — S3 CORRECT, S3.1 authorized (2026-10-01)

Source: PO ChatGPT conversation reply to `_reports/S3_report.md` (Android c298450, Windows 62e1e43). Condensed by the Coordinator.

## Decisions
- JEV-S3-PERSISTENCE-INTEGRITY = **CORRECT** (body accepted, not yet closed). S0, S0.1, S1, S2 stay CLOSED / ACCEPTED.
- Android `KbResult` + atomic replace and Windows secret+binding in one atomic commit with DPAPI match the S3 goals; five evidence items accepted.
- Blocker: after a failed migration the legacy plaintext is still returned by `_get_key()` and `LEGACY_UNBOUND` still means SEND=true. "Keep" is not "use": a key whose destination cannot be proven must not be sent.
- No Reject for the missing real-device / real-HKCU / GUI verification (evidence discipline is correct). Before entering S5 the PO wants one bounded real Windows migration smoke; not needed earlier.

## Rulings on the three questions
1. `OPENROUTER_API_KEY` / `DEEPSEEK_API_KEY` (shared names): never auto-delete. Only detect → import into Jev → notify the user. Current behaviour accepted. `JEV_API_KEY` / `LLM_API_KEY` (Jev-private): may be deleted after migration and read-back verification.
2. Migration failure = KEEP plaintext + REPORT issue + **REFUSE Jev network use**. Persistent legacy registry source with unfinished migration → refuse. DPAPI stored + binding → normal. Typed credential in settings → normal. A process-env key whose route cannot be proven → fail closed too (S4 decides whether a route-aware env config is worth keeping). `LEGACY_UNBOUND → SEND` retires at the end of S3: production send result false, or an explicit retired state (Coordinator picks the smaller change); it may remain in the v1 contract as migration/history state.
3. Contacts with no app binding: may exist with notes/relationship, but inject **no** chat context until the user confirms a merge/bind on a same-name chat. A suggestion is not an identity confirmation. Confirmed → app added → eligible. Declined → the chat gets its own (app, name) contact or stays unfiled.

## JEV-S3.1-IDENTITY-AND-LEGACY-CLOSURE: AUTHORIZED (only these two)
A. Windows legacy credential closure. Tests: DPAPI failure → registry key kept + no network credential; config write failure → plaintext kept + no send; corrupt/unreadable config → plaintext kept + no send; generic name imported OK → still present, Jev uses its DPAPI copy; generic import fails → variable untouched + Jev refuses; successful migration → DPAPI+binding works; typed credential/settings flow has no regression.
B. Android unbound-contact closure. `apps.isEmpty()` contacts are never selected by `findContact(title, app)` for notes/history; suggestion only; context only after confirmed merge/bind. Tests: manual 小王 + QQ 小王 → no context before confirmation; confirm → context available; WhatsApp same name does not inherit unless also bound; alias obeys the same rule; already-bound (app, name) contacts unaffected.

Do not touch anything else in S3. Do not try to separate two same-name contacts in one app (a model-capability limit).

## Order after S3.1
S3.1 closes → next is S4 (JEV-S4-MODEL-ROUTING-CONTRACT: route/config snapshot, capability Supported/Unsupported/Unknown/DisabledByPolicy, provider-specific capability query, bounded retry/error taxonomy, prompt/reply contract, retire Windows Jev judgment mode, drop legacy-credential compatibility no longer needed, reliable "can this model see images"). S5/S6 NOT authorized (S5 widens data egress).

## State
S0 / S1 / S2 CLOSED. S3 CORRECT. S3.1 AUTHORIZED. S4–S6 NOT AUTHORIZED. Report back after S3.1; PO then rules on S3 closure and S4.
