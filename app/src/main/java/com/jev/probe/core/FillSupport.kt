package com.jev.probe.core

/**
 * How a tapped reply reaches the input box of each chat app today
 * (contracts/jev/v1/fill_support.json is the shared copy; a test keeps the two equal).
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

object FillSupportMap {
    /** Contract app id -> status; the package names are the adapters' `pkg`. */
    val byApp: Map<String, FillSupport> = mapOf(
        "qq" to FillSupport.FRESH_VERIFIED,
        "whatsapp" to FillSupport.FRESH_VERIFIED,
        "x" to FillSupport.FRESH_VERIFIED,
        "feishu" to FillSupport.COPY_ONLY,
        "wechat" to FillSupport.COPY_ONLY,  // fully disabled in the product, and OCR-only if it were on
    )

    val packages: Map<String, String> = mapOf(
        "qq" to "com.tencent.mobileqq",
        "whatsapp" to "com.whatsapp",
        "x" to "com.twitter.android",
        "feishu" to "com.ss.android.lark",
        "wechat" to "com.tencent.mm",
    )
}
