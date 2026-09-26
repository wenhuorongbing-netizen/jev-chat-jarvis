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
        val accent: String, val accentDeep: String, val accentLight: String,
        val accentSoft: String, val surfaceElev: String,
        val hairline: String,   // "#AARRGGBB"，发丝分割线
        val danger: String, val warn: String, val ok: String,
    )

    val LIGHT: Palette = Palette(
        ink = "#111827",
        sub = "#6B7280",
        faint = "#9CA3AF",
        surface = "#FFFFFF",
        canvas = "#F4F5F9",
        card = "#F3F4F6",
        accent = "#4F5BD5",
        accentDeep = "#3F4BC0",
        accentLight = "#8B94EC",
        accentSoft = "#EEF0FC",
        surfaceElev = "#FFFFFF",
        hairline = "#14000000",
        danger = "#DC2626",
        warn = "#D97706",
        ok = "#0E9F6E",
    )

    val DARK: Palette = Palette(
        ink = "#E5E7EB",
        sub = "#9CA3AF",
        faint = "#6B7280",
        surface = "#1A1E30",
        canvas = "#0F1220",
        card = "#262B45",
        accent = "#6B76E8",
        accentDeep = "#5A64DC",
        accentLight = "#9AA3F2",
        accentSoft = "#353B60",
        surfaceElev = "#232840",
        hairline = "#1AFFFFFF",
        danger = "#EF4444",
        warn = "#FBBF24",
        ok = "#34D399",
    )

    fun palette(dark: Boolean): Palette = if (dark) DARK else LIGHT

    // §1.2 字号（sp）——v3：整体上移取整，对齐宿主 App 阅读密度
    const val TEXT_BODY = 14f
    const val TEXT_TRANS = 14f
    const val TEXT_AUX = 12f
    const val TEXT_META = 11f

    // §1.3 圆角（dp）
    const val RADIUS_CARD = 10
    const val RADIUS_PANEL = 18

    // v2.2 动效（ms / 比例）
    const val DUR_MICRO = 100L
    const val DUR_PANEL = 160L
    const val DUR_STATE = 200L
    const val PRESS_SCALE = 0.97f
    const val BUBBLE_PRESS_SCALE = 0.88f

    /** 气泡/主按钮渐变：accentLight → accent，135°（TL_BR）。 */
    fun accentGradient(p: Palette): Array<String> = arrayOf(p.accentLight, p.accent)
}

/**
 * Android 侧小助手：把 token 的 "#RRGGBB" 字符串转成 ColorInt。
 * 顶层函数，与 UiTokens 分离——UiTokens 本体不 import android.*，
 * JVM 单测只要不调用本函数即可直接跑。
 */
fun color(hex: String): Int = android.graphics.Color.parseColor(hex)
