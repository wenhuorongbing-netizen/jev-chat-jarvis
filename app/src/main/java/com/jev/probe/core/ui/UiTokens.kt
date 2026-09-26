package com.jev.probe.core.ui

/**
 * 设计 token。规格：docs/ui-polish-android-spec.md §1 / §2.1。
 *
 * 本 object 零 Android 依赖（不 import android.*），颜色一律以 "#RRGGBB"
 * 字符串表达，保证 app/src/test 下可用 JUnit4 在 JVM 上直接跑。
 */
object UiTokens {

    data class Palette(
        val ink: String, val sub: String, val faint: String,
        val surface: String, val canvas: String, val card: String,
        val accent: String, val accentSoft: String,
        val danger: String, val warn: String, val ok: String,
    )

    val LIGHT: Palette = Palette(
        ink = "#111827",
        sub = "#6B7280",
        faint = "#9CA3AF",
        surface = "#FFFFFF",
        canvas = "#F5F6F8",
        card = "#F3F4F6",
        accent = "#4F5BD5",
        accentSoft = "#E4E6FA",
        danger = "#DC2626",
        warn = "#D97706",
        ok = "#16A34A",
    )

    val DARK: Palette = Palette(
        ink = "#E5E7EB",
        sub = "#9CA3AF",
        faint = "#6B7280",
        surface = "#1F2937",
        canvas = "#111827",
        card = "#374151",
        accent = "#6B76E8",
        accentSoft = "#33374F",
        danger = "#EF4444",
        warn = "#FBBF24",
        ok = "#34D399",
    )

    fun palette(dark: Boolean): Palette = if (dark) DARK else LIGHT

    // §1.2 字号（sp）
    const val TEXT_BODY = 13.5f
    const val TEXT_TRANS = 13.5f
    const val TEXT_AUX = 11.5f
    const val TEXT_META = 10.5f

    // §1.3 圆角（dp）
    const val RADIUS_CARD = 10
    const val RADIUS_PANEL = 14
}

/**
 * Android 侧小助手：把 token 的 "#RRGGBB" 字符串转成 ColorInt。
 * 顶层函数，与 UiTokens 分离——UiTokens 本体不 import android.*，
 * JVM 单测只要不调用本函数即可直接跑。
 */
fun color(hex: String): Int = android.graphics.Color.parseColor(hex)
