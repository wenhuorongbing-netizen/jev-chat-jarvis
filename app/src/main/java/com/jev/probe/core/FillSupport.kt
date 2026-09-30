package com.jev.probe.core

/**
 * How a tapped reply reaches the input box of an app. Each adapter declares its own
 * (`ChatAppAdapter.fillSupport`) and the fill path obeys it; contracts/jev/v1/fill_support.json
 * is the shared description, and a test asserts the two are equal — the contract follows
 * production, it does not steer it.
 *
 * - [FRESH_VERIFIED]: the target is re-read right before the write and its
 *   visible-message signature has to match ([FillGuard]).
 * - [COPY_ONLY]: the text is not in the accessibility tree (OCR-only), so there is
 *   no signature to prove the conversation; the reply is copied, the person pastes.
 */
enum class FillSupport(val wire: String) {
    FRESH_VERIFIED("fresh-verified"),
    COPY_ONLY("copy-only")
}

/** Contract app ids and their packages (WhatsApp Business shares the `whatsapp` id). */
object FillSupportMap {
    val packages: Map<String, List<String>> = mapOf(
        "qq" to listOf("com.tencent.mobileqq"),
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "x" to listOf("com.twitter.android"),
        "feishu" to listOf("com.ss.android.lark"),
        "wechat" to listOf("com.tencent.mm"),
    )

    fun appOf(pkg: String): String? = packages.entries.firstOrNull { pkg in it.value }?.key
}
