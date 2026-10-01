package com.jev.probe.core

import android.content.Context
import android.util.Log

/**
 * App-private config store. Holds the two live API routes (reply / vision),
 * the relationship description, the conversation whitelist, plus the context
 * and OCR switches.
 *
 * Sprint 5 (D1): the Jev judgment mode is deleted. The judge route's fields
 * survive only as @Deprecated read/write shims over the old storage keys, so
 * an upgraded install with v1.x data cannot crash and an old judge key can
 * still serve as the reply-route fallback (see [effectiveReplyKey]).
 *
 * Key handling: stored in app-private SharedPreferences (not world-readable,
 * never logged, never in code/git). Only key *lengths* are ever logged.
 * The API keys are additionally encrypted at rest via [KeyVault]
 * (Android Keystore AES/GCM, `v1:` format) — the public getters/setters
 * below still speak plaintext; the disk only ever sees ciphertext.
 */
class Prefs(context: Context, prefsName: String = PREFS_MAIN) {

    private val sp = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /**
     * Only the real config migrates — and only the real config logs it. The
     * throwaway instances behind the settings test buttons and the KB self-check
     * have nothing to carry over, and used to print one migration line per tap.
     */
    init { if (prefsName == PREFS_MAIN) { migrateIfNeeded(); unseedBochaDefaultIfUnconfigured() } }

    /**
     * v1.2 -> v1.3: the single `openrouter_key` becomes the judge route's key.
     * `reply_model` keeps its old storage key, so it carries over untouched.
     */
    private fun migrateIfNeeded() {
        if (sp.getBoolean(K_MIGRATED_V13, false)) return   // runs exactly once
        val legacy = sp.getString(K_LEGACY_KEY, "") ?: ""
        val current = sp.getString(K_JUDGE_KEY, "") ?: ""
        val e = sp.edit().putBoolean(K_MIGRATED_V13, true)
        if (current.isBlank() && legacy.isNotBlank()) {
            e.putString(K_JUDGE_KEY, legacy)
            Log.i(TAG, "prefs migrated judgeKey.len=${legacy.length}")
        } else {
            Log.i(TAG, "prefs migrated judgeKey.len=${current.length} (no legacy key to copy)")
        }
        e.apply()
    }

    /**
     * Fresh install only: default the judge route to Bocha Jev (limited-time free).
     * Runs ONLY when there is no judge config whatsoever — the provider key was
     * never written AND both the current judge key and the legacy OpenRouter key
     * are blank. Any existing or migrated user is left completely untouched, so an
     * OpenRouter key can never be redirected to jev.bocha.cn. The [judgeProvider]
     * getter default stays OpenRouter on purpose; this only seeds a truly new sp.
     */
    /**
     * v1.4.0 seeded fresh installs to Bocha Jev; v1.4.1 restores OpenRouter as the
     * default (Bocha stays available, now second in the list). Undo that earlier
     * auto-seed exactly once, and only when the user never entered a key and never
     * picked a provider by hand — a saved key, or any non-Bocha provider, means a
     * real choice we must not touch. Fresh installs now get no seed at all: the
     * getters already default to OpenRouter.
     */
    private fun unseedBochaDefaultIfUnconfigured() {
        if (sp.getBoolean(K_UNSEEDED_BOCHA, false)) return
        val e = sp.edit().putBoolean(K_UNSEEDED_BOCHA, true)
        val prov = sp.getString(K_JUDGE_PROVIDER, null)
        val key = sp.getString(K_JUDGE_KEY, "") ?: ""
        if (prov == PROVIDER_BOCHA && key.isBlank()) {
            e.remove(K_JUDGE_PROVIDER).remove(K_JUDGE_BASE).remove(K_JUDGE_MODEL)
            Log.i(TAG, "prefs: reverted auto-seeded bocha default to openrouter")
        }
        e.apply()
    }

    // ------------------------------------------------ judge (deprecated, legacy data only)

    /** @deprecated Jev 判断模式已删（Sprint 5 / D1）。仅为兼容老数据保留读写。 */
    @Deprecated("Jev judge mode deleted in Sprint 5; kept only so old stored data stays readable")
    var judgeProvider: String
        get() = sp.getString(K_JUDGE_PROVIDER, PROVIDER_OPENROUTER) ?: PROVIDER_OPENROUTER
        set(v) = sp.edit().putString(K_JUDGE_PROVIDER, v.trim()).apply()

    /** @deprecated Jev 判断模式已删（Sprint 5 / D1）。仅为兼容老数据保留读写。 */
    @Deprecated("Jev judge mode deleted in Sprint 5; kept only so old stored data stays readable")
    var judgeBaseUrl: String
        get() = sp.getString(K_JUDGE_BASE, DEFAULT_JUDGE_BASE_OPENROUTER) ?: DEFAULT_JUDGE_BASE_OPENROUTER
        set(v) = sp.edit().putString(K_JUDGE_BASE, v.trim()).apply()

    /**
     * Plaintext in / plaintext out — encryption is a storage detail, see
     * [readSecret]/[writeSecret]. Disk holds `v1:` AES/GCM ciphertext.
     *
     * @deprecated Jev 判断模式已删（Sprint 5 / D1）。仍被 [effectiveReplyKey]
     * 用作老数据的兜底 key，读写保留。
     */
    @Deprecated("Jev judge mode deleted in Sprint 5; kept as the legacy fallback for effectiveReplyKey")
    var judgeKey: String
        get() = readSecret(K_JUDGE_KEY)
        set(v) = writeSecret(K_JUDGE_KEY, v)

    /** @deprecated Jev 判断模式已删（Sprint 5 / D1）。仅为兼容老数据保留读写。 */
    @Deprecated("Jev judge mode deleted in Sprint 5; kept only so old stored data stays readable")
    var judgeModel: String
        get() = sp.getString(K_JUDGE_MODEL, DEFAULT_JUDGE_MODEL_OPENROUTER) ?: DEFAULT_JUDGE_MODEL_OPENROUTER
        set(v) = sp.edit().putString(K_JUDGE_MODEL, v.trim()).apply()

    /** @deprecated Back-compat alias so older call sites keep compiling. */
    @Deprecated("Alias of the deprecated judgeKey; kept for source compatibility only")
    var openRouterKey: String
        get() = judgeKey
        set(v) { judgeKey = v }

    // ---------------------------------------------------------------- reply

    /** OpenAI-compatible base, up to and including `/v1`. */
    var replyBaseUrl: String
        get() = sp.getString(K_REPLY_BASE, DEFAULT_REPLY_BASE) ?: DEFAULT_REPLY_BASE
        set(v) = sp.edit().putString(K_REPLY_BASE, v.trim()).apply()

    /** Blank = fall back to [judgeKey] when both bases share an origin. Plaintext in/out, ciphertext at rest. */
    var replyKey: String
        get() = readSecret(K_REPLY_KEY)
        set(v) = writeSecret(K_REPLY_KEY, v)

    /** Generative model for drafting the 3 candidate replies. */
    var replyModel: String
        get() = sp.getString(K_REPLY_MODEL, DEFAULT_REPLY_MODEL) ?: DEFAULT_REPLY_MODEL
        set(v) = sp.edit().putString(K_REPLY_MODEL, v.trim()).apply()

    // --------------------------------------------------------------- vision

    /**
     * Blank / never saved = same source as the reply route (see [VisionRoute]):
     * DeepSeek when replies use DeepSeek, otherwise the OpenRouter preset.
     */
    var visionBaseUrl: String
        get() = sp.getString(K_VISION_BASE, null)?.ifBlank { null } ?: VisionRoute.defaultBase(replyBaseUrl)
        set(v) = sp.edit().putString(K_VISION_BASE, v.trim()).apply()

    /** Blank = fall back to the reply key, but only for the same host. Plaintext in/out, ciphertext at rest. */
    var visionKey: String
        get() = readSecret(K_VISION_KEY)
        set(v) = writeSecret(K_VISION_KEY, v)

    /** Blank / never saved = `deepseek-flash` on DeepSeek, else the OpenRouter preset. */
    var visionModel: String
        get() = sp.getString(K_VISION_MODEL, null)?.ifBlank { null } ?: VisionRoute.defaultModel(visionBaseUrl)
        set(v) = sp.edit().putString(K_VISION_MODEL, v.trim()).apply()

    // -------------------------------------------------------- context (D)

    /**
     * Record per-contact history and inject it into analysis. Default OFF:
     * nothing about the user's chats is written to disk unless they opt in
     * (v1.3 revision, D stage).
     */
    var contextEnabled: Boolean
        get() = sp.getBoolean(K_CTX_ENABLED, false)
        set(v) = sp.edit().putBoolean(K_CTX_ENABLED, v).apply()

    /** How many recent history entries to inject. */
    var contextHistoryCount: Int
        get() = sp.getInt(K_CTX_COUNT, 30)
        set(v) = sp.edit().putInt(K_CTX_COUNT, v).apply()

    /** Auto-summarize a contact once enough history accumulates. */
    var autoSummary: Boolean
        get() = sp.getBoolean(K_AUTO_SUMMARY, true)
        set(v) = sp.edit().putBoolean(K_AUTO_SUMMARY, v).apply()

    // ------------------------------------------------------------ OCR (B)

    /** "mlkit" | "vision". */
    var ocrEngine: String
        get() = sp.getString(K_OCR_ENGINE, OCR_MLKIT) ?: OCR_MLKIT
        set(v) = sp.edit().putString(K_OCR_ENGINE, v.trim()).apply()

    /** Run generic OCR capture on apps with no dedicated adapter. */
    var ocrForUnknownApps: Boolean
        get() = sp.getBoolean(K_OCR_UNKNOWN, true)
        set(v) = sp.edit().putBoolean(K_OCR_UNKNOWN, v).apply()

    /** Fall back to OCR when an adapted app's node tree comes back empty. */
    var ocrFallback: Boolean
        get() = sp.getBoolean(K_OCR_FALLBACK, true)
        set(v) = sp.edit().putBoolean(K_OCR_FALLBACK, v).apply()

    /** Auto-analyze in OCR mode (default off: OCR costs a screenshot each time). */
    var ocrAutoAnalyze: Boolean
        get() = sp.getBoolean(K_OCR_AUTO, false)
        set(v) = sp.edit().putBoolean(K_OCR_AUTO, v).apply()

    // ------------------------------------------------------------- existing

    /** Free-text describing who the other person is; injected into the reply prompt. */
    var relationship: String
        get() = sp.getString(K_REL, DEFAULT_REL) ?: DEFAULT_REL
        set(v) = sp.edit().putString(K_REL, v).apply()

    /** Master on/off for showing the overlay + running analysis. */
    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, true)
        set(v) = sp.edit().putBoolean(K_ENABLED, v).apply()

    /**
     * Conversation whitelist: titles the assistant is allowed to act on. Empty
     * set means "all conversations". Stored as a plain string set.
     */
    var whitelist: Set<String>
        get() = sp.getStringSet(K_WHITELIST, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(K_WHITELIST, v).apply()

    /** Conversations whose relation proposal the user skipped; see [com.jev.probe.core.kb.RelationSkips]. */
    var relationSkips: Set<String>
        get() = sp.getStringSet(K_REL_SKIPS, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(K_REL_SKIPS, v).apply()

    /** Overlay panel opacity, 60..100 (%). Lower lets the chat show through. */
    var overlayOpacity: Int
        get() = sp.getInt(K_OPACITY, 92).coerceIn(60, 100)
        set(v) = sp.edit().putInt(K_OPACITY, v.coerceIn(60, 100)).apply()

    /** Remembered vertical position of the bubble (px); -1 = default. */
    var bubbleY: Int
        get() = sp.getInt(K_BUBBLE_Y, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_Y, v).apply()

    /** Remembered horizontal position of the bubble (px); -1 = default. */
    var bubbleX: Int
        get() = sp.getInt(K_BUBBLE_X, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_X, v).apply()

    /**
     * 气泡松手后吸到较近一侧边。默认 false——保持现状「停在哪就在哪」，
     * 躲开 MIUI/手势导航的边缘返回手势区（Sprint 7，可选开启）。
     */
    var bubbleSnap: Boolean
        get() = sp.getBoolean(K_BUBBLE_SNAP, false)
        set(v) = sp.edit().putBoolean(K_BUBBLE_SNAP, v).apply()

    /** Sprint 7：首次三步引导已完成（含跳过）。 */
    var onboarded: Boolean
        get() = sp.getBoolean(K_ONBOARDED, false)
        set(v) = sp.edit().putBoolean(K_ONBOARDED, v).apply()

    /** Auto-analyze on every incoming message; if false, user taps to analyze. */
    var autoAnalyze: Boolean
        get() = sp.getBoolean(K_AUTO, true)
        set(v) = sp.edit().putBoolean(K_AUTO, v).apply()

    /**
     * Sprint 5 (D1): bilingual is the only mode — the Jev judgment mode and its
     * settings toggle are deleted. The getter is pinned to true so an old
     * install that once switched it off still behaves the bilingual way; the
     * setter ignores writes (the K_BILINGUAL storage key is inert legacy data).
     * Only the reply key is needed.
     */
    var bilingualMode: Boolean
        get() = true   // 永远 true：模式开关已废弃（Sprint 5 / D1）
        set(@Suppress("UNUSED_PARAMETER") v) = Unit   // 写入忽略：老版本残留值不再生效

    /** Language the replies are written in (and filled), e.g. "德语". */
    var bilingualLang: String
        get() = sp.getString(K_BILINGUAL_LANG, DEFAULT_BILINGUAL_LANG) ?: DEFAULT_BILINGUAL_LANG
        set(v) = sp.edit().putString(K_BILINGUAL_LANG, v.trim().ifBlank { DEFAULT_BILINGUAL_LANG }).apply()


    /** Who the user is, in their own words — the prompt writes as this person. */
    var aboutMe: String
        get() = sp.getString(K_ABOUT_ME, "") ?: ""
        set(v) = sp.edit().putString(K_ABOUT_ME, v.trim()).apply()

    /** WeChat hides text from most accessibility services and may flag screen
     *  readers; reading it is opt-in. */
    var wechatEnabled: Boolean
        get() = sp.getBoolean(K_WECHAT, false)
        set(v) = sp.edit().putBoolean(K_WECHAT, v).apply()
    // ------------------------------------------------------------- helpers

    /**
     * Secret read path for the three API keys. Decides via [KeyVaultCodec]:
     * blank -> ""; `v1:` -> Keystore decrypt; legacy plaintext -> returned
     * as-is AND lazily re-written as ciphertext so storage upgrades itself
     * without a migration pass. The write-back swallows Keystore failures
     * (the getter must never break a previously working plaintext read);
     * the disk value then simply upgrades on the next read or set instead.
     */
    private fun readSecret(storageKey: String): String {
        val stored = sp.getString(storageKey, "") ?: ""
        return when (KeyVaultCodec.classify(stored)) {
            KeyVaultCodec.StoredAction.EMPTY -> ""
            KeyVaultCodec.StoredAction.DECRYPT -> KeyVault.decrypt(stored)
            KeyVaultCodec.StoredAction.PLAINTEXT_MIGRATE -> {
                try {
                    sp.edit().putString(storageKey, KeyVault.encrypt(stored)).apply()
                } catch (e: Exception) {
                    Log.w(TAG, "keyVault lazy migrate failed key=$storageKey len=${stored.length}")
                }
                stored
            }
        }
    }

    /**
     * Secret write path: blank clears the entry, anything else is stored as
     * `v1:` ciphertext. Keystore failures propagate — the caller (settings
     * save) decides how to surface them.
     */
    private fun writeSecret(storageKey: String, plain: String) {
        val trimmed = plain.trim()
        val stored = if (trimmed.isEmpty()) "" else KeyVault.encrypt(trimmed)
        sp.edit().putString(storageKey, stored).apply()
    }

    /**
     * Reply route key. Blank falls back to the legacy judge key (old installs) only
     * when the judge base shares an origin with the reply base — an old OpenRouter
     * key must never be sent to a different vendor.
     */
    @Suppress("DEPRECATION")
    fun effectiveReplyKey(): String =
        VisionRoute.keyWithSameOriginFallback(replyKey, replyBaseUrl, judgeBaseUrl, judgeKey)

    /**
     * Vision route key. Blank falls back to the reply key (then the legacy judge
     * key) only when both routes share scheme, host and port — a key is never sent
     * to a different vendor than the one it was entered for.
     */
    fun effectiveVisionKey(): String =
        VisionRoute.keyWithSameOriginFallback(visionKey, visionBaseUrl, replyBaseUrl, effectiveReplyKey())

    fun isAllowed(title: String?): Boolean {
        val wl = whitelist
        if (wl.isEmpty()) return true
        if (title == null) return false
        return wl.any { title.contains(it) }
    }

    /** Readiness gate: the reply route is the one that must be configured
     *  (bilingual is the only mode since Sprint 5 / D1). */
    fun hasKey(): Boolean = hasReplyKey()

    /** Bilingual mode only needs the reply route. */
    fun hasReplyKey(): Boolean = effectiveReplyKey().isNotBlank()

    companion object {
        private const val TAG = "JEVASSIST"

        /** The one real config file. Anything else is a scratch instance. */
        const val PREFS_MAIN = "jev_assistant"

        private const val K_LEGACY_KEY = "openrouter_key"
        private const val K_MIGRATED_V13 = "prefs_migrated_v13"
        private const val K_UNSEEDED_BOCHA = "unseeded_bocha_v141"
        private const val K_JUDGE_PROVIDER = "judge_provider"
        private const val K_JUDGE_BASE = "judge_base_url"
        private const val K_JUDGE_KEY = "judge_key"
        private const val K_JUDGE_MODEL = "judge_model"
        private const val K_REPLY_BASE = "reply_base_url"
        private const val K_REPLY_KEY = "reply_key"
        private const val K_REPLY_MODEL = "reply_model"
        private const val K_VISION_BASE = "vision_base_url"
        private const val K_VISION_KEY = "vision_key"
        private const val K_VISION_MODEL = "vision_model"
        private const val K_CTX_ENABLED = "context_enabled"
        private const val K_CTX_COUNT = "context_history_count"
        private const val K_AUTO_SUMMARY = "auto_summary"
        private const val K_OCR_ENGINE = "ocr_engine"
        private const val K_OCR_UNKNOWN = "ocr_unknown_apps"
        private const val K_OCR_FALLBACK = "ocr_fallback"
        private const val K_OCR_AUTO = "ocr_auto_analyze"
        private const val K_REL = "relationship"
        private const val K_ENABLED = "enabled"
        private const val K_WHITELIST = "whitelist"
        private const val K_REL_SKIPS = "relation_skips"
        private const val K_OPACITY = "overlay_opacity"
        private const val K_BUBBLE_Y = "bubble_y"
        private const val K_BUBBLE_X = "bubble_x"
        private const val K_BUBBLE_SNAP = "bubble_snap"
        private const val K_ONBOARDED = "onboarded"
        private const val K_AUTO = "auto_analyze"
        private const val K_BILINGUAL = "bilingual_mode"
        private const val K_ABOUT_ME = "about_me"
        private const val K_WECHAT = "wechat_enabled"
        private const val K_BILINGUAL_LANG = "bilingual_lang"
        const val DEFAULT_BILINGUAL_LANG = "德语"

        const val PROVIDER_BOCHA = "bocha"          // legacy: only read by the v1.4.0 unseed migration
        const val PROVIDER_OPENROUTER = "openrouter" // legacy default of the deprecated judge route

        const val OCR_MLKIT = "mlkit"
        const val OCR_VISION = "vision"

        // Legacy judge-route defaults: only referenced by the deprecated judge
        // getters above, so old stored data keeps decoding the same way.
        const val DEFAULT_JUDGE_BASE_OPENROUTER = "https://openrouter.ai/api"
        const val DEFAULT_JUDGE_MODEL_OPENROUTER = "typesafe/jev-1.13"

        // Reply route presets (OpenAI-compatible chat completions).
        const val DEFAULT_REPLY_BASE = "https://openrouter.ai/api/v1"
        const val DEFAULT_REPLY_MODEL = "deepseek/deepseek-chat-v3.1"
        const val DEEPSEEK_BASE = "https://api.deepseek.com/v1"
        const val DEEPSEEK_MODEL = "deepseek-chat"
        const val DASHSCOPE_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        const val DASHSCOPE_MODEL = "qwen-plus"

        // Vision route presets. The default follows the reply route, see [VisionRoute].
        const val DEFAULT_VISION_BASE = "https://openrouter.ai/api/v1"
        const val DEFAULT_VISION_MODEL = "qwen/qwen2.5-vl-72b-instruct"
        const val DEEPSEEK_VISION_MODEL = "deepseek-flash"
        const val DASHSCOPE_VISION_MODEL = "qwen-vl-max"

        const val DEFAULT_REL = "对方是我的伴侣；from=me 的是我发的，from=other 的是对方发的"
    }
}
