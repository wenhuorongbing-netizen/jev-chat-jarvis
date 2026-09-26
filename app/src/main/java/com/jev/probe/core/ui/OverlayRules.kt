package com.jev.probe.core.ui

/**
 * 悬浮层纯逻辑规则。规格：docs/ui-polish-android-spec.md §1.4 / §2.2。
 * 零 Android 依赖，可 JVM 单测。
 */
object OverlayRules {

    enum class BubbleState { IDLE, LOADING, READY, ERROR }

    data class BubbleVisual(val label: String, val alpha: Float, val danger: Boolean)

    /** §1.4 气泡状态矩阵。 */
    fun bubbleVisual(s: BubbleState): BubbleVisual = when (s) {
        BubbleState.IDLE -> BubbleVisual("Jev", 0.5f, false)
        BubbleState.LOADING -> BubbleVisual("···", 0.9f, false)
        BubbleState.READY -> BubbleVisual("✓", 1f, false)
        BubbleState.ERROR -> BubbleVisual("!", 1f, true)
    }

    /** P0-1 同源规则：中文对话或 gloss 与正文相同 → 不显示灰字。 */
    fun shouldShowGloss(lang: String, gloss: String, body: String): Boolean {
        if (lang.isBlank()) return false
        if (lang.trim().equals("中文", ignoreCase = true)) return false
        if (gloss.isBlank()) return false
        if (gloss == body) return false
        return true
    }

    /** P1-7：把原始错误映射成「一句人话 + 一个动作」。 */
    enum class ErrorAction { NONE, OPEN_SETTINGS, RETRY }

    data class ErrorView(val message: String, val action: ErrorAction, val actionLabel: String?)

    fun errorView(raw: String, hasReplyKey: Boolean): ErrorView {
        if (!hasReplyKey) {
            return ErrorView("还没填回复接口的 key", ErrorAction.OPEN_SETTINGS, "去设置")
        }
        val lower = raw.lowercase()
        if (lower.contains("401") || lower.contains("403") ||
            lower.contains("key") || raw.contains("密钥")
        ) {
            return ErrorView("回复接口的 key 不对或已过期", ErrorAction.OPEN_SETTINGS, "去设置")
        }
        if (lower.contains("timeout") || raw.contains("超时") ||
            lower.contains("unable to resolve") || raw.contains("网络")
        ) {
            return ErrorView("网络连不上，稍后再试", ErrorAction.RETRY, "重试")
        }
        return ErrorView("出错了：${raw.take(80)}", ErrorAction.RETRY, "重试")
    }

    /** 设置页「高级」折叠：智能回复开 → 默认折叠（P1-6）。 */
    fun foldAdvancedByDefault(bilingualMode: Boolean): Boolean = bilingualMode
}
