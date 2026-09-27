package com.jev.probe.core.ui

/**
 * 首次三步引导流的纯判定逻辑：权限 → 密钥 → 演示。
 *
 * 零 Android 依赖（不 import android.*），app/src/test 下用 JUnit4 在 JVM 直接跑。
 * 本 object 不管持久化：DEMO 步点「完成」或任意步点「跳过」后写入的 onboarded
 * 标记由调用方（MainActivity / Prefs）负责读写，这里只把标记当作入参。
 */
object OnboardingRules {

    enum class Step { PERMISSIONS, KEY, DEMO, DONE }

    /**
     * a11y / overlay / key 三条件 → 当前该停在哪一步。
     * 权限缺一 → PERMISSIONS；权限齐但缺 key → KEY；全齐 → DEMO。
     */
    fun step(a11y: Boolean, overlay: Boolean, key: Boolean): Step = when {
        !a11y || !overlay -> Step.PERMISSIONS
        !key -> Step.KEY
        else -> Step.DEMO
    }

    /** 已标记完成引导（onboarded）时直达 DONE，恢复原主页。 */
    fun step(a11y: Boolean, overlay: Boolean, key: Boolean, onboarded: Boolean): Step =
        if (onboarded) Step.DONE else step(a11y, overlay, key)
}
