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
        // SPEC A3：原始错误信息不进 UI（可进 logcat，只打长度/条数）。
        return ErrorView("出错了，稍后再试", ErrorAction.RETRY, "重试")
    }

    /**
     * Sprint 7「换一条」闭环：同一轮里被「换一条」否定的回复，生成下一轮
     * prompt 时要避开。空列表 → ""；否则取最近 [maxKeep] 条（每条截断 40 字），
     * 拼成一句中文指令：「用户否定了这些回复，换不同角度：'xx'；'yy'」。
     */
    fun rerollNote(rejected: List<String>, maxKeep: Int = 3): String {
        if (rejected.isEmpty() || maxKeep <= 0) return ""
        val quoted = rejected.takeLast(maxKeep).joinToString("；") { "'${it.take(40)}'" }
        return "用户否定了这些回复，换不同角度：$quoted"
    }

    /**
     * Sprint 7 气泡吸边（可选，默认关）：松手后吸到较近一侧边，距边 [margin]
     * （吸边仍保留边距躲 MIUI 边缘手势，不贴 0）。[x] 是气泡左缘；气泡中心在
     * 屏幕中线左半 → 吸左边，否则吸右边；恰好居中归右侧。
     */
    fun snappedX(x: Int, screenW: Int, bubbleW: Int, margin: Int): Int {
        val right = screenW - bubbleW - margin
        if (right <= margin) return margin.coerceAtLeast(0)  // 退化窄屏：不抛异常
        val center = x + bubbleW / 2
        return if (center < screenW / 2) margin else right
    }
}
