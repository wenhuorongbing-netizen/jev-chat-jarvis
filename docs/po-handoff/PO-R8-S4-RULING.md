# PO ruling R8 — S4 accepted, S5 gate authorized (2026-10-01)

Source: PO ChatGPT conversation reply to `_reports/S4_report.md` (Android 9dc9e69, Windows c2251c9). Condensed by the Coordinator.

## Decisions
- JEV-S4-MODEL-ROUTING-CONTRACT = **ACCEPT / CLOSED**. S0–S4 CLOSED / ACCEPTED.
- Why: route snapshot is a real boundary on both platforms (Windows `ReplyRoute`/`ReplyPlan`; Android `ModelRoute`/`ReplyConfig` read once per generation); capability has four states and Unknown is neither Unsupported nor cached; reply contract is deterministic (fewer than three stays fewer; Windows Chinese and foreign paths share one strict parser); the old Jev judgment runtime is out of production (the JEV slot stays only for migration); SDK retries are off and retries are owned by the local taxonomy, with a stale S2 generation never calling the provider again.

## Answers to the report's questions
1. **Android lighter snapshot: sufficient. No S4.1 destination gate.** Do not add provider/protocol/binding fields for symmetry. Standing rule: if Android ever gains multiple providers, custom provider credentials or key reuse, it must introduce credential→destination binding at the same time; same-origin fallback alone no longer suffices then.
2. **Windows dead code is split.**
   - `settings.jev_key()/has_jev_key()` and the JEV-slot migration: keep. They carry old-install migration compatibility; keep at least until the real migration smoke is done, then decide when the compatibility window ends.
   - Hidden `tension`/`intent` widgets and the single-element loop (pure UI scaffolding with no production or migration use): may be deleted in a small cleanup before S5 starts; it is not an S5 product goal and needs no sprint of its own.
3. **Capability cache keeps `origin/path + exact model`. No credential identity, not even a hash.** It caches the provider's declared input modality for a model, which is not a property of the secret; adding identity couples the cache to the secret lifecycle and creates a credential-derived identifier for no benefit. If evidence ever shows per-account entitlement differences, model it as a provider-supplied non-secret account/project scope, not hash(key). The remaining risk stays listed but is not a defect.

## Next: JEV-S5-GATE-RUNTIME-SMOKE (AUTHORIZED) — S5 admission gate, not a new architecture sprint
Owner/Coordinator performs a small real Windows verification (no private chats, no real chat images). Use a temporary/test API key, not a long-lived production key; revoke it afterwards.
At least:
- a temporary legacy Jev private key in real HKCU is recognised by the migration;
- after success `config.json` contains only `dpapi1:` ciphertext + binding;
- the private HKCU plaintext is deleted;
- generic `OPENROUTER_API_KEY` / `DEEPSEEK_API_KEY` are NOT deleted;
- after restart the app recovers the key from the DPAPI store;
- changing the route without re-entering the key → the request is rejected before it is sent;
- the deliberate-failure migration path is already covered by fault tests; do not break a real environment to demonstrate it;
- the Windows GUI starts and the settings page reads the post-migration state.
Plus the small Windows cleanup above (delete hidden `tension`/`intent` scaffolding only; keep the JEV migration slot/helpers).

## S5 pre-authorization (after the gate passes, no further planning permission needed)
JEV-S5-MANUAL-IMAGE-VERTICAL, manual image recognition only: the latest message really is an image → the user explicitly triggers/enables image handling for this time → capture the correct bubble → bind to the current generation → capability decision → model request → three replies → image lifecycle ends. Order: WhatsApp single chat → acceptance → QQ. Both platforms reuse the existing ConversationRef / generation identity / image policy / ModelRoute / capability / retry / reply contract; no second vision architecture.
S5 excludes: automatic "must look at the image"; GIF / album / full group coverage; Android WeChat restore; long-term image storage; images into the contact knowledge base; image descriptions into history; automatic provider switching; S6. The image exists only for this generation/request.
Carry into S5: Windows `DISABLED_BY_POLICY` is unreachable on the reply path today (switch off → `image=None`). S5 wiring should route the image decision through the policy state (owner disabled → DisabledByPolicy) so "no image" and "image present but the user forbade sending it" can be told apart. No extra UI layer required.

## Status
S0–S4: CLOSED / ACCEPTED. S5-GATE-RUNTIME-SMOKE: AUTHORIZED. S5-MANUAL-IMAGE-VERTICAL: CONDITIONALLY AUTHORIZED, starts directly once the gate passes. S6: NOT authorized.
