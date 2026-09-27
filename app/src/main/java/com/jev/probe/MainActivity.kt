package com.jev.probe

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.content.res.Configuration
import com.jev.probe.core.Prefs
import com.jev.probe.core.ui.OnboardingRules
import com.jev.probe.core.ui.UiTokens
import com.jev.probe.core.ui.color
import kotlin.math.roundToInt

/**
 * Home / setup screen. Card-based layout with a live readiness summary, a
 * guided permission checklist (each row reflects its real granted state), a
 * prominent on/off switch, and a link to settings.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var container: LinearLayout
    private val a11yComponent =
        "com.jev.probe/com.google.android.accessibility.selecttospeak.SelectToSpeakService"

    private val pal by lazy {
        val dark = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        UiTokens.palette(dark)
    }
    private val accent by lazy { color(pal.accent) }
    private val green by lazy { color(pal.ok) }
    private val red by lazy { color(pal.danger) }
    private val ink by lazy { color(pal.ink) }
    private val sub by lazy { color(pal.sub) }
    private val surface by lazy { color(pal.surface) }
    private val cardBg by lazy { color(pal.card) }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.decorView.setBackgroundColor(color(pal.canvas))

        // API 33+ 通知权限：用于掉权限/掉线提醒；结果回调无需处理。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }

        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
        }
        container.padForSystemBars()   // edge-to-edge: keep the title off the status bar
        scroll.addView(container)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        container.removeAllViews()

        val a11y = isA11yEnabled()
        val overlay = Settings.canDrawOverlays(this)
        // bilingual 是唯一模式（Sprint 5 / D1），就绪只看回复接口密钥
        val key = prefs.hasReplyKey()

        // Sprint 7：未就绪时是三步向导，就绪（或跳过）后恢复原主页
        when (OnboardingRules.step(a11y, overlay, key, isOnboarded())) {
            OnboardingRules.Step.PERMISSIONS -> buildPermissionsStep(a11y, overlay)
            OnboardingRules.Step.KEY -> buildKeyStep()
            OnboardingRules.Step.DEMO -> buildDemoStep()
            OnboardingRules.Step.DONE -> buildHome(a11y, overlay, key)
        }
    }

    // ------------------------------------------------------------ onboarding

    private fun isOnboarded(): Boolean = prefs.onboarded

    /** 标记引导完成（点「我准备好了」或「跳过」），刷新进主页。 */
    private fun finishOnboarding() {
        prefs.onboarded = true
        build()
    }

    /** 每步顶部：左侧步骤指示，右侧「跳过，直接进主页」小字。 */
    private fun onboardingHeader(index: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(text("第 $index 步，共 3 步", 12f, color(pal.faint), bold = true).apply {
            letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(text("跳过，直接进主页", 12f, sub).apply {
            setPadding(dp(8), dp(4), 0, dp(4))
            setOnClickListener { finishOnboarding() }
        })
        return row
    }

    /** 第 1 步：两个必开权限 + 自启动提醒。 */
    private fun buildPermissionsStep(a11y: Boolean, overlay: Boolean) {
        container.addView(onboardingHeader(1))
        container.addView(text("两步就能用上", 22f, ink, bold = true).apply {
            setPadding(0, dp(8), 0, dp(4))
        })
        container.addView(text("先开两个权限，助手才能读到消息、把卡片贴在聊天窗口上。", 13f, sub))
        container.addView(permCard("无障碍权限", "读取当前聊天窗口的消息文字（在列表里找到『Jev 聊天助手』）", a11y) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        container.addView(permCard("悬浮窗权限", "在聊天窗口上方显示分析卡片", overlay) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        container.addView(permCard("自启动 + 省电无限制", "小米/HyperOS 必做，否则服务被冻结。设过一次即可，这里检测不到，不用重复点。", null) {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        })
    }

    /** 第 2 步：选提供方、粘贴密钥，即改即存。 */
    private fun buildKeyStep() {
        container.addView(onboardingHeader(2))
        container.addView(text("填回复接口密钥", 22f, ink, bold = true).apply {
            setPadding(0, dp(8), 0, dp(4))
        })
        container.addView(text("选一个提供方，再把密钥粘进来。密钥只存在本机。", 13f, sub))

        val c = cardBox()
        c.addView(text("回复接口密钥", 15f, ink, bold = true))
        c.addView(text("聊天内容只发往你自己配置的接口", 12f, sub).apply { setPadding(0, dp(4), 0, 0) })

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0)
        }
        chips.addView(chip("DeepSeek 官方") {
            prefs.replyBaseUrl = Prefs.DEEPSEEK_BASE
            prefs.replyModel = Prefs.DEEPSEEK_MODEL
            Toast.makeText(this, "已选 DeepSeek 官方", Toast.LENGTH_SHORT).show()
        })
        chips.addView(chip("OpenRouter") {
            prefs.replyBaseUrl = Prefs.DEFAULT_REPLY_BASE
            prefs.replyModel = Prefs.DEFAULT_REPLY_MODEL
            Toast.makeText(this, "已选 OpenRouter", Toast.LENGTH_SHORT).show()
        })
        c.addView(chips)

        val cont = continueBtn()
        val keyEdit = EditText(this).apply {
            hint = "粘贴或输入密钥"
            textSize = 14f
            setTextColor(ink)
            setHintTextColor(sub)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            background = roundBg(dp(10), cardBg)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
            setText(prefs.replyKey)
            setSelection(text.length)
        }
        // 即改即存：密钥逐字落盘（Prefs 内部加密），同时刷新「继续」按钮可用态。
        keyEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                prefs.replyKey = s?.toString().orEmpty()
                refreshContinue(cont)
            }
        })
        c.addView(keyEdit)
        c.addView(pasteBtn(keyEdit))
        container.addView(c)

        refreshContinue(cont)
        container.addView(cont)
    }

    /** 「已填好？点继续」：hasReplyKey() 为真才可用。 */
    private fun continueBtn() = TextView(this).apply {
        text = "已填好？点继续"
        textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) }
    }

    private fun refreshContinue(cont: TextView) {
        val ok = prefs.hasReplyKey()
        cont.setTextColor(if (ok) Color.WHITE else sub)
        cont.background = if (ok) accentGradientBg(14) else roundBg(dp(14), cardBg)
        if (ok) {
            cont.pressBounce()
            cont.setOnClickListener { build() }   // key 已齐，重建后进入第 3 步
        } else {
            cont.setOnClickListener(null)
        }
    }

    /** 提供方胶囊：点击回填 base/model 默认值。 */
    private fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent); background = roundBg(dp(16), color(pal.accentSoft))
        setPadding(dp(14), dp(8), dp(14), dp(8))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(8) }
        pressBounce()
        setOnClickListener { onClick() }
    }

    /** 有些 ROM 的安全键盘在密码框里不给粘贴：只读剪贴板写进输入框，不显示内容。 */
    private fun pasteBtn(target: EditText) = TextView(this).apply {
        text = "粘贴"; textSize = 13f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent); background = roundBg(dp(10), surface, stroke = true)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                ?.coerceToText(this@MainActivity)?.toString()?.trim().orEmpty()
            if (clip.isEmpty()) {
                Toast.makeText(this@MainActivity, "剪贴板是空的", Toast.LENGTH_SHORT).show()
            } else {
                target.setText(clip)
                Toast.makeText(this@MainActivity, "已粘贴（${clip.length} 位）", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 第 3 步：演示说明 + 「我准备好了」。 */
    private fun buildDemoStep() {
        container.addView(onboardingHeader(3))
        container.addView(text("最后一步，试一试", 22f, ink, bold = true).apply {
            setPadding(0, dp(8), 0, dp(4))
        })
        container.addView(text("去 WhatsApp 或 QQ 打开一个会话。气泡亮起后点它，稍等片刻，再点一条候选回复。回复只会填进输入框，发送由你点。", 14f, ink).apply {
            setPadding(0, 0, 0, dp(4))
        })
        container.addView(TextView(this).apply {
            text = "我准备好了"
            textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = accentGradientBg(14)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) }
            pressBounce()
            setOnClickListener { finishOnboarding() }
        })
    }

    /** 就绪（或引导完成）后的原主页：行为零变化。 */
    private fun buildHome(a11y: Boolean, overlay: Boolean, key: Boolean) {
        container.addView(text("Jev 聊天助手", 24f, ink, bold = true))
        container.addView(text("贴在 WhatsApp / QQ / X / 飞书旁：读出对方新消息，外语翻成中文，给 3 条候选回复。只填入输入框，发送由你点。",
            13f, sub).apply { setPadding(0, dp(8), 0, dp(16)) })

        val ready = a11y && overlay && key

        // Readiness card
        container.addView(statusCard(ready, a11y, overlay, key))
        crashHint()?.let { container.addView(it) }
        container.addView(privacyHint())

        // Permission checklist
        container.addView(sectionLabel("权限设置"))
        container.addView(permCard("无障碍权限", "读取当前聊天窗口的消息文字（在列表里找到『Jev 聊天助手』）", a11y) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        container.addView(permCard("悬浮窗权限", "在聊天窗口上方显示分析卡片", overlay) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        container.addView(permCard("自启动 + 省电无限制", "小米/HyperOS 必做，否则服务被冻结。设过一次即可，这里检测不到，不用重复点。", null) {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        })

        // Actions
        container.addView(sectionLabel("其他"))
        container.addView(actionRow("设置", "密钥 · 模型 · 关系 · 透明度 · 会话白名单") {
            startActivity(Intent(this, SettingsActivity::class.java))
        })

        // Master toggle
        val toggle = bigToggle(prefs.enabled)
        toggle.setOnClickListener {
            prefs.enabled = !prefs.enabled
            build()
        }
        container.addView(toggle)
    }

    // ---------------------------------------------------------------- cards

    private fun statusCard(ready: Boolean, a11y: Boolean, overlay: Boolean, key: Boolean): View {
        val c = cardBox()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(dot(if (ready) green else red).apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(12)
        })
        head.addView(text(if (ready) "已就绪，可以用了" else "尚未就绪", 16f, if (ready) green else ink, bold = true))
        c.addView(head)
        c.addView(checkLine("无障碍", a11y))
        c.addView(checkLine("悬浮窗", overlay))
        c.addView(checkLine("密钥", key, okWord = "已设", noWord = "未设 · 去设置 »") {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        return c
    }

    /** One tappable line under the readiness card, opening the privacy policy page. */
    private fun privacyHint(): View = text("读取的聊天内容只发往你自己配置的接口 · 隐私政策", 11f, sub).apply {
        setPadding(dp(4), dp(8), 0, 0)
        setOnClickListener { openUrl(PRIVACY_URL) }
    }

    /**
     * Crash hint under the readiness card: shown when filesDir/crash_last.log
     * exists (only existence is checked — the stack is never displayed in UI).
     * The clear button deletes the file and rebuilds the screen.
     */
    private fun crashHint(): View? {
        val log = java.io.File(filesDir, App.CRASH_LAST)
        if (!log.exists()) return null
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), 0, 0)
        }
        row.addView(text("上次运行崩溃过，日志已存在本机", 11f, red).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(text("清除", 11f, red, bold = true).apply {
            setPadding(dp(12), dp(2), dp(4), dp(2))
            setOnClickListener {
                log.delete()
                java.io.File(filesDir, App.CRASH_PREV).delete()
                build()
            }
        })
        return row
    }

    /** Opens an external link; swallows the failure with a toast rather than crashing. */
    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            Toast.makeText(this, "打不开浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkLine(label: String, ok: Boolean, okWord: String = "已开", noWord: String = "未开",
                          onMissing: (() -> Unit)? = null): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        row.addView(text(if (ok) "✓" else "✗", 14f, if (ok) green else red, bold = true).apply {
            (this as TextView).width = dp(22)
        })
        // 未达标且给了去向时整行可点：文字换 accent 色，作为 onboarding 的直达入口。
        val fixable = !ok && onMissing != null
        row.addView(text(label + (if (ok) okWord else noWord), 13f, if (fixable) accent else sub))
        if (fixable) row.setOnClickListener { onMissing?.invoke() }
        return row
    }

    private fun permCard(title: String, desc: String, granted: Boolean?, onClick: () -> Unit): View {
        val c = cardBox()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(text(title, 15f, ink, bold = true))
        left.addView(text(desc, 12f, sub).apply { setPadding(0, dp(4), 0, 0) })
        if (granted == true) left.addView(text("✓ 已开启", 12f, green, bold = true).apply { setPadding(0, dp(4), 0, 0) })
        row.addView(left)
        // granted==true 时右侧不再放「已开启」灰按钮，只保留左侧绿字。
        if (granted != true) row.addView(btn("去开启", true, onClick))
        c.addView(row)
        return c
    }

    private fun actionRow(title: String, desc: String, onClick: () -> Unit): View {
        val c = cardBox()
        c.setOnClickListener { onClick() }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(text(title, 15f, ink, bold = true))
        left.addView(text(desc, 12f, sub).apply { setPadding(0, dp(4), 0, 0) })
        row.addView(left)
        row.addView(text("›", 22f, sub))
        c.addView(row)
        return c
    }

    private fun bigToggle(on: Boolean): View {
        return TextView(this).apply {
            text = if (on) "助手已开启 · 点击关闭" else "助手已关闭 · 点击开启"
            textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (on) Color.WHITE else accent)
            // v2.1：开启态改 accentLight → accent 渐变；关闭态保持描边样式（accent 语义描边）。
            background = if (on) accentGradientBg(14) else roundBg(dp(14), surface, stroke = true)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) }
            pressBounce()
        }
    }

    // ---------------------------------------------------------------- atoms

    private fun cardBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundBg(dp(14), surface)
        elevation = dp(2).toFloat()   // v2.3：卡片柔和投影
        clipToOutline = true
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
    }

    // v2.3：section 标题 12sp 粗体 + letterSpacing 0.08，颜色 faint。
    private fun sectionLabel(t: String) = text(t, 12f, color(pal.faint), bold = true).apply {
        letterSpacing = 0.08f
        setPadding(dp(4), dp(20), 0, dp(4))
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dot(color: Int) = View(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
    }

    private fun btn(label: String, enabled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (enabled) Color.WHITE else sub)
        // v2.1：可点的主操作换渐变；已开启的灰态保持 pal.card。
        background = if (enabled) accentGradientBg(10) else roundBg(dp(10), cardBg)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        if (enabled) {
            pressBounce()
            setOnClickListener { onClick() }
        }
    }

    /** v2.1 主按钮渐变：accentLight → accent（TL_BR，135°），圆角不变。 */
    private fun accentGradientBg(radiusDp: Int) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        UiTokens.accentGradient(pal).map { color(it) }.toIntArray()
    ).apply { cornerRadius = dp(radiusDp).toFloat() }

    /** v2.2 按压反馈：按下 scale 0.98（100ms），松开 Overshoot 回弹（250ms）；返回 false 不吞 click。 */
    private fun View.pressBounce() {
        setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> v.animate()
                    .scaleX(0.98f).scaleY(0.98f).setDuration(UiTokens.DUR_MICRO).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate()
                    .scaleX(1f).scaleY(1f).setDuration(250L)
                    .setInterpolator(OvershootInterpolator(2f)).start()
            }
            false
        }
    }

    private fun roundBg(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = radius.toFloat(); setColor(color)
        if (stroke) setStroke(dp(1), accent)
    }

    private fun isA11yEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains(a11yComponent)
    }

    companion object {
        private const val PRIVACY_URL = "https://chatjevs.com/privacy.html"
        private const val REQ_NOTIFICATIONS = 41
    }
}
