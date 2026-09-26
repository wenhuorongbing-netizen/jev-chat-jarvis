package com.jev.probe.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jev.probe.core.Analysis
import com.jev.probe.core.BilingualResult
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import com.jev.probe.core.ui.OverlayRules
import com.jev.probe.core.ui.OverlayRules.BubbleState
import com.jev.probe.core.ui.UiTokens
import com.jev.probe.core.ui.color
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Floating overlay: a small draggable bubble that expands into a translucent
 * panel showing Jev's read of the chat plus 3 ranked candidate replies. All
 * actions are copy / fill — never send.
 *
 * Design goals: let the chat show through (adjustable opacity), keep the signal
 * scannable (danger badge + intent headline + reply cards), and stay out of the
 * way (draggable bubble that snaps to the edge and remembers its position).
 *
 * Colors come from [UiTokens] (light/dark by system uiMode); bubble states from
 * [OverlayRules]. No hex color literals live in this file.
 */
class OverlayController(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = Prefs(ctx)
    private var root: FrameLayout? = null
    private var bubble: TextView? = null
    private var dangerDot: View? = null
    private var panel: LinearLayout? = null
    private var contentBox: LinearLayout? = null
    private var expanded = false
    private var lp: WindowManager.LayoutParams? = null

    /** Current palette: re-read on every use so a uiMode flip is picked up. */
    private val pal get() = UiTokens.palette(isDark())

    var onManualAnalyze: (() -> Unit)? = null

    /** Bubble menu → file the open conversation as a knowledge-base contact. */
    var onSaveContact: (() -> Unit)? = null

    /** Bubble menu → one manual screenshot + OCR of whatever app is open. */
    var onOcrCapture: (() -> Unit)? = null

    /** How much knowledge context the last analysis actually used. */
    private var ctxNotes = 0
    private var ctxHistory = 0

    /** A caveat about how the current snapshot was captured (OCR mode). */
    private var noteText: String? = null

    /** Whether the overlay window is currently on screen. */
    fun isShowing(): Boolean = root != null

    private var lastJudgment: Analysis? = null
    private var lastFill: ((String) -> Unit)? = null

    /** Set when [showReplies] was handed a draftAndRank failure, so the panel
     *  can say so instead of silently showing "（未生成候选回复）". */
    private var replyError: String? = null

    private fun isDark(): Boolean =
        ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).roundToInt()

    private fun canOverlay(): Boolean = Settings.canDrawOverlays(ctx)

    private val screenW get() = ctx.resources.displayMetrics.widthPixels
    private val screenH get() = ctx.resources.displayMetrics.heightPixels

    /** Panel background: palette.surface with the user's opacity so the chat shows through. */
    private fun panelBg(): Int {
        val a = (prefs.overlayOpacity / 100f * 255).roundToInt().coerceIn(150, 255)
        val s = color(pal.surface)
        return Color.argb(a, Color.red(s), Color.green(s), Color.blue(s))
    }

    private fun card(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(color)
        if (stroke) setStroke(dp(1), color(pal.hairline))
    }

    // ---------------------------------------------------------------- window

    private fun ensureRoot() {
        if (root != null) return
        if (!canOverlay()) { android.util.Log.w("JEVASSIST", "overlay: canDrawOverlays=false"); return }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.bubbleX in 0..(screenW - dp(BUBBLE))) prefs.bubbleX else dp(8)
            y = if (prefs.bubbleY >= 0) prefs.bubbleY else dp(150)
        }
        lp = params

        val r = FrameLayout(ctx)
        val p = buildPanel()
        val bubbleWrap = buildBubble(params)
        r.addView(p)
        r.addView(bubbleWrap)
        root = r
        try { wm.addView(r, params) } catch (e: Exception) {
            android.util.Log.e("JEVASSIST", "overlay addView failed: ${e.message}"); root = null
        }
    }

    private fun buildBubble(params: WindowManager.LayoutParams): View {
        val wrap = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(dp(BUBBLE), dp(BUBBLE))
        }
        val b = TextView(ctx).apply {
            text = "Jev"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            background = bubbleBg(danger = false)
            elevation = dp(4).toFloat()
            layoutParams = FrameLayout.LayoutParams(dp(BUBBLE), dp(BUBBLE))
        }
        val dot = View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) }
            layoutParams = FrameLayout.LayoutParams(dp(12), dp(12)).apply {
                gravity = Gravity.TOP or Gravity.END
            }
        }
        wrap.addView(b)
        wrap.addView(dot)
        attachBubbleTouch(wrap, params)
        bubble = b; dangerDot = dot
        return wrap
    }

    private fun buildPanel(): LinearLayout {
        val p = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = card(UiTokens.RADIUS_PANEL, panelBg(), stroke = true)
            elevation = dp(8).toFloat()
            clipToOutline = true   // rounded outline follows the GradientDrawable corners
            setPadding(dp(10), dp(6), dp(10), dp(8))
            layoutParams = FrameLayout.LayoutParams(dp(PANEL_W), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(BUBBLE + 4) // sit just below the bubble
            }
        }
        // Header: one thin row — what language the other side wrote in, then icons.
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(ctx).apply {
            text = ""; setTextColor(color(pal.faint)); textSize = 11f
            letterSpacing = 0.02f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }.also { headerLabel = it })
        header.addView(iconBtn("↻") { onManualAnalyze?.invoke() })
        header.addView(iconBtn("⚙") { openSettings() })
        header.addView(iconBtn("✕") { toggle() })
        p.addView(header)

        // Wraps its content; scrolls only past 55% of the screen, so a normal
        // result (translation + 3 short replies) never needs scrolling.
        val scroll = object : ScrollView(ctx) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cap = View.MeasureSpec.makeMeasureSpec((screenH * 0.55f).roundToInt(), View.MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, cap)
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        p.addView(scroll)
        contentBox = content
        panel = p
        return p
    }

    private var headerLabel: TextView? = null

    private fun iconBtn(glyph: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = glyph; setTextColor(color(pal.faint)); textSize = 14f
        setPadding(dp(9), dp(2), dp(5), dp(2))
        setOnClickListener { onClick() }
    }

    // --------------------------------------------------------------- gestures

    private fun attachBubbleTouch(v: View, params: WindowManager.LayoutParams) {
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        var moved = false; var downTime = 0L; var longFired = false
        val longPress = Runnable {
            if (!moved) { longFired = true; showBubbleMenu() }
        }
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; touchX = e.rawX; touchY = e.rawY
                    moved = false; longFired = false; downTime = System.currentTimeMillis()
                    bubblePress(true)
                    v.postDelayed(longPress, 500); true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt(); val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    // Keep a margin from both side edges: the extreme edge is MIUI's
                    // back-gesture zone, which steals touches and makes the bubble
                    // "stuck". Free positioning (no forced edge snap) also avoids it.
                    params.x = (startX + dx).coerceIn(dp(8), screenW - dp(60))
                    params.y = (startY + dy).coerceIn(dp(24), screenH - dp(120))
                    root?.let { runCatching { wm.updateViewLayout(it, params) } }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    bubblePress(false)  // dragged or not, the spring-back always lands
                    if (longFired) { true }
                    else if (moved) {
                        prefs.bubbleX = params.x; prefs.bubbleY = params.y; true  // stays where dropped
                    } else { toggle(); true }
                }
                MotionEvent.ACTION_CANCEL -> { v.removeCallbacks(longPress); bubblePress(false); true }
                else -> false
            }
        }
    }

    /** Press juiciness on the bubble itself (not the drag wrap): down shrinks,
     *  release springs back with a light overshoot. */
    private fun bubblePress(down: Boolean) {
        val b = bubble ?: return
        b.animate().cancel()
        if (down) {
            b.animate().scaleX(UiTokens.BUBBLE_PRESS_SCALE).scaleY(UiTokens.BUBBLE_PRESS_SCALE)
                .setDuration(UiTokens.DUR_MICRO).start()
        } else {
            b.animate().scaleX(1f).scaleY(1f).setDuration(250)
                .setInterpolator(OvershootInterpolator(2.0f)).start()
        }
    }

    private fun showBubbleMenu() {
        val menu = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = card(12, color(pal.surfaceElev), stroke = true)
            elevation = dp(8).toFloat()
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = FrameLayout.LayoutParams(dp(196), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(BUBBLE + 4) }
        }
        val auto = prefs.autoAnalyze
        menu.addView(menuItem(if (auto) "新消息自动生成：开（点关）" else "新消息自动生成：关（点开）") {
            prefs.autoAnalyze = !auto; root?.removeView(menu)
            toast(if (auto) "已关闭：点气泡才生成" else "已开启：来新消息就在后台生成")
        })
        menu.addView(menuItem("截屏识别一次") { root?.removeView(menu); onOcrCapture?.invoke() })
        menu.addView(menuItem("把当前会话存为联系人") { onSaveContact?.invoke(); root?.removeView(menu) })
        menu.addView(menuItem("打开设置") { openSettings(); root?.removeView(menu) })
        menu.addView(menuItem("隐藏助手（本次）") { hide() })
        menu.addView(menuItem("取消") { root?.removeView(menu) })
        root?.addView(menu)
    }

    private fun menuItem(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; setTextColor(color(pal.ink)); textSize = 14f
        setPadding(dp(12), dp(10), dp(12), dp(10)); setOnClickListener { onClick() }
    }

    private fun openSettings() {
        runCatching {
            ctx.startActivity(Intent().setClassName(ctx, "com.jev.probe.SettingsActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (expanded) toggle()
    }

    private var collapsedX = dp(6)
    private var collapsedY = dp(150)

    /** True while an expand/collapse animation is in flight. */
    private var panelAnimating = false

    private fun toggle() {
        val params = lp ?: return
        val p = panel
        if (panelAnimating && p != null) {
            // Toggled mid-animation: stop the running one quietly and jump
            // straight to the final state of this toggle — never stack a
            // second animation on top of the first.
            p.animate().setListener(null).cancel()
            panelAnimating = false
            expanded = !expanded
            if (expanded) {
                // collapsedX/collapsedY already hold the bubble's spot from the
                // original expand — params.x is the panel position right now, so
                // re-saving it here would strand the bubble at the panel corner.
                params.x = dp(6)
                val maxTop = (screenH * 0.14f).roundToInt()
                if (params.y > maxTop) params.y = maxTop
                p.visibility = View.VISIBLE; p.alpha = 1f; p.translationY = 0f
                if (!hasResult && !loading) root?.post { onManualAnalyze?.invoke() }
            } else {
                p.visibility = View.GONE; p.alpha = 1f; p.translationY = 0f
                params.x = collapsedX; params.y = collapsedY
            }
            android.util.Log.d("JEVASSIST", "overlay: toggle expanded=$expanded x=${params.x} y=${params.y} saved=($collapsedX,$collapsedY)")
            root?.let { runCatching { wm.updateViewLayout(it, params) } }
            return
        }
        expanded = !expanded
        if (expanded) {
            // Open the panel from the left, fully on-screen and up high (clear of the
            // input box), regardless of which edge the bubble was snapped to.
            collapsedX = params.x; collapsedY = params.y
            params.x = dp(6)
            val maxTop = (screenH * 0.14f).roundToInt()
            if (params.y > maxTop) params.y = maxTop
            if (p != null) {
                panelAnimating = true
                p.visibility = View.VISIBLE
                p.alpha = 0f
                p.translationY = dp(12).toFloat()
                p.animate().alpha(1f).translationY(0f).setDuration(UiTokens.DUR_PANEL)
                    .setInterpolator(OvershootInterpolator(0.9f))
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            p.alpha = 1f; p.translationY = 0f
                            panelAnimating = false
                        }
                        override fun onAnimationCancel(animation: Animator) { panelAnimating = false }
                    })
                    .start()
            }
            // Opening with nothing ready for this chat = "generate now".
            if (!hasResult && !loading) root?.post { onManualAnalyze?.invoke() }
            android.util.Log.d("JEVASSIST", "overlay: toggle expanded=$expanded x=${params.x} y=${params.y} saved=($collapsedX,$collapsedY)")
            root?.let { runCatching { wm.updateViewLayout(it, params) } }
        } else {
            // Collapse: hide the panel and hand the window position back to the
            // bubble only after the fade+slide has finished.
            if (p != null && p.visibility == View.VISIBLE) {
                panelAnimating = true
                p.animate().alpha(0f).translationY(dp(12).toFloat()).setDuration(UiTokens.DUR_PANEL)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) { finishCollapse(p, params) }
                        override fun onAnimationCancel(animation: Animator) { panelAnimating = false }
                    })
                    .start()
            } else {
                params.x = collapsedX; params.y = collapsedY  // bubble returns to where it was
                root?.let { runCatching { wm.updateViewLayout(it, params) } }
            }
            android.util.Log.d("JEVASSIST", "overlay: toggle expanded=$expanded x=${params.x} y=${params.y} saved=($collapsedX,$collapsedY)")
        }
    }

    private fun finishCollapse(p: View, params: WindowManager.LayoutParams) {
        panelAnimating = false
        p.visibility = View.GONE
        p.alpha = 1f; p.translationY = 0f
        params.x = collapsedX; params.y = collapsedY
        root?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    // ------------------------------------------------------------ public API

    fun showIdle(title: String?) {
        ensureRoot()
        if (!hasResult && !loading) setBubble(BubbleState.IDLE)
        // An empty panel must never stay literally blank (root may have been
        // rebuilt after hide()).
        if (contentBox?.childCount == 0) setContent(listOf(hint("点 ↻ 生成回复")))
    }

    /** hasResult: the panel holds replies for the current chat; loading: a round is in flight. */
    private var hasResult = false
    private var loading = false

    /** Bubble background: accentLight→accent TL_BR gradient normally, solid danger
     *  red in the ERROR state. */
    private fun bubbleBg(danger: Boolean): GradientDrawable =
        if (danger) {
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(pal.danger)) }
        } else {
            val g = UiTokens.accentGradient(pal).map { color(it) }.toIntArray()
            GradientDrawable(GradientDrawable.Orientation.TL_BR, g).apply {
                shape = GradientDrawable.OVAL
            }
        }

    /** Last solid fill color shown on the bubble (gradient end color counts) —
     *  the crossfade's starting point. */
    private var bubbleTint: Int? = null
    private var bubbleFade: ValueAnimator? = null

    /** The bubble carries the state, so the panel never has to pop open by itself. */
    private fun setBubble(s: BubbleState) {
        val b = bubble ?: return
        val visual = OverlayRules.bubbleVisual(s)
        b.text = visual.label   // label switches instantly; only the color crossfades
        b.alpha = visual.alpha
        val target = color(if (visual.danger) pal.danger else pal.accent)
        val from = bubbleTint
        bubbleFade?.cancel()
        if (from == null || from == target) {
            b.background = bubbleBg(visual.danger)
            bubbleTint = target
            return
        }
        // ArgbEvaluator can't interpolate a gradient, so the fade runs on a
        // solid fill and the gradient comes back when it lands.
        val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(from) }
        b.background = bg
        bubbleFade = ValueAnimator.ofObject(ArgbEvaluator(), from, target).apply {
            duration = UiTokens.DUR_STATE
            addUpdateListener {
                val c = it.animatedValue as Int
                bg.setColor(c); bubbleTint = c
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    b.background = bubbleBg(visual.danger)
                    bubbleTint = target
                }
            })
            start()
        }
    }

    /**
     * Drop whatever judgment/candidates/note belonged to the previous
     * conversation. Call this before showing anything for a different chat
     * window (a different app, or new content in the same one) — otherwise a
     * leftover [lastJudgment] from a prior conversation can keep [showIdle]
     * from putting the "分析当前对话" button back, and a leftover [lastFill]
     * could fill the wrong chat's input box.
     */
    fun resetForNewConversation() {
        lastJudgment = null
        lastFill = null
        noteText = null
        replyError = null
        hasResult = false; loading = false
        headerLabel?.text = ""
        contentBox?.removeAllViews()
        setBubble(BubbleState.IDLE)
    }

    private fun bigButton(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        background = card(12, color(pal.accent))
        setPadding(dp(12), dp(11), dp(12), dp(11))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
        setOnClickListener { onClick() }
    }

    /** Loading placeholder: three skeleton cards with a staggered alpha shimmer.
     *  Each card's animator is stashed in its tag; [setContent]/[hide] cancel it. */
    private fun skeletonCards(): List<View> = (0..2).map { i ->
        val v = View(ctx)
        v.background = card(UiTokens.RADIUS_CARD, color(pal.card))
        v.alpha = 0.35f
        v.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(5) }
        val shimmer = ValueAnimator.ofFloat(0.35f, 0.65f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            startDelay = i * 150L
            addUpdateListener { v.alpha = it.animatedValue as Float }
            start()
        }
        v.tag = shimmer
        v
    }

    /** [open]: legacy Jev mode still opens the panel; bilingual mode only
     *  animates the bubble and waits for the user to tap it. */
    fun showLoading(open: Boolean = false) {
        ensureRoot()
        ctxNotes = 0; ctxHistory = 0   // counts for the round that is starting
        replyError = null              // this round has not failed (yet)
        loading = true; hasResult = false
        setBubble(BubbleState.LOADING)
        setContent(skeletonCards())
        if (open && !expanded) toggle()
    }

    /** How many knowledge notes / history lines went into the pending analysis. */
    fun setContextInfo(notes: Int, history: Int) {
        ctxNotes = notes; ctxHistory = history
    }

    /** A caveat line for the panel (OCR mode); null clears it. */
    fun setNote(note: String?) {
        noteText = note
    }

    /**
     * Take the overlay out of the picture for one screenshot. INVISIBLE, not
     * removed: the window (and everything on it) must survive the round trip.
     */
    fun setHiddenForShot(hidden: Boolean) {
        root?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    fun showError(msg: String) {
        ensureRoot()
        loading = false; hasResult = true  // tapping shows the error instead of retrying blindly
        setBubble(BubbleState.ERROR)
        val ev = OverlayRules.errorView(msg, prefs.effectiveReplyKey().isNotBlank())
        // 原始错误不进 UI（SPEC A3）；logcat 按隐私规则只打长度，不打内容。
        android.util.Log.d("JEVASSIST", "overlay: error shown raw.len=${msg.length}")
        val views = ArrayList<View>()
        views.add(line(ev.message, color(pal.danger), 14f, true))
        ev.actionLabel?.let { label ->
            views.add(bigButton(label) {
                when (ev.action) {
                    OverlayRules.ErrorAction.OPEN_SETTINGS -> openSettings()
                    OverlayRules.ErrorAction.RETRY -> onManualAnalyze?.invoke()
                    OverlayRules.ErrorAction.NONE -> {}
                }
            })
        }
        setContent(views)
    }

    /**
     * A neutral one-time notice (used when the foreground is WeChat, which is
     * fully disabled). Not framed as an error: shows the bubble, drops any stale
     * judgment from the previous chat, puts the message in the panel and opens it
     * once so the user actually reads it. Never auto-dismisses (unlike a toast)
     * and never takes input focus (the overlay window is FLAG_NOT_FOCUSABLE).
     */
    fun showNotice(msg: String) {
        ensureRoot(); bubble?.alpha = 1f
        resetForNewConversation()
        setContent(listOf(
            line("提示", color(pal.accent), 14f, true),
            hint(msg)))
        if (!expanded) toggle()
    }

    fun showJudgment(a: Analysis) {
        lastJudgment = a
        render(a, generating = true)
    }

    fun showReplies(ranked: List<RankedReply>, error: String? = null, onFill: (String) -> Unit) {
        lastFill = onFill
        replyError = error
        // 隐私：失败细节只打长度，不打内容（SPEC A3，原始错误不进 UI）。
        if (error != null) android.util.Log.d("JEVASSIST", "overlay: replies failed err.len=${error.length}")
        val a = lastJudgment?.copy(rankedReplies = ranked) ?: return
        lastJudgment = a
        render(a, generating = false)
    }

    /** Bilingual mode: translation of the other side, then 3 target-language
     *  replies with Chinese glosses. "填入" fills only the target-language text. */
    fun showBilingual(r: BilingualResult, onFill: (String) -> Unit) {
        ensureRoot()
        // 不透明：候选回复要读清楚，聊天内容透过来会和中文释义叠在一起
        panel?.background = card(UiTokens.RADIUS_PANEL, color(pal.surface), stroke = true)
        lastFill = onFill
        lastJudgment = null
        loading = false; hasResult = true
        setBubble(BubbleState.READY)
        val zhOnly = r.lang.isBlank() || r.lang == "中文"
        headerLabel?.text = if (zhOnly) "点一条填入 · 长按复制" else "对方 · ${r.lang}　点一条填入"
        val views = ArrayList<View>()
        // 中文对话不显示译文行（与 Windows P0-3 同规则）；lang 缺失时仍按外语处理
        if (r.translation.isNotBlank() && r.lang != "中文") views.add(line(r.translation, color(pal.ink), UiTokens.TEXT_TRANS, true))
        r.replies.forEachIndexed { i, reply ->
            // 中文对话或释义与正文相同时不显示灰字（与 Windows P0-1 同规则）
            val gloss = if (OverlayRules.shouldShowGloss(r.lang, reply.zh, reply.text)) reply.zh else ""
            views.add(replyCard(i + 1, reply.text, -1, onFill, gloss))
        }
        // 次要信息放最底下、字最小：分析一句话，其余是来源说明
        if (r.analysis.isNotBlank()) views.add(line(r.analysis, color(pal.sub), UiTokens.TEXT_AUX).apply { setPadding(0, dp(6), 0, 0) })
        val meta = ArrayList<String>()
        if (ctxNotes > 0 || ctxHistory > 0) meta.add("知识库 $ctxNotes · 历史 $ctxHistory")
        noteText?.takeIf { it.isNotBlank() }?.let { meta.add(it) }
        if (meta.isNotEmpty()) views.add(line(meta.joinToString(" · "), color(pal.faint), UiTokens.TEXT_META))
        setContent(views)
    }

    /** In-panel snackbar: a small dark pill at the bottom of the overlay root,
     *  fading in, holding ~1.6s, then fading out. Replaces itself on repeat calls. */
    private var snackbar: TextView? = null

    fun snackbar(msg: String) {
        val r = root ?: return
        snackbar?.let { old ->
            old.animate().cancel()
            old.clearAnimation()
            r.removeView(old)
        }
        val pill = TextView(ctx).apply {
            text = msg; setTextColor(Color.WHITE); textSize = 12f
            background = card(20, Color.argb(199, 0, 0, 0)) // 黑 78%
            setPadding(dp(14), dp(8), dp(14), dp(8))
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(24)
            }
        }
        snackbar = pill
        r.addView(pill)
        pill.animate().alpha(1f).setDuration(120).withEndAction {
            pill.postDelayed({
                pill.animate().alpha(0f).setDuration(200).withEndAction {
                    r.removeView(pill)
                    if (snackbar === pill) snackbar = null
                }.start()
            }, 1600)
        }.start()
    }

    /** Every in-file notice routes to the in-panel snackbar (never a system Toast). */
    fun toast(msg: String) = snackbar(msg)

    fun hide() {
        val r = root ?: return
        cancelContentAnimators()
        bubbleFade?.cancel(); bubbleFade = null; bubbleTint = null
        snackbar?.let { it.animate().cancel(); it.clearAnimation(); r.removeView(it) }
        snackbar = null
        runCatching { wm.removeView(r) }
        root = null; bubble = null; panel = null; contentBox = null; dangerDot = null; expanded = false
        hasResult = false; loading = false; headerLabel = null
        panelAnimating = false
    }

    // --------------------------------------------------------------- rendering

    /** Cancel per-child animators (skeleton shimmer) before dropping views. */
    private fun cancelContentAnimators() {
        val c = contentBox ?: return
        for (i in 0 until c.childCount) (c.getChildAt(i).tag as? ValueAnimator)?.cancel()
    }

    private fun setContent(views: List<View>) {
        val c = contentBox ?: return
        cancelContentAnimators()
        c.removeAllViews(); views.forEach { c.addView(it) }
    }

    private fun render(a: Analysis, generating: Boolean) {
        ensureRoot(); bubble?.alpha = 1f
        loading = generating; hasResult = true
        panel?.background = card(UiTokens.RADIUS_PANEL, panelBg(), stroke = true) // re-apply in case opacity changed
        val views = ArrayList<View>()

        // What context this read was based on (knowledge base / remembered history).
        views.add(hint(
            if (ctxNotes == 0 && ctxHistory == 0) "未用知识库"
            else "知识库 $ctxNotes 条 · 历史 $ctxHistory 条"))

        // How this snapshot was captured, when it changes how to read it.
        noteText?.let { if (it.isNotBlank()) views.add(hint(it)) }

        // Danger badge — the alarm signal, up top and color-coded.
        a.dangerLevel?.let {
            val lvl = it.score.roundToInt()
            views.add(dangerBadge(lvl, it.maxLevel))
            tintBubbleDanger(it.score)
        }
        // Intent headline.
        a.trueIntent?.let {
            views.add(line("对方真实意图：${INTENT[it.choice] ?: it.choice}", color(pal.ink), 15f, true))
            views.add(hint("把握 ${(it.confidence * 100).roundToInt()}%"))
        }
        // Compact secondary line: needs · action · reply-now.
        val bits = ArrayList<String>()
        a.sheNeeds?.let { bits.add("要${(NEEDS[it.choice] ?: it.choice)}") }
        a.bestAction?.let { bits.add(ACTION[it.choice] ?: it.choice) }
        a.shouldReplyNow?.let { bits.add(if (it >= 0.5) "可给实质" else "先别给实质") }
        if (bits.isNotEmpty()) views.add(line(bits.joinToString("  ·  "), color(pal.sub), 13f))
        a.tensionResolved?.let { if (it >= 0.7) views.add(line("✓ 紧张已缓解", color(pal.ok), 12f)) }

        views.add(divider())
        views.add(line("候选回复（Jev 排序）", color(pal.faint), 12f))
        if (generating) {
            views.addAll(skeletonCards())
        } else {
            val fill = lastFill ?: {}
            a.rankedReplies.forEachIndexed { i, r ->
                views.add(replyCard(i + 1, r.text, (r.prob * 100).roundToInt(), fill))
            }
            if (a.rankedReplies.isEmpty()) {
                // 原始错误不进 UI（SPEC A3）：区分「生成失败」与「没有候选」即可，
                // 细节由 showReplies 按长度记进 logcat。
                val msg = if (replyError != null) "回复接口出错了，点下方重新分析" else "（未生成候选回复）"
                views.add(hint(msg))
            }
        }
        views.add(reAnalyzeBtn())

        setContent(views)
        if (!expanded) toggle()
    }

    private fun dangerBadge(lvl: Int, max: Int): View {
        val color = dangerColor(lvl)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        row.addView(TextView(ctx).apply {
            text = "危险 $lvl/$max"
            setTextColor(Color.WHITE); textSize = 13f; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = card(20, color)
        })
        row.addView(TextView(ctx).apply {
            text = "  " + dangerWord(lvl); setTextColor(color); textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        })
        return row
    }

    private fun replyCard(rank: Int, text: String, pct: Int, onFill: (String) -> Unit, zh: String = ""): View {
        val top = rank == 1
        val cardBg = color(if (top) pal.accentSoft else pal.card)
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = card(UiTokens.RADIUS_CARD, cardBg)
            setPadding(dp(9), dp(6), dp(9), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(5) }
            // The whole card is the button: tap fills (then collapses so the input
            // box is visible to review and send), long-press copies.
            isClickable = true
            setOnClickListener {
                android.util.Log.d("JEVASSIST", "overlay: fill tapped")
                onFill(text)
                toast("已填入，确认后自己发送")
                if (expanded) toggle()
            }
            setOnLongClickListener { copy(text); true }
            // Press juiciness: shrink + darken on down, release back on up/cancel.
            // Returns false so click / long-click still fire.
            val pressedBg = if (top) mix(cardBg, color(pal.accentDeep), 0.08f) else darken(cardBg)
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        animate().scaleX(UiTokens.PRESS_SCALE).scaleY(UiTokens.PRESS_SCALE)
                            .setDuration(UiTokens.DUR_MICRO).start()
                        background = card(UiTokens.RADIUS_CARD, pressedBg)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        animate().scaleX(1f).scaleY(1f).setDuration(UiTokens.DUR_MICRO).start()
                        background = card(UiTokens.RADIUS_CARD, cardBg)
                    }
                }
                false
            }
        }
        if (pct >= 0) c.addView(TextView(ctx).apply {
            this.text = "#$rank · ${pct}%"
            setTextColor(color(pal.accent)); textSize = UiTokens.TEXT_META
            setTypeface(typeface, Typeface.BOLD)
        })
        c.addView(TextView(ctx).apply {
            this.text = text; setTextColor(color(pal.ink)); textSize = UiTokens.TEXT_BODY
            setLineSpacing(dp(1).toFloat(), 1.3f)
        })
        if (zh.isNotBlank()) c.addView(TextView(ctx).apply {
            this.text = zh; setTextColor(color(pal.sub)); textSize = UiTokens.TEXT_AUX
            setPadding(0, dp(1), 0, 0)
        })
        return c
    }

    private fun pill(label: String, primary: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (primary) Color.WHITE else color(pal.accent))
        background = card(18, color(if (primary) pal.accent else pal.surface), stroke = !primary)
        setPadding(dp(18), dp(6), dp(18), dp(6))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun reAnalyzeBtn() = TextView(ctx).apply {
        text = "重新分析"; textSize = 13f; gravity = Gravity.CENTER
        setTextColor(color(pal.sub))
        setPadding(dp(10), dp(10), dp(10), dp(4))
        setOnClickListener { onManualAnalyze?.invoke() }
    }

    private fun tintBubbleDanger(score: Double) {
        val color = dangerColor(score.roundToInt())
        dangerDot?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(color); setStroke(dp(2), Color.WHITE)
        }
    }

    // --------------------------------------------------------------- helpers

    private fun line(text: String, colorInt: Int, size: Float, bold: Boolean = false) =
        TextView(ctx).apply {
            this.text = text; setTextColor(colorInt); textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(2))
        }

    private fun hint(text: String) = line(text, color(pal.faint), 12f)

    private fun divider() = View(ctx).apply {
        setBackgroundColor(color(pal.hairline))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(8); bottomMargin = dp(4)
        }
    }

    private fun copy(text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("jev_reply", text))
        toast("已复制")
    }

    private fun dangerColor(lvl: Int): Int = when {
        lvl >= 6 -> color(pal.danger)
        lvl >= 3 -> color(pal.warn)
        else -> color(pal.ok)
    }

    /** Blend color [a] toward [b] by fraction [t] (0 = a, 1 = b). */
    private fun mix(a: Int, b: Int, t: Float): Int = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).roundToInt(),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).roundToInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).roundToInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).roundToInt())

    /** Darken a color ~8% by scaling its RGB channels (alpha preserved). */
    private fun darken(c: Int, f: Float = 0.92f): Int = Color.argb(
        Color.alpha(c),
        (Color.red(c) * f).roundToInt(),
        (Color.green(c) * f).roundToInt(),
        (Color.blue(c) * f).roundToInt())

    private fun dangerWord(lvl: Int): String = when {
        lvl >= 8 -> "很危险"
        lvl >= 6 -> "偏危险"
        lvl >= 3 -> "留神"
        else -> "安全"
    }

    companion object {
        private const val BUBBLE = 44
        private const val PANEL_W = 292

        private val INTENT = mapOf(
            "confirm_you_care" to "确认你在不在乎", "vent_anger" to "在发泄情绪",
            "request_action" to "要你办事", "seek_explanation" to "要个解释",
            "casual_chat" to "随便聊聊", "close_topic" to "事情过去了")
        private val NEEDS = mapOf(
            "apology" to "道歉", "action" to "具体行动", "explanation" to "解释",
            "care" to "你的在乎", "nothing" to "（不用做什么）")
        private val ACTION = mapOf(
            "check_history" to "翻聊天记录", "apologize" to "先道歉", "give_commitment" to "给承诺",
            "explain" to "解释清楚", "acknowledge" to "接住情绪", "say_less" to "少说两句",
            "make_plan" to "定个安排")
    }
}
