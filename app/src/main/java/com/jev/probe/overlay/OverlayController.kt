package com.jev.probe.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import com.jev.probe.core.BilingualResult
import com.jev.probe.core.Prefs
import com.jev.probe.core.kb.Contact
import com.jev.probe.core.ui.OverlayRules
import com.jev.probe.core.ui.OverlayRules.BubbleState
import com.jev.probe.core.ui.UiTokens
import com.jev.probe.core.ui.color
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the 关系提议 bar can do; the service supplies these, the overlay only calls them. */
class RelationBarActions(
    /** 合并推荐：与会话标题相似的已有联系人（空 = 不出这一步）。 */
    val similar: List<Contact>,
    val onMerge: (Contact) -> Unit,
    val onPick: (String) -> Unit,
    val onCustom: () -> Unit,
    val onSkip: () -> Unit
)

/**
 * Floating overlay: a small draggable bubble that expands into a panel with the
 * translation plus 3 candidate replies (bilingual is the only mode since the
 * Jev judgment mode was deleted in Sprint 5). All actions are copy / fill —
 * never send. The panel never opens by itself: the bubble carries every state
 * (idle / loading / ready / error).
 *
 * Design goals: let the chat show through (adjustable opacity), keep the signal
 * scannable (3 reply cards + one analysis line), and stay out of the way
 * (draggable bubble that remembers its position).
 *
 * Colors come from [UiTokens] (light/dark by system uiMode); bubble states from
 * [OverlayRules]. No hex color literals live in this file.
 */
class OverlayController(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = Prefs(ctx)
    private var root: FrameLayout? = null
    private var bubbleWrap: View? = null
    private var bubble: TextView? = null
    private var noticeDot: View? = null
    private var panel: LinearLayout? = null
    private var contentBox: LinearLayout? = null
    private var expanded = false
    private var lp: WindowManager.LayoutParams? = null

    /** Current palette: re-read on every use so a uiMode flip is picked up. */
    private val pal get() = UiTokens.palette(isDark())

    var onManualAnalyze: (() -> Unit)? = null

    /** Kept for the service's wiring; the menu entry moved to the settings page. */
    var onSaveContact: (() -> Unit)? = null

    /** Bubble menu → one manual screenshot + OCR of whatever app is open. */
    var onOcrCapture: (() -> Unit)? = null

    /** How much knowledge context the last analysis actually used. */
    private var ctxNotes = 0
    private var ctxHistory = 0

    /** A caveat about how the current snapshot was captured (OCR mode). */
    private var noteText: String? = null

    /** Sprint 7「换一条」闭环：同一轮里被否定的回复（已填入的那条）。
     *  服务侧在下一轮生成前调 [consumeRejected] 取走；[resetForNewConversation]
     *  与新一轮 [showLoading] 兜底清空。只在主线程被触碰。 */
    private val rejectedThisRound = mutableListOf<String>()

    /** 最近一次点卡片填入的文本：snackbar「换一条」否定的就是它。 */
    private var lastFilledText: String? = null

    /** Whether the overlay window is currently on screen. */
    fun isShowing(): Boolean = root != null

    private fun isDark(): Boolean =
        ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).roundToInt()

    private fun canOverlay(): Boolean = Settings.canDrawOverlays(ctx)

    // ------------------------------------------------- overlay permission self-heal
    //
    // HyperOS/MIUI 上 `appops allow` 会被系统瞬间回收，canDrawOverlays() 回到
    // false。老逻辑只打一行 log 就 return，所有 show* 静默 no-op，用户看到的是
    // 助手凭空消失且毫无提示。这里做三件事：2s/5s/15s 退避重试（≤3 次）、
    // AppOps 监听撤销/恢复、重试耗尽后发一条引导通知（同一掉权周期只发一次）。

    private val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 本轮掉权已排期的重试次数；权限恢复（或本就可用）时清零，开启新一轮。 */
    private var permRetryCount = 0

    /** 已有一次重试在 Handler 里排队：show* 反复进来时不重复排。 */
    private var permRetryQueued = false

    /** 「canDrawOverlays=false」一条掉权周期只打一次，不再刷屏。 */
    private var permLogged = false

    /** 引导通知一条掉权周期只发一次。 */
    private var permNotified = false

    /** 待恢复：撤销时若窗口在屏上会被拆掉；恢复后由下一个 show*（或退避回调）自然重建。 */
    private var pendingRestore = false

    private var opsWatching = false

    /** 退避回调就是重新走一遍 ensureRoot：权限回来了则内部重置周期、重建并播
     *  气泡入场动画；仍 false 则继续退避，耗尽后引导通知。 */
    private val permRetryTask = Runnable { permRetryQueued = false; ensureRoot() }

    /** 授权模式变化回调（HyperOS 瞬收 appops 授权会触发）。回调线程不定，统一切回主线程。 */
    private val opsListener = AppOpsManager.OnOpChangedListener { op, pkg ->
        if (op != AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW || pkg != ctx.packageName) return@OnOpChangedListener
        mainHandler.post { if (canOverlay()) onPermRestored() else onPermRevoked() }
    }

    init {
        appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, ctx.packageName, opsListener)
        opsWatching = true
    }

    /** canDrawOverlays=false 的唯一入口：一条周期一条 log，退避 ≤3 次，用尽后引导一次。 */
    private fun onPermMissing() {
        if (!permLogged) {
            permLogged = true
            android.util.Log.w("JEVASSIST", "overlay: canDrawOverlays=false, backoff retry scheduled")
        }
        if (permRetryQueued) return
        if (permRetryCount >= PERM_BACKOFF.size) { notifyOverlayPermLostOnce(); return }
        mainHandler.postDelayed(permRetryTask, PERM_BACKOFF[permRetryCount])
        permRetryQueued = true
        permRetryCount++
    }

    /** 权限回来了：重置掉权周期（下一轮掉权可重新 log / 通知），并撤掉还挂着的引导通知。 */
    private fun onPermRestored() {
        if (permLogged) {
            android.util.Log.i("JEVASSIST",
                "overlay: canDrawOverlays=true again" + if (pendingRestore) ", rebuild on next show" else "")
        }
        if (permNotified) {
            runCatching {
                (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(NOTIF_OVERLAY_LOST)
            }
        }
        permRetryCount = 0; permLogged = false; permNotified = false; pendingRestore = false
    }

    /** AppOps 回调：授权被撤销。窗口在屏上就先拆掉并进入待恢复，然后走退避/通知周期。 */
    private fun onPermRevoked() {
        pendingRestore = true
        if (root != null) {
            android.util.Log.w("JEVASSIST", "overlay: permission revoked while showing, hiding")
            hide()
        }
        onPermMissing()
    }

    /** 连续重试仍 false：发一条引导通知（autoCancel，点击跳本应用的悬浮窗设置页）。
     *  POST_NOTIFICATIONS 未授予时只打 log，不调用 notify，不崩。 */
    private fun notifyOverlayPermLostOnce() {
        if (permNotified) return
        permNotified = true
        android.util.Log.w("JEVASSIST", "overlay: still false after ${PERM_BACKOFF.size} retries")
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            android.util.Log.w("JEVASSIST", "overlay: notifications off, skip permission guide")
            return
        }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Jev 助手状态", NotificationManager.IMPORTANCE_DEFAULT))
        val tap = PendingIntent.getActivity(ctx, 0,
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notif = Notification.Builder(ctx, CHANNEL_STATUS)
            .setContentTitle("悬浮窗权限被收回了")
            .setContentText("点这里重新开启")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIF_OVERLAY_LOST, notif) }
    }

    /** 服务销毁时调用：摘掉 AppOps 监听、取消排队中的退避重试、拆掉窗口。幂等。
     *  接线点：ChatCaptureService.onDestroy 里 `overlay?.hide()` 那一行换成
     *  `overlay?.destroy()`（destroy 内含 hide）。 */
    fun destroy() {
        mainHandler.removeCallbacks(permRetryTask)
        permRetryQueued = false
        if (opsWatching) {
            runCatching { appOps.stopWatchingMode(opsListener) }
            opsWatching = false
        }
        hide()
    }

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
        if (!canOverlay()) { onPermMissing(); return }
        onPermRestored()  // 权限在：掉权周期归零（没掉过权时是无害 no-op）
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.bubbleX in 0..(screenW - dp(BUBBLE))) prefs.bubbleX else dp(8)
            // 恢复的落点夹紧在屏内（-1 默认分支不变）
            y = if (prefs.bubbleY >= 0) prefs.bubbleY.coerceIn(dp(24), screenH - dp(120)) else dp(150)
        }
        lp = params

        val r = FrameLayout(ctx)
        val p = buildPanel()
        val wrap = buildBubble(params)
        r.addView(p)
        r.addView(wrap)
        root = r
        try {
            wm.addView(r, params)
            playBubbleEntrance(wrap)
        } catch (e: Exception) {
            android.util.Log.e("JEVASSIST", "overlay addView failed: ${e.message}")
            root = null; bubbleWrap = null
        }
    }

    /** 气泡出现：scale 0.6→1 + alpha 0→1，带一点弹性。首次创建与掉权恢复后的
     *  重建都走 ensureRoot 这一条路径，所以恢复重建时入场动画同样生效。 */
    private fun playBubbleEntrance(wrap: View) {
        wrap.scaleX = 0.6f; wrap.scaleY = 0.6f; wrap.alpha = 0f
        wrap.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(180).setInterpolator(OvershootInterpolator(1.2f)).start()
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
        bubble = b; noticeDot = dot; bubbleWrap = wrap
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
        // Header: one thin row — the other side's language (foreign chats only), then icons.
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(ctx).apply {
            text = ""; setTextColor(color(pal.faint)); textSize = UiTokens.TEXT_META
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }.also { headerLabel = it })
        header.addView(iconBtn("↻") { onManualAnalyze?.invoke() }.also { refreshBtn = it })
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
    private var refreshBtn: TextView? = null

    private fun iconBtn(glyph: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = glyph; setTextColor(color(pal.faint)); textSize = 14f
        setPadding(dp(7), dp(2), dp(7), dp(2))
        setOnClickListener { onClick() }
    }

    // --------------------------------------------------------------- gestures

    private fun attachBubbleTouch(v: View, params: WindowManager.LayoutParams) {
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        var moved = false; var longFired = false
        /** 松手吸边动画播放中：吞掉触摸，不与动画抢 params.x（200ms 单次短动画）。 */
        var snapping = false
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        val longPress = Runnable {
            if (!moved) { longFired = true; showBubbleMenu() }
        }
        v.setOnTouchListener { _, e ->
            if (snapping) return@setOnTouchListener true
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; touchX = e.rawX; touchY = e.rawY
                    moved = false; longFired = false
                    bubblePress(true)
                    v.postDelayed(longPress, 500); true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt(); val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    // 长按菜单弹出后锁定本次拖动，气泡不再跟手。
                    // Keep a margin from both side edges: the extreme edge is MIUI's
                    // back-gesture zone, which steals touches and makes the bubble
                    // "stuck". Free positioning (no forced edge snap) also avoids it.
                    if (!longFired) {
                        params.x = (startX + dx).coerceIn(dp(8), screenW - dp(60))
                        params.y = (startY + dy).coerceIn(dp(24), screenH - dp(120))
                        root?.let { runCatching { wm.updateViewLayout(it, params) } }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    bubblePress(false)  // dragged or not, the spring-back always lands
                    // 长按松手也保存落点（长按触发前可能已拖过阈值）
                    if (moved) {
                        // Sprint 7 可选吸边（prefs.bubbleSnap，默认关）：开了就把
                        // params.x 平滑吸到较近一侧再存落点；没开维持现状
                        // 「停在哪就在哪」，躲开 MIUI 边缘手势区。
                        if (prefs.bubbleSnap) {
                            snapping = true
                            snapBubbleToEdge(params) { snapping = false }
                        } else {
                            prefs.bubbleX = params.x; prefs.bubbleY = params.y
                        }
                    }
                    if (!longFired && !moved) toggle()
                    true
                }
                MotionEvent.ACTION_CANCEL -> { v.removeCallbacks(longPress); bubblePress(false); true }
                else -> false
            }
        }
    }

    /** 松手吸边：200ms ValueAnimator 把 params.x 过渡到 [OverlayRules.snappedX]
     *  （边距 dp(8)，不贴 0），每帧 updateViewLayout，落地后才写 prefs。 */
    private fun snapBubbleToEdge(params: WindowManager.LayoutParams, onEnd: () -> Unit) {
        val target = OverlayRules.snappedX(params.x, screenW, dp(BUBBLE), dp(8))
        if (target == params.x) {   // 已经在吸附位：直接存，不播空动画
            prefs.bubbleX = params.x; prefs.bubbleY = params.y
            onEnd(); return
        }
        ValueAnimator.ofInt(params.x, target).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                params.x = it.animatedValue as Int
                root?.let { r -> runCatching { wm.updateViewLayout(r, params) } }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    prefs.bubbleX = params.x; prefs.bubbleY = params.y
                    onEnd()
                }
                // 取消（hide/拆窗）只收尾不存落点：半途位置不是用户的意图
                override fun onAnimationCancel(animation: Animator) { onEnd() }
            })
            start()
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
                .setInterpolator(OvershootInterpolator(1.5f)).start()
        }
    }

    // ------------------------------------------------------------- bubble menu

    /** The currently-open long-press menu, if any (toggle() removes it first). */
    private var openMenu: View? = null

    /** Pixels the window was shifted up so the menu could open above the bubble. */
    private var menuShift = 0

    private fun showBubbleMenu() {
        val r = root ?: return
        val params = lp ?: return
        dismissMenu(animate = false)
        val auto = prefs.autoAnalyze
        val menu = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = card(UiTokens.RADIUS_PANEL, color(pal.surfaceElev), stroke = true)
            elevation = dp(8).toFloat()
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        menu.addView(menuItem(if (auto) "自动生成：开" else "自动生成：关") {
            prefs.autoAnalyze = !auto
            dismissMenu()
            toast(if (auto) "已关闭：点气泡才生成" else "已开启：来新消息就在后台生成")
        })
        menu.addView(menuItem("截屏识别") { dismissMenu { onOcrCapture?.invoke() } })
        menu.addView(menuItem("设置") { dismissMenu { openSettings() } })
        menu.addView(menuItem("隐藏") { dismissMenu { hide() } })
        // 位置钳制：气泡在屏上 2/3 → 菜单放下方；在下 1/3 → 窗口上移、菜单长到气泡上方
        val menuW = dp(236)
        menu.measure(
            View.MeasureSpec.makeMeasureSpec(menuW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val mlp = FrameLayout.LayoutParams(menuW, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (params.y <= screenH * 2 / 3) {
            mlp.topMargin = dp(BUBBLE + 4)
        } else {
            val oldY = params.y
            params.y = (oldY - menu.measuredHeight - dp(4)).coerceAtLeast(dp(8))
            menuShift = oldY - params.y
            bubbleWrap?.translationY = menuShift.toFloat() // 气泡视觉上留在原屏幕位置
            mlp.topMargin = 0
            runCatching { wm.updateViewLayout(r, params) }
        }
        menu.layoutParams = mlp
        // 长出动画：alpha 0→1 + scaleY 0.92→1，从顶边长出来
        menu.pivotY = 0f
        menu.alpha = 0f
        menu.scaleY = 0.92f
        openMenu = menu
        r.addView(menu)
        menu.animate().alpha(1f).scaleY(1f).setDuration(130)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    /** 菜单项点击后 100ms 淡出再 removeView；[after] 在移除后执行。 */
    private fun dismissMenu(animate: Boolean = true, after: (() -> Unit)? = null) {
        val m = openMenu
        if (m == null) { after?.invoke(); return }
        openMenu = null
        restoreMenuShift()
        if (animate) {
            m.animate().alpha(0f).setDuration(100).withEndAction {
                root?.removeView(m)
                after?.invoke()
            }.start()
        } else {
            m.animate().cancel()
            root?.removeView(m)
            after?.invoke()
        }
    }

    /** 菜单向上开时窗口上移过，关菜单要把气泡位置还回来。 */
    private fun restoreMenuShift() {
        if (menuShift == 0) return
        bubbleWrap?.translationY = 0f
        lp?.let { p ->
            p.y += menuShift
            root?.let { runCatching { wm.updateViewLayout(it, p) } }
        }
        menuShift = 0
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

    /** showNotice 的红点提醒是否还挂在气泡上：用户点开面板看到内容后消除。 */
    private var noticeDotOn = false

    private fun clearNoticeDot() {
        if (!noticeDotOn) return
        noticeDotOn = false
        noticeDot?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT)
        }
    }

    private fun toggle() {
        dismissMenu(animate = false)   // 菜单开着就先撤掉
        val params = lp ?: return
        val p = panel
        if (panelAnimating && p != null) {
            // Toggled mid-animation: stop the running one quietly and jump
            // straight to the final state of this toggle — never stack a
            // second animation on top of the first.
            p.animate().setListener(null).cancel()
            panelAnimating = false
            expanded = !expanded
            if (expanded) clearNoticeDot()
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
        if (expanded) clearNoticeDot()
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
                    .setInterpolator(DecelerateInterpolator())
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
        set(v) { field = v; updateRefreshEnabled() }

    /** 生成中禁用 ↻（alpha 0.4 且不可点），防止重复触发；生成结束恢复。 */
    private fun updateRefreshEnabled() {
        refreshBtn?.let {
            it.isEnabled = !loading
            it.alpha = if (loading) 0.4f else 1f
        }
    }

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
    private var labelFade: ValueAnimator? = null

    /** Previous bubble state, so the READY arrival pulse plays only once per round. */
    private var bubbleState: BubbleState? = null

    /** The bubble carries the state, so the panel never has to pop open by itself. */
    private fun setBubble(s: BubbleState) {
        val b = bubble ?: return
        val prev = bubbleState
        bubbleState = s
        val visual = OverlayRules.bubbleVisual(s)
        // 新消息到达提醒只播一次：进入 READY 的那一下；READY→其他状态只有 alpha/颜色过渡
        val arrival = s == BubbleState.READY && prev != BubbleState.READY
        if (arrival) {
            swapLabelFaded(b, visual.label)
            playArrival(b, visual.alpha)
        } else {
            labelFade?.cancel()
            b.setTextColor(Color.WHITE)
            b.text = visual.label   // label switches instantly; only the color crossfades
            b.alpha = visual.alpha
        }
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

    /** 新消息到达：alpha 0.5→1（250ms）+ scale 1→1.12→1 脉冲（320ms，回程 Overshoot 1.5）。 */
    private fun playArrival(b: View, targetAlpha: Float) {
        ObjectAnimator.ofFloat(b, View.ALPHA, b.alpha, targetAlpha).apply {
            duration = 250
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        val up = ObjectAnimator.ofPropertyValuesHolder(b,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.12f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.12f)).apply { duration = 140 }
        val down = ObjectAnimator.ofPropertyValuesHolder(b,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1.12f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.12f, 1f)).apply {
            duration = 180
            interpolator = OvershootInterpolator(1.5f)
        }
        AnimatorSet().apply { playSequentially(up, down); start() }
    }

    /** 气泡文案淡换（Jev→✓）：淡出 100ms → 换字 → 淡入 120ms。只动字色，不动 view alpha。 */
    private fun swapLabelFaded(b: TextView, newLabel: String) {
        labelFade?.cancel()
        labelFade = ValueAnimator.ofObject(ArgbEvaluator(), Color.WHITE, Color.TRANSPARENT).apply {
            duration = 100
            addUpdateListener { b.setTextColor(it.animatedValue as Int) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    b.text = newLabel
                    ValueAnimator.ofObject(ArgbEvaluator(), Color.TRANSPARENT, Color.WHITE).apply {
                        duration = 120
                        addUpdateListener { b.setTextColor(it.animatedValue as Int) }
                        start()
                    }
                }
            })
            start()
        }
    }

    /**
     * Drop whatever translation/candidates/note belonged to the previous
     * conversation. Call this before showing anything for a different chat
     * window (a different app, or new content in the same one) — otherwise a
     * leftover result from a prior conversation can keep [showIdle] from
     * putting the panel back into its empty state.
     */
    fun resetForNewConversation() {
        noteText = null
        hasResult = false; loading = false
        // 换会话 = 新一轮：上一轮的「换一条」否定与填入记录不带过去
        rejectedThisRound.clear()
        lastFilledText = null
        headerLabel?.text = ""
        headerLabel?.visibility = View.VISIBLE
        cancelContentAnimators()
        contentBox?.removeAllViews()
        // 红点（通知提醒）随旧会话一起清掉
        noticeDotOn = false
        noticeDot?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT)
        }
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

    /** Loading placeholder: three skeleton cards — opaque pal.card base + an
     *  accentSoft breathing layer on top (0.55↔0.9, 1200ms, staggered 150ms).
     *  Each card's animator is stashed in its tag; [setContent]/[hide] cancel it. */
    private fun skeletonCards(): List<View> = (0..2).map { i ->
        val box = FrameLayout(ctx)
        box.addView(View(ctx).apply {
            background = card(UiTokens.RADIUS_CARD, color(pal.card))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        })
        val glow = View(ctx).apply {
            background = card(UiTokens.RADIUS_CARD, color(pal.accentSoft))
            alpha = 0.55f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        box.addView(glow)
        box.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) }
        val shimmer = ValueAnimator.ofFloat(0.55f, 0.9f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            startDelay = i * 150L
            addUpdateListener { glow.alpha = it.animatedValue as Float }
            start()
        }
        box.tag = shimmer
        box
    }

    /** 面板永不自动弹出，加载状态由气泡承载，面板永远等用户点。 */
    fun showLoading() {
        ensureRoot()
        ctxNotes = 0; ctxHistory = 0   // counts for the round that is starting
        loading = true; hasResult = false
        // 新一轮生成的兜底清空：正常路径下服务侧已在 showLoading 之前
        // consumeRejected() 取走否定列表，这里清的是「没来得及消费」的残留。
        rejectedThisRound.clear()
        setBubble(BubbleState.LOADING)
        // 加载态面板背景与就绪态一致：不透明 surface
        panel?.background = card(UiTokens.RADIUS_PANEL, color(pal.surface), stroke = true)
        setContent(skeletonCards())
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
     * Sprint 7「换一条」闭环：服务侧在下一轮生成前调用，取走本轮被否定的
     * 回复（返回副本并清空）。调用时机要在 [showLoading] 之前——后者会兜底
     * 清空，晚于它就拿不到了。
     */
    fun consumeRejected(): List<String> {
        val copy = rejectedThisRound.toList()
        rejectedThisRound.clear()
        return copy
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
     * fully disabled). Not framed as an error: the panel never pops open by
     * itself — the bubble gets an accent-colored dot instead, and the dot
     * clears once the user opens the panel and reads the message. Never
     * auto-dismisses and never takes input focus (FLAG_NOT_FOCUSABLE).
     */
    fun showNotice(msg: String) {
        ensureRoot(); bubble?.alpha = 1f
        resetForNewConversation()
        setContent(listOf(
            line("提示", color(pal.accent), 14f, true),
            hint(msg)))
        hasResult = true   // 面板里是提示内容：点气泡打开时不要触发「生成」
        noticeDotOn = true
        popNoticeDot(GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(color(pal.accent)); setStroke(dp(2), Color.WHITE)
        })
    }

    /** Bilingual result: translation of the other side, then 3 target-language
     *  replies with Chinese glosses. "填入" fills only the target-language text. */
    fun showBilingual(
        r: BilingualResult,
        onFill: (String) -> Unit,
        relation: RelationBarActions? = null
    ) {
        ensureRoot()
        // 不透明：候选回复要读清楚，聊天内容透过来会和中文释义叠在一起
        panel?.background = card(UiTokens.RADIUS_PANEL, color(pal.surface), stroke = true)
        loading = false; hasResult = true
        setBubble(BubbleState.READY)
        // 头部：外语只留语言标签；中文会话整行隐藏
        val zhOnly = r.lang.isBlank() || r.lang == "中文"
        headerLabel?.let {
            it.visibility = if (zhOnly) View.GONE else View.VISIBLE
            it.text = if (zhOnly) "" else r.lang
        }
        val views = ArrayList<View>()
        // 中文对话不显示译文行（与 Windows P0-3 同规则）；lang 缺失时仍按外语处理
        if (r.translation.isNotBlank() && r.lang != "中文") views.add(translationRow(r.translation))
        r.replies.forEachIndexed { i, reply ->
            // 中文对话或释义与正文相同时不显示灰字（与 Windows P0-1 同规则）
            val gloss = if (OverlayRules.shouldShowGloss(r.lang, reply.zh, reply.text)) reply.zh else ""
            views.add(replyCard(i + 1, reply.text, onFill, gloss))
        }
        // 关系提议条在回复卡片之后：不挡也不拦候选回复，用户想选再选
        if (relation != null && r.relationCandidates.isNotEmpty()) {
            views.add(relationBar(r.relationCandidates, relation))
        }
        // 次要信息放最底下：分析压成一行（单行省略）；OCR 提示保留
        if (r.analysis.isNotBlank()) views.add(line(r.analysis, color(pal.sub), UiTokens.TEXT_AUX).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
        })
        noteText?.takeIf { it.isNotBlank() }?.let { views.add(line(it, color(pal.faint), UiTokens.TEXT_META)) }
        setContent(views)
    }

    /** 关系提议条。有相似联系人时先问「是不是同一个人」（合并推荐，点了才并入），
     *  「都不是」再进入三个候选 chip + 「自己输入」；两步都有「跳过」。点选或跳过就
     *  回调并把整条收掉；从不自己建档或合并，一切由回调侧（服务）在用户点击之后做。 */
    private fun relationBar(candidates: List<String>, actions: RelationBarActions): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        fun close() { (bar.parent as? ViewGroup)?.removeView(bar) }

        fun chips(labels: List<String>, onClick: (Int) -> Unit) = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            labels.forEachIndexed { i, label ->
                addView(TextView(ctx).apply {
                    text = label; textSize = UiTokens.TEXT_AUX; gravity = Gravity.CENTER
                    setTextColor(color(pal.ink)); maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    background = card(UiTokens.RADIUS_CARD, color(pal.card))
                    setPadding(dp(6), dp(7), dp(6), dp(7))
                    isClickable = true
                    setOnClickListener { onClick(i) }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { if (i > 0) leftMargin = dp(6) }
                })
            }
        }

        fun links(vararg items: Pair<String, () -> Unit>) = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
            items.forEach { (label, onClick) ->
                addView(TextView(ctx).apply {
                    text = label; textSize = UiTokens.TEXT_AUX; setTextColor(color(pal.accent))
                    setPadding(dp(10), dp(8), dp(4), dp(2))
                    isClickable = true
                    setOnClickListener { onClick() }
                })
            }
        }

        val skip = "跳过" to { close(); actions.onSkip() }

        fun showRelations() {
            bar.removeAllViews()
            bar.addView(hint("对方是？点一个记下来，回复会更贴"))
            bar.addView(chips(candidates) { i -> close(); actions.onPick(candidates[i]) })
            // 候选都不准：自己输入（悬浮窗打不了字，由服务拉起输入框）；不想设：跳过
            bar.addView(links("自己输入" to { actions.onCustom() }, skip))
        }

        fun showMerge() {
            val similar = actions.similar
            bar.removeAllViews()
            bar.addView(hint("是不是同一个人？点一个并入"))
            bar.addView(chips(similar.map { it.name }) { i -> close(); actions.onMerge(similar[i]) })
            bar.addView(links("都不是" to { showRelations() }, skip))
        }

        if (actions.similar.isEmpty()) showRelations() else showMerge()
        return bar
    }

    /** 译文行：左侧 2dp accentSoft 竖条分区 + 最多两行、行距 1.25 的粗体译文。 */
    private fun translationRow(text: String): View {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(View(ctx).apply {
            setBackgroundColor(color(pal.accentSoft))
            layoutParams = LinearLayout.LayoutParams(dp(2), ViewGroup.LayoutParams.MATCH_PARENT)
        })
        row.addView(line(text, color(pal.ink), UiTokens.TEXT_TRANS, true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.25f)
            setPadding(dp(8), dp(2), 0, dp(2))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        return row
    }

    /** In-panel snackbar: a small dark pill at the bottom of the overlay root,
     *  fading in, holding ~1.6s, then fading out. Replaces itself on repeat calls.
     *  填入成功那条右侧带「换一条」动作（重新展开面板）。 */
    private var snackbar: View? = null
    private var snackbarHide: Runnable? = null

    fun snackbar(msg: String) {
        val r = root ?: return
        snackbar?.let { old ->
            snackbarHide?.let { old.removeCallbacks(it) }
            old.animate().cancel()
            old.clearAnimation()
            r.removeView(old)
        }
        val pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = card(20, Color.argb(199, 0, 0, 0)) // 黑 78%
            setPadding(dp(14), dp(8), dp(14), dp(8))
            alpha = 0f
            translationY = dp(8).toFloat()  // 入场：随 120ms 淡入并行上滑 8dp→0
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(24)
            }
        }
        pill.addView(TextView(ctx).apply {
            text = msg; setTextColor(Color.WHITE); textSize = 12f
        })
        // ChatCaptureService 填入成功时弹的正是这句；给它配上「换一条」
        if (msg == FILL_OK) {
            pill.addView(TextView(ctx).apply {
                text = "换一条"
                setTextColor(color(pal.accentLight)); textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(12), 0, 0, 0)
                setOnClickListener {
                    // Sprint 7 闭环：把刚填入的那条记入本轮否定列表，下一轮
                    // 生成时由服务侧 consumeRejected() 取走，prompt 里换角度。
                    lastFilledText?.takeIf { it.isNotBlank() }?.let {
                        rejectedThisRound.add(it)
                        android.util.Log.d("JEVASSIST",
                            "overlay: reroll rejected.len=${it.length} total=${rejectedThisRound.size}")
                    }
                    snackbarHide?.let { pill.removeCallbacks(it) }
                    pill.animate().cancel()
                    r.removeView(pill)
                    if (snackbar === pill) snackbar = null
                    if (!expanded) toggle()
                    snackbar(REROLL_NOTED)
                }
            })
        }
        snackbar = pill
        r.addView(pill)
        val hideRun = Runnable {
            pill.animate().alpha(0f).setDuration(200).withEndAction {
                r.removeView(pill)
                if (snackbar === pill) snackbar = null
            }.start()
        }
        snackbarHide = hideRun
        pill.animate().alpha(1f).translationY(0f).setDuration(120).withEndAction {
            pill.postDelayed(hideRun, 1600)
        }.start()
    }

    /** Every in-file notice routes to the in-panel snackbar (never a system Toast). */
    fun toast(msg: String) = snackbar(msg)

    fun hide() {
        val r = root ?: return
        openMenu?.animate()?.cancel()
        openMenu = null
        menuShift = 0
        cancelContentAnimators()
        bubbleFade?.cancel(); bubbleFade = null; bubbleTint = null
        labelFade?.cancel(); labelFade = null
        panel?.animate()?.cancel()
        snackbar?.let { v ->
            snackbarHide?.let { v.removeCallbacks(it) }
            v.animate().cancel(); v.clearAnimation(); r.removeView(v)
        }
        snackbar = null; snackbarHide = null
        val wrap = bubbleWrap
        root = null; bubble = null; bubbleWrap = null; panel = null; contentBox = null
        noticeDot = null; expanded = false
        hasResult = false; loading = false; headerLabel = null; refreshBtn = null
        panelAnimating = false
        bubbleState = null
        noticeDotOn = false
        // 气泡消失：scale→0.85 + alpha→0（140ms）再移除窗口
        if (wrap != null) {
            wrap.animate().scaleX(0.85f).scaleY(0.85f).alpha(0f).setDuration(140)
                .withEndAction { runCatching { wm.removeView(r) } }.start()
        } else {
            runCatching { wm.removeView(r) }
        }
    }

    // --------------------------------------------------------------- rendering

    /** Cancel per-child animators (skeleton shimmer) before dropping views. */
    private fun cancelContentAnimators() {
        val c = contentBox ?: return
        for (i in 0 until c.childCount) (c.getChildAt(i).tag as? ValueAnimator)?.cancel()
    }

    /** 立即替换内容（保住 childCount 的同步语义），只给新内容播入场：
     *  alpha 0→1 + translationY 6dp→0，180ms，逐条错开 40ms。 */
    private fun setContent(views: List<View>) {
        val c = contentBox ?: return
        cancelContentAnimators()
        c.removeAllViews()
        views.forEachIndexed { i, v ->
            c.addView(v)
            v.alpha = 0f
            v.translationY = dp(6).toFloat()
            v.animate().alpha(1f).translationY(0f).setDuration(180)
                .setStartDelay((i * 40).toLong())
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun replyCard(rank: Int, text: String, onFill: (String) -> Unit, zh: String = ""): View {
        val top = rank == 1
        val cardBg = color(if (top) pal.accentSoft else pal.card)
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = card(UiTokens.RADIUS_CARD, cardBg)
            setPadding(dp(9), dp(8), dp(9), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            // The whole card is the button: tap fills (the panel collapses after a
            // beat so the press spring-back plays; the true fill result is shown by
            // the service), long-press copies.
            isClickable = true
            setOnClickListener {
                android.util.Log.d("JEVASSIST", "overlay: fill tapped")
                lastFilledText = text   // snackbar「换一条」否定的就是这条
                onFill(text)
                // 填入是异步的，成功/失败提示由服务侧按真实结果弹；这里不抢话
                postDelayed({ if (expanded) toggle() }, 150)
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
        c.addView(TextView(ctx).apply {
            this.text = text; setTextColor(color(pal.ink)); textSize = UiTokens.TEXT_BODY
            setLineSpacing(dp(1).toFloat(), 1.3f)
        })
        if (zh.isNotBlank()) c.addView(TextView(ctx).apply {
            this.text = zh; setTextColor(color(pal.sub)); textSize = UiTokens.TEXT_AUX
            setPadding(0, dp(3), 0, 0)
        })
        return c
    }

    /** 角标出现动效：换底色 + scale 0→1，180ms Overshoot(1.5)。
     *  清除方（clearNoticeDot / resetForNewConversation）只把底色改透明，
     *  下次出现时从 0 重新弹出。 */
    private fun popNoticeDot(bg: GradientDrawable) {
        val d = noticeDot ?: return
        d.animate().cancel()
        d.background = bg
        d.scaleX = 0f; d.scaleY = 0f
        d.animate().scaleX(1f).scaleY(1f).setDuration(180)
            .setInterpolator(OvershootInterpolator(1.5f)).start()
    }

    // --------------------------------------------------------------- helpers

    private fun line(text: String, colorInt: Int, size: Float, bold: Boolean = false) =
        TextView(ctx).apply {
            this.text = text; setTextColor(colorInt); textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(2))
        }

    private fun hint(text: String) = line(text, color(pal.faint), 12f)

    private fun copy(text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("jev_reply", text)
        // 标记敏感内容：不进剪贴板预览/历史（minSdk 30，setExtras API 24+ 直接可用）
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        cm.setPrimaryClip(clip)
        toast("已复制")
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

    companion object {
        private const val BUBBLE = 44
        private const val PANEL_W = 292

        /** 掉权退避：2s / 5s / 15s，最多 3 次重试。 */
        private val PERM_BACKOFF = longArrayOf(2_000L, 5_000L, 15_000L)

        /** 状态通知渠道：与 KeepAliveService.CHANNEL_STATUS 复用同一 id（同名同重要性，重复创建幂等）。 */
        private const val CHANNEL_STATUS = "jev_status"
        private const val NOTIF_OVERLAY_LOST = 3

        /** 填入成功提示（与 ChatCaptureService.fillInput 的成功文案一致）——
         *  这条 snackbar 右侧带「换一条」动作。 */
        private const val FILL_OK = "已填入，确认后自己发送"

        /** 「换一条」点完后的反馈：否定已记录，下一轮换角度（Sprint 7）。 */
        private const val REROLL_NOTED = "已记下，下条换个角度"
    }
}
