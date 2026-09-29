package com.jev.probe.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.capture.ocr.MlKitOcr
import com.jev.probe.capture.ocr.OcrLine
import com.jev.probe.capture.ocr.ScreenCapture
import com.jev.probe.core.BubbleRect
import com.jev.probe.core.BilingualResult
import com.jev.probe.core.ChatMemory
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.kb.Contact
import com.jev.probe.core.kb.ContactMatch
import com.jev.probe.core.kb.ContextBuilder
import com.jev.probe.RelationInputActivity
import com.jev.probe.core.kb.KbStore
import com.jev.probe.core.kb.RelationSkips
import com.jev.probe.core.ui.OverlayRules
import com.jev.probe.jev.ReplyClient
import com.jev.probe.overlay.OverlayController
import com.jev.probe.overlay.RelationBarActions
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * The live capture service (registered under a disguised class name so WeChat
 * exposes its node tree — see the disguised subclass). It reads whichever
 * adapted chat app is in the foreground, detects a new incoming message from the
 * other person, drafts bilingual replies off the main thread, and drives the
 * floating overlay.
 *
 * Per-app node rules live in [ChatAppAdapter] implementations; everything here
 * is app-agnostic.
 *
 * It never sends a message. The only write action is ACTION_SET_TEXT (or a
 * clipboard PASTE fallback) to fill the chat input box when the user taps
 * "填入"; the user still presses send.
 */
open class ChatCaptureService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)

    /** Adapted chat apps, keyed by package name.
     *  WeChat is intentionally NOT wired in: reading it (node tree / screenshot /
     *  OCR) is what trips WeChat's anti-screenshot risk control, so it is fully
     *  disabled and handled by a short-circuit notice instead of an adapter (see
     *  [maybeCapture] / [onAccessibilityEvent]). [WeChatAdapter] is kept in the
     *  codebase for a possible future restore, just not used here. */
    private val adapters = listOf(QQAdapter(), XAdapter(), FeishuAdapter(), WhatsAppAdapter(), WhatsAppAdapter(WhatsAppAdapter.PKG_BUSINESS)).associateBy { it.pkg }

    /** Submit to the worker, ignoring rejection after the service is torn down
     *  (a stale overlay callback must never crash the process). */
    private fun submit(task: () -> Unit) {
        try { worker.execute(task) } catch (_: RejectedExecutionException) { }
    }
    private lateinit var prefs: Prefs
    private var overlay: OverlayController? = null

    private var lastSignature: String = ""
    private var activePkg: String? = null
    private var analyzing = false

    /** Last known-good (non-transient) title per package. See [isTransientTitle]:
     *  a page like X's DM thread briefly shows "连接中…" as `snapshot.title`
     *  right after opening, which must never overwrite a real conversation
     *  title or get saved as a contact name. Never cleared on app switch — the
     *  next real title for that package simply replaces it. */
    private val lastGoodTitle: MutableMap<String, String> = HashMap()
    private val debounce = Runnable { runAnalysis() }
    /** Trailing debounce for the high-frequency content/scrolled event stream:
     *  one capture pass per burst, not one per event. */
    private val captureDebounce = Runnable { maybeCapture() }
    private var pendingSnapshot: ChatSnapshot? = null
    @Volatile private var currentSnapshot: ChatSnapshot? = null
    private var foregroundPkg: String? = null

    // ---- OCR path (B stage). Everything here runs on the main thread: the
    // screenshot callback and the ML Kit callback are both posted back to it.
    private val screenCapture by lazy {
        ScreenCapture(this,
            hideOverlay = { overlay?.setHiddenForShot(true) },
            restoreOverlay = { overlay?.setHiddenForShot(false) })
    }
    private val ocr = MlKitOcr()
    private var ocrBusy = false

    /** What the screen looked like the last time we fired an automatic shot.
     *  See [ocrSignature]: this is the brake on the OCR path. */
    private var lastOcrSignature: String = ""

    /** WeChat is fully disabled — we never read it, so instead of a signature we
     *  just track whether the "WeChat not supported" notice has been shown for
     *  the current WeChat visit. Reset to false whenever a non-WeChat foreground
     *  is seen, so it re-appears next visit but does not re-pop on every event. */
    private var wechatNoticeShown = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        overlay = OverlayController(this)
        overlay?.onManualAnalyze = {
            currentSnapshot?.let { pendingSnapshot = it; runAnalysis() }
        }
        // Bubble menu: file the open conversation as a knowledge-base contact.
        // Contacts are never created automatically — this is the one-tap way in.
        overlay?.onSaveContact = {
            val title = currentSnapshot?.title
            val pkg = activePkg ?: foregroundPkg ?: ""
            when {
                title.isNullOrBlank() -> overlay?.toast("当前会话没有标题，存不了")
                isTransientTitle(title) -> overlay?.toast("当前会话标题还没加载出来，稍后再试")
                else -> submit {
                    relationSettled.add(memKey(pkg, title))
                    val msg = try {
                        KbStore.get(this).saveOrMergeContact(title, pkg)
                    } catch (e: Exception) { "保存失败：${e.javaClass.simpleName}" }
                    main.post { overlay?.toast(msg) }
                }
            }
        }
        // Bubble menu: one manual screenshot + OCR, for any app at all.
        overlay?.onOcrCapture = { ocrCaptureManual() }
        // Keep the process at foreground importance so MIUI does not freeze us.
        runCatching { KeepAliveService.start(this) }
        // Load the bundled OCR model now, off the main thread: the first
        // recognize() otherwise pays for it inside the screenshot callback.
        submit { MlKitOcr.warmUp() }
        // HyperOS may kill and restart us. On (re)connect, proactively re-show the
        // bubble for whatever chat is already open, so it comes back on its own
        // instead of waiting for the user to scroll.
        main.postDelayed({ if (prefs.enabled) runCatching { maybeCapture() } }, 900)
        Log.i(TAG, "capture service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!prefs.enabled) { main.post { overlay?.hide() }; return }

        val type = event.eventType
        // Decide "did we leave the chat app" from the REAL active window, not the
        // event's package. The event package can be an IME (e.g. com.tencent.wetype)
        // or the status bar while the chat app is still foreground — keying off it
        // made the bubble flicker (hide → re-show → hide…). rootInActiveWindow stays
        // on the chat app while the keyboard is up, so this is stable.
        //
        // An app with no adapter is NOT a reason to take the bubble away: the only
        // way into DingTalk / Telegram / anything else is the bubble menu's
        // "截屏识别一次", and a bubble that is gone cannot be tapped. So we park
        // the idle bubble there instead — still no automatic capture, no analysis.
        // The bubble does come off for places where it would only be in the way:
        // our own settings screens, the launcher, and the system UI.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val fg = rootInActiveWindow?.packageName?.toString()
            // WeChat is fully disabled: never read/screenshot/OCR/fill here, only
            // show the one-time "not supported" notice and stop. Checked before the
            // generic no-adapter branch because WeChat is no longer in `adapters`.
            if (fg == PKG_WECHAT && !prefs.wechatEnabled) { foregroundPkg = fg; showWeChatDisabled(auto = true); return }
            // Only chat apps we can read get a bubble; everywhere else it would
            // just be in the way (user feedback: "在哪个app都出现").
            if (fg != null && adapterFor(fg) == null) {
                foregroundPkg = fg
                wechatNoticeShown = false // left WeChat → allow the notice again next visit
                // IME / status bar events can report their own package while the
                // chat app stays underneath — only a real app switch hides us.
                if (!fg.contains("inputmethod", true) && fg != "com.android.systemui") main.post { overlay?.hide() }
                return
            }
        }

        when (type) {
            // App/window switches stay immediate so the bubble follows at once.
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> maybeCapture()
            // Content/scrolled events fire in bursts (caret blinks, timestamp
            // flips, typing): collapse each burst into one trailing capture pass.
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                main.removeCallbacks(captureDebounce)
                main.postDelayed(captureDebounce, CAPTURE_DEBOUNCE_MS)
            }
        }
    }

    private fun maybeCapture() {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString()
        // WeChat is fully disabled — no tree read, no screenshot, no OCR, no fill.
        // A content-changed / scrolled event in WeChat only re-shows the one-time
        // notice (deduped); it must never reach an adapter or the OCR path.
        if (pkg == PKG_WECHAT && !prefs.wechatEnabled) { showWeChatDisabled(auto = true); return }
        if (pkg != PKG_WECHAT) wechatNoticeShown = false // allow the notice again next WeChat visit
        val adapter = pkg?.let { adapterFor(it) } ?: return
        // Outside a chat window (conversation list, profile, settings…): no bubble.
        val rawSnapshot = adapter.extract(root, resources)
        if (rawSnapshot == null) { main.post { overlay?.hide() }; return }
        // Stabilize the title BEFORE anything below reads it: some apps (X) show
        // a transient "连接中…" title for a moment right after opening a thread.
        val snapshot = stabilizeTitle(pkg ?: "", rawSnapshot)
        if (!prefs.isAllowed(snapshot.title)) { main.post { overlay?.hide() }; return }
        // In a chat window but the tree holds no text (Feishu draws its bodies,
        // WeChat hides them when the disguise fails) → screenshot + OCR, subject
        // to ScreenCapture's own >=1s throttle and failure backoff.
        if (snapshot.messages.isEmpty()) {
            // In a chat window, but the tree carries no text (Feishu draws its
            // message bodies). Park the bubble BEFORE attempting OCR, so the user
            // still has something to tap when OCR is off, deduped, or comes back
            // empty — previously all three cases left the screen with no bubble.
            if (overlay?.isShowing() != true) main.post { overlay?.showIdle(snapshot.title) }
            if (prefs.ocrFallback) {
                // Gate BEFORE the shot, not after the OCR. Feishu's tree is empty
                // on every content-changed event, and a successful shot resets the
                // failure backoff — so without this the caret blinking or an
                // "online" badge flipping keeps a screenshot going out every
                // second forever. The picture can only differ if the bubbles moved
                // or the conversation changed, and that is exactly what the
                // signature measures.
                val sig = ocrSignature(pkg ?: "", snapshot.title, snapshot.bubbleRects)
                if (sig == lastOcrSignature && overlay?.isShowing() == true) return
                lastOcrSignature = sig
                ocrCapture(snapshot.title, snapshot.bubbleRects, pkg ?: "", manual = false)
            }
            return
        }

        // Switching to another adapted app resets the dedupe signature, so two apps
        // whose last few messages happen to match cannot swallow each other.
        if (pkg != activePkg) { activePkg = pkg; lastSignature = "" }

        currentSnapshot = snapshot
        val sig = snapshot.signature()
        val showing = overlay?.isShowing() == true
        // Same content and the bubble is already up → nothing to do.
        if (sig == lastSignature && showing) return
        // Same content but the bubble is gone (killed by MIUI, or we left and came
        // back) → just put the bubble back, do NOT re-analyze (saves tokens/time).
        if (sig == lastSignature && !showing) {
            val cached = synchronized(resultCache) { resultCache[memKey(pkg, snapshot.title) + "#" + sig] }
            main.post {
                overlay?.showIdle(snapshot.title)
                cached?.let { showResult(it, pkg, snapshot.title) }
            }
            return
        }
        // Anything else reaching here is a genuinely different conversation (new
        // app, or new content in this one) — leftover results from whatever was
        // shown before must not leak into it.
        main.post { overlay?.resetForNewConversation() }
        lastSignature = sig
        Log.d(TAG, "snapshot[$pkg] title.len=${snapshot.title?.length ?: 0} n=${snapshot.messages.size} " +
            snapshot.messages.takeLast(6).joinToString(" | ") { "${it.side}:${it.text.length}" }) // sides + lengths only, never content

        // Stitch the visible window onto this chat's local transcript (text only,
        // no screenshots) so the prompt sees more than one screen of history.
        // Gated by "记录聊天历史": with the switch off, nothing about the chat
        // is written to disk at all (P0-A — the write used to run regardless).
        val key = memKey(pkg, snapshot.title)
        val bottom = isAtBottom(root)
        if (prefs.contextEnabled) {
            submit { runCatching { ChatMemory.get(this).merge(key, snapshot.messages, bottom) } }
        }
        pendingSnapshot = snapshot

        // Already generated for exactly this state (came back to the chat) → show it.
        val cached = synchronized(resultCache) { resultCache[key + "#" + sig] }
        if (cached != null) {
            main.post {
                overlay?.showIdle(snapshot.title)
                showResult(cached, pkg, snapshot.title)
            }
            return
        }

        // Pre-generate in the background only when the other person spoke last and
        // auto is on; the panel never opens by itself — the bubble turns green.
        main.post { overlay?.showIdle(snapshot.title) }
        if (snapshot.latestFrom != "other" || !prefs.autoAnalyze) return
        main.removeCallbacks(debounce)
        main.postDelayed(debounce, 1000) // people often send 2-3 messages in a row
    }

    private fun adapterFor(pkg: String): ChatAppAdapter? =
        if (pkg == PKG_WECHAT) (if (prefs.wechatEnabled) wechatAdapter else null) else adapters[pkg]

    private val wechatAdapter by lazy { WeChatAdapter() }

    private fun memKey(pkg: String?, title: String?) = (pkg ?: "") + "|" + (title ?: "")

    /** Recent results, so switching away and back does not pay for a new call. */
    private val resultCache = object : LinkedHashMap<String, BilingualResult>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BilingualResult>?) = size > 30
    }

    /**
     * Whether the message list shows the newest message (cannot scroll further
     * down). The biggest scrollable node on screen is taken as the message list.
     */
    private fun isAtBottom(root: AccessibilityNodeInfo): Boolean {
        var best: AccessibilityNodeInfo? = null; var bestArea = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>(); stack.addLast(root)
        var guard = 0
        val r = Rect()
        while (stack.isNotEmpty() && guard++ < 3000) {
            val n = stack.removeLast()
            if (n.isScrollable) {
                n.getBoundsInScreen(r)
                val area = r.width() * r.height()
                if (area > bestArea) { bestArea = area; best = n }
            }
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.addLast(it) }
        }
        val list = best ?: return true
        return list.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
    }

    /**
     * Foreground is WeChat, which is fully disabled: no node-tree read, no
     * screenshot, no OCR, no fill. We only surface a one-time notice saying WeChat
     * itself blocks reading. [auto] events (accessibility callbacks)
     * show it once per WeChat visit — [wechatNoticeShown] dedupes them; a manual
     * bubble tap ([auto] = false) always shows it. Never gated by the user's
     * whitelist (it is an info notice, not a read) and only ever reached under
     * the WeChat package.
     */
    private fun showWeChatDisabled(auto: Boolean) {
        if (auto && wechatNoticeShown) return
        wechatNoticeShown = true
        // No auto-popup panel in WeChat — a full card opening by itself is
        // intrusive when others can see the screen. showNotice files the message
        // on the bubble (the overlay side turns it into a red dot, shown when the
        // user next opens the panel). Never hide() afterwards: hide() tears down
        // the overlay root and would take the just-posted notice with it — which
        // is exactly why the old hide-then-toast order never showed anything.
        main.post { overlay?.showNotice(WECHAT_DISABLED_MSG) }
    }

    /** A placeholder title an app shows only for a moment (e.g. X's "连接中…"
     *  right after opening a DM thread) — never a real conversation title.
     *  Blank/null counts too, so a caller can always fall back the same way. */
    private fun isTransientTitle(t: String?): Boolean {
        val trimmed = t?.trim()?.removeSuffix("…")?.removeSuffix("...")?.trim()
        if (trimmed.isNullOrEmpty()) return true
        val lower = trimmed.lowercase()
        return TRANSIENT_TITLE_WORDS.any { lower.contains(it.lowercase()) }
    }

    /** Replace a transient title with the last known-good one for this package
     *  (if any), and otherwise remember the current title as the new good one. */
    private fun stabilizeTitle(pkg: String, snapshot: ChatSnapshot): ChatSnapshot {
        if (isTransientTitle(snapshot.title)) {
            val good = lastGoodTitle[pkg] ?: return snapshot
            return snapshot.copy(title = good)
        }
        snapshot.title?.let { lastGoodTitle[pkg] = it }
        return snapshot
    }

    private fun runAnalysis() {
        val snapshot = pendingSnapshot ?: return
        if (analyzing) return
        // Sprint 5 (D1): the Jev judgment mode is deleted; bilingual is the only
        // path. The old prefs.bilingualMode flag is deprecated and always true.
        runBilingual(snapshot)
    }

    /**
     * The one and only pipeline: a single reply-route round trip returns the
     * Chinese translation of the other side plus 3 target-language replies
     * with Chinese glosses; "填入" fills only the target-language text.
     */
    private fun runBilingual(snapshot: ChatSnapshot) {
        if (!prefs.hasReplyKey()) { main.post { overlay?.showError("未设置回复接口密钥，去设置里填") }; return }
        analyzing = true
        // Sprint 7「换一条」闭环（有意的 prompt 产品决策变更）：上一轮被用户
        // 否定的已填入回复，这一轮生成要换角度。必须在 main.post{ showLoading() }
        // 之前取走——showLoading 会兜底清空否定列表。本函数跑在主线程，
        // consumeRejected 同步执行，顺序可靠。
        val reroll = OverlayRules.rerollNote(overlay?.consumeRejected().orEmpty())
        main.post { overlay?.showLoading(); overlay?.setNote(snapshot.note) }
        // 否定指令拼在「关系」实参末尾——ReplyClient 不在本 sprint 可改清单内，
        // 这是用户上下文里落点最靠后的最小侵入注入点。
        val relBase = prefs.relationship
        val rel = if (reroll.isEmpty()) relBase else relBase + "\n" + reroll
        val pkg = activePkg ?: ""
        val key = memKey(pkg, snapshot.title)
        val sig = snapshot.signature()
        val started = System.currentTimeMillis()
        submit {
            val ctx = try {
                ContextBuilder.build(this, snapshot, pkg, prefs)
            } catch (e: Exception) {
                Log.w(TAG, "context build failed: ${e.javaClass.simpleName}"); null
            }
            // The same switch gates the read/inject side: with "记录聊天历史"
            // off, no stitched transcript or style samples go into the prompt —
            // the model sees only what is on screen right now.
            val transcript: List<Msg>
            val style: List<String>
            if (prefs.contextEnabled) {
                val mem = ChatMemory.get(this)
                transcript = runCatching { mem.merge(key, snapshot.messages, true) }.getOrDefault(snapshot.messages)
                style = runCatching { mem.styleSamples(STYLE_SAMPLES) }.getOrDefault(emptyList())
            } else {
                transcript = snapshot.messages
                style = emptyList()
            }
            main.post { overlay?.setContextInfo(ctx?.notes?.size ?: 0, ctx?.history?.size ?: 0) }
            // 关系提议：只有确认没联系人、关系仍是默认值时才顺带索取候选。ctx 建失败时
            // 不确定有没有联系人，按「有」处理（不提）。跳过过的会话（按 App+标题记，见 RelationSkips）也不提。
            val hasContact = ctx == null || ctx.contact != null
            val isDefaultRel = relBase == Prefs.DEFAULT_REL
            val titleOk = !snapshot.title.isNullOrBlank() && !isTransientTitle(snapshot.title)
            val skipped = RelationSkips.contains(prefs.relationSkips, pkg, snapshot.title)
            val propose = titleOk && OverlayRules.shouldProposeRelation(hasContact, isDefaultRel, skipped)
            // 请求发出前才确认没联系人（例如之后被删了）：这个会话重新有资格被提议。
            // 必须在请求前清，否则会抹掉请求期间用户刚点选留下的标记。
            if (propose) relationSettled.remove(key)
            val raw = try {
                ReplyClient(prefs).draftBilingual(snapshot, rel, ctx, transcript, style, propose)
            } catch (e: Exception) {
                main.post { analyzing = false; if (isCurrent(key)) overlay?.showError(e.message ?: e.javaClass.simpleName) }
                return@submit
            }
            // 没要提议却带了候选（模型自作主张）就丢掉
            val result = if (propose) raw else raw.copy(relationCandidates = emptyList())
            Log.i(TAG, "bilingual: ${System.currentTimeMillis() - started}ms transcript=${transcript.size} style=${style.size}")
            synchronized(resultCache) { resultCache["$key#$sig"] = result }
            main.post {
                analyzing = false
                // The user may have left this chat while we were generating: keep the
                // result in the cache, but never pop it up over a different chat.
                if (isCurrent(key)) showResult(result, pkg, snapshot.title)
                // A newer message arrived mid-flight → go again for that one.
                val next = pendingSnapshot
                if (next != null && next.signature() != sig && next.latestFrom == "other" &&
                    prefs.autoAnalyze && isCurrent(memKey(activePkg, next.title))) runAnalysis()
            }
        }
    }

    /** Conversations (memKey) that got a contact while their results were cached or
     *  in flight: those results still carry candidates that must not be shown again. */
    private val relationSettled: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Show a result; the 关系提议 bar goes along only when [OverlayRules.shouldShowRelationBar]
     * allows it and the title is usable. 建档 happens only in the pick callback,
     * i.e. after the user's tap.
     */
    private fun showResult(result: BilingualResult, pkg: String?, title: String?) {
        val app = pkg ?: ""
        val key = memKey(app, title)
        val bar = result.relationCandidates.isNotEmpty() && !title.isNullOrBlank() && !isTransientTitle(title) &&
            OverlayRules.shouldShowRelationBar(
                // 缓存的结果可能早于建档（自己输入、菜单存档都不经过这里），以库里为准
                hasContact = key in relationSettled || contactExists(title, app),
                isDefaultRelationship = prefs.relationship == Prefs.DEFAULT_REL,
                skipped = RelationSkips.contains(prefs.relationSkips, app, title),
                candidates = result.relationCandidates
            )
        val shown = if (bar) result else result.copy(relationCandidates = emptyList())
        val actions = if (!bar || title == null) null else RelationBarActions(
            similar = similarContacts(title),
            onMerge = { contact ->
                relationSettled.add(key)
                submit {
                    val msg = try {
                        KbStore.get(this).mergeInto(contact.id, title, app)
                    } catch (e: Exception) { "保存失败：${e.javaClass.simpleName}" }
                    main.post { overlay?.toast(msg) }
                }
            },
            onPick = { relation ->
                relationSettled.add(key)
                submit {
                    val msg = try {
                        KbStore.get(this).saveOrMergeContact(title, app, relation)
                    } catch (e: Exception) { "保存失败：${e.javaClass.simpleName}" }
                    main.post { overlay?.toast(msg) }
                }
            },
            onCustom = {
                runCatching {
                    startActivity(Intent(this, RelationInputActivity::class.java)
                        .putExtra(RelationInputActivity.EXTRA_TITLE, title)
                        .putExtra(RelationInputActivity.EXTRA_APP, app)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
            onSkip = {
                prefs.relationSkips = RelationSkips.add(prefs.relationSkips, app, title)
                overlay?.toast("这个会话不再提示关系")
            }
        )
        overlay?.showBilingual(shown, { fillInput(it) }, actions)
    }

    private fun contactExists(title: String, app: String): Boolean =
        try { KbStore.get(this).findContact(title, app) != null } catch (e: Exception) { false }

    /** 合并推荐：名字或别名与 [title] 相同 / 互相包含的联系人，最多三个；只推荐，不合并。 */
    private fun similarContacts(title: String): List<Contact> =
        try { ContactMatch.similar(title, KbStore.get(this).contacts()) } catch (e: Exception) { emptyList() }

    /** Whether the chat identified by [key] is the one on screen right now. */
    private fun isCurrent(key: String): Boolean =
        overlay?.isShowing() == true && memKey(activePkg, currentSnapshot?.title) == key

    // ------------------------------------------------------------------ OCR

    /**
     * Bubble menu → "截屏识别一次". Works on ANY app, adapted or not: one whole
     * screen shot, every line OCR'd, lines grouped into pseudo-bubbles by line
     * spacing. Nobody can tell who said what this way, so everything is filed as
     * the other person and the panel says so.
     */
    private fun ocrCaptureManual() {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString() ?: foregroundPkg ?: activePkg ?: ""
        // WeChat is fully disabled: a manual "截屏识别一次" in WeChat must NOT take
        // a screenshot — just show the notice (a manual tap always shows it).
        if (pkg == PKG_WECHAT) { showWeChatDisabled(auto = false); return }
        // Top bar text, if this app has one we can read; else the first OCR line.
        val title = root?.let {
            findTitleInActionBar(it, Int.MAX_VALUE, resources.displayMetrics.widthPixels, resources, 0.15, 0.85)
        }
        ocrCapture(title, emptyList(), pkg, manual = true)
    }

    /**
     * What the screen would look like to a camera, as far as the tree can tell.
     *
     * Feishu: the conversation title plus every bubble rectangle and its side —
     * the bubbles move whenever the list scrolls or a message arrives, and stay
     * put when only chrome (caret, presence dot, timestamp) redraws. Apps that
     * give us no rectangles fall back to package + title, which at least stops a
     * burst of events on one screen from becoming a burst of screenshots.
     */
    private fun ocrSignature(pkg: String, title: String?, rects: List<BubbleRect>): String {
        if (rects.isEmpty()) return pkg + "|" + (title ?: "")
        return (title ?: "") + "|" + rects.joinToString(";") { br ->
            val r = br.rect
            "${r.left},${r.top},${r.right},${r.bottom},${br.side}"
        }
    }

    /**
     * Screenshot, then either OCR each known bubble rect (Feishu: the tree knows
     * where the bubbles are and who sent them, just not what they say) or OCR
     * the whole screen (everything else).
     */
    private fun ocrCapture(treeTitle: String?, rects: List<BubbleRect>, pkg: String, manual: Boolean) {
        if (ocrBusy) return
        ocrBusy = true
        screenCapture.capture { res ->
            when (res) {
                is ScreenCapture.Result.Failed -> {
                    ocrBusy = false
                    Log.i(TAG, "ocr: screenshot failed code=${res.code}")
                    // Nothing was read, so the signature must not claim this
                    // screen is done — the next event may retry, still held
                    // back by ScreenCapture's own throttle and failure backoff.
                    if (!manual) lastOcrSignature = ""
                    // Throttle/interval codes are transient timing, not
                    // something the user can act on — nagging would be constant.
                    val transient = res.code == ScreenCapture.CODE_THROTTLED || res.code == 3
                    if (manual || !transient) overlay?.showError(res.humanMessage)
                }
                is ScreenCapture.Result.Ok -> {
                    ocr.scaleX = res.scaleX; ocr.scaleY = res.scaleY
                    ocr.originX = res.originX; ocr.originY = res.originY
                    if (rects.isNotEmpty() && !manual) {
                        // Re-measure inside the callback. The rects handed in were
                        // read before the 120ms overlay-hide wait and the shot
                        // itself; one scroll tick in between and we would crop the
                        // rows next to the ones in the picture. Fall back to the
                        // old rects only if the tree gives us nothing now.
                        val fresh = rootInActiveWindow?.let { collectFeishuBubbleRects(it, resources) }
                        ocrByRects(res.bitmap, if (fresh.isNullOrEmpty()) rects else fresh, treeTitle, pkg)
                    } else ocrWholeScreen(res.bitmap, treeTitle, pkg, manual)
                }
            }
        }
    }

    /** One OCR pass per bubble rectangle; each rect becomes exactly one message. */
    private fun ocrByRects(bmp: Bitmap, rects: List<BubbleRect>, title: String?, pkg: String) {
        val sx = ocr.scaleX; val sy = ocr.scaleY
        // Screen -> bitmap: drop the window origin first. A window shot does not
        // start at (0,0) in split screen or when it excludes the status bar.
        val ox = ocr.originX; val oy = ocr.originY
        val out = arrayOfNulls<Msg>(rects.size)
        var remaining = rects.size
        rects.forEachIndexed { i, br ->
            val region = Rect(
                ((br.rect.left - ox) * sx).toInt(), ((br.rect.top - oy) * sy).toInt(),
                ((br.rect.right - ox) * sx).toInt(), ((br.rect.bottom - oy) * sy).toInt())
            ocr.recognize(bmp, region) { lines ->
                val text = cleanBubbleText(lines.joinToString(" ") { it.text })
                if (text.isNotEmpty()) out[i] = Msg(br.side, text)
                remaining--
                if (remaining == 0) {
                    runCatching { bmp.recycle() }
                    finishOcrSnapshot(ChatSnapshot(title, out.filterNotNull()), pkg, manual = false)
                }
            }
        }
    }

    /** Whole screen minus the top bar and the input area, grouped by line gaps. */
    private fun ocrWholeScreen(bmp: Bitmap, treeTitle: String?, pkg: String, manual: Boolean) {
        val region = Rect(0, (bmp.height * TOP_CROP).toInt(), bmp.width, (bmp.height * BOTTOM_CROP).toInt())
        ocr.recognize(bmp, region) { lines ->
            runCatching { bmp.recycle() }
            val msgs = groupOcrLines(lines)
            val title = treeTitle?.takeIf { it.isNotBlank() }
                ?: lines.firstOrNull()?.text?.trim()?.take(24)
            finishOcrSnapshot(ChatSnapshot(title, msgs, note = OCR_NOTE), pkg, manual)
        }
    }

    /**
     * OCR lines → "bubbles": a gap larger than 1.2x the previous line's height
     * starts a new one. Side is unknowable from a flat screen read, so every
     * group is filed as the other person (and [OCR_NOTE] says so on the panel).
     */
    private fun groupOcrLines(lines: List<OcrLine>): List<Msg> {
        val usable = lines
            .filter { it.text.isNotBlank() && !PURE_TIME.matches(it.text.trim()) }
            .sortedBy { it.bounds.top }
        val out = ArrayList<Msg>()
        val buf = StringBuilder()
        var prev: OcrLine? = null
        for (l in usable) {
            val p = prev
            if (p != null) {
                val gap = l.bounds.top - p.bounds.bottom
                val lineHeight = maxOf(p.bounds.height(), 1)
                if (gap > lineHeight * 1.2f) {
                    if (buf.isNotEmpty()) { out.add(Msg("other", buf.toString())); buf.setLength(0) }
                }
            }
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(l.text.trim())
            prev = l
        }
        if (buf.isNotEmpty()) out.add(Msg("other", buf.toString()))
        return out
    }

    /** Strip the read receipt and the timestamp Feishu glues onto a bubble. */
    private fun cleanBubbleText(raw: String): String {
        var t = raw.trim()
        var changed = true
        while (changed && t.isNotEmpty()) {
            changed = false
            for (tail in arrayOf("已读", "未读")) {
                if (t.endsWith(tail)) { t = t.removeSuffix(tail).trim(); changed = true }
            }
            TAIL_TIME.find(t)?.let { t = t.substring(0, it.range.first).trim(); changed = true }
        }
        return t
    }

    /** Shared tail of both OCR paths: dedupe, then analyze or park the bubble. */
    private fun finishOcrSnapshot(snapshot: ChatSnapshot, pkg: String, manual: Boolean) {
        ocrBusy = false
        // Counts only — OCR'd chat text never goes to logcat.
        Log.i(TAG, "ocr[$pkg] msgs=${snapshot.messages.size} manual=$manual")
        if (snapshot.messages.isEmpty()) {
            if (manual) overlay?.showError("这一屏没认出文字")
            return
        }
        if (!prefs.isAllowed(snapshot.title)) { overlay?.hide(); return }

        if (pkg.isNotEmpty() && pkg != activePkg) { activePkg = pkg; lastSignature = "" }
        currentSnapshot = snapshot
        val sig = snapshot.signature()
        // Manual taps always re-run; the automatic path dedupes like the tree path.
        if (!manual && sig == lastSignature) {
            if (overlay?.isShowing() != true) overlay?.showIdle(snapshot.title)
            return
        }
        // Same rule as the tree path: past this point the conversation is either
        // new or being force-refreshed, so drop whatever was shown before.
        overlay?.resetForNewConversation()
        lastSignature = sig

        val auto = prefs.ocrAutoAnalyze && prefs.autoAnalyze && snapshot.latestFrom == "other"
        if (manual || auto) {
            pendingSnapshot = snapshot
            main.removeCallbacks(debounce)
            runAnalysis()
        } else {
            overlay?.setNote(snapshot.note)
            overlay?.showIdle(snapshot.title)
        }
    }

    /** Fill the chat input box with the chosen reply (never sends). */
    private fun fillInput(text: String) {
        submit {
            // Fast path: SET_TEXT works when the box already has input focus and no
            // IME composing session is active.
            var ok = trySetText(text)
            if (!ok) {
                // Otherwise focus the box (pops the keyboard) and retry SET_TEXT;
                // if the IME composing region still swallows it (WeChat), PASTE from
                // the clipboard. The box is cleared before PASTE so a SET_TEXT that
                // silently took (but failed verification) never gets doubled.
                // Never clicks send.
                val edit = rootInActiveWindow?.let { findEditable(it) }
                if (edit != null) {
                    edit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Thread.sleep(300)
                    ok = trySetText(text)
                    if (!ok) {
                        copyToClipboard(text)
                        val focused = rootInActiveWindow?.let { findEditable(it) } ?: edit
                        setTextRaw(focused, "")
                        val pasted = focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                        Thread.sleep(150)
                        val after = readInput()
                        ok = (after != null && after.contains(text)) || (pasted && after == null)
                        Log.i(TAG, "fill: paste=$pasted readback=${after?.length ?: -1}")
                    }
                }
            }
            main.post {
                // The one place that reports the fill result, and it tells the
                // truth: success only after a verified write/paste. (The reply
                // card's tap handler no longer pre-announces success.)
                if (ok) overlay?.snackbar("已填入，确认后自己发送")
                else { copyToClipboard(text); overlay?.snackbar("已复制，长按输入框粘贴") }
            }
        }
    }

    /** Set text on the chat input box, verifying it actually took. */
    private fun trySetText(text: String): Boolean {
        val edit = rootInActiveWindow?.let { findEditable(it) } ?: return false
        if (!setTextRaw(edit, text)) return false
        // SET_TEXT can report success without filling an unfocused box; verify.
        // Read back through refresh() — the node cache can still hold the old
        // (empty) text right after the action, which made Feishu look like a
        // failure and triggered a second PASTE on top.
        Thread.sleep(150)
        val after = readInput()
        Log.i(TAG, "fill: setText readback=${after?.length ?: -1} want=${text.length}")
        return after == text
    }

    private fun setTextRaw(edit: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Current text of the input box, fetched fresh (bypassing the node cache). */
    private fun readInput(): String? {
        val edit = rootInActiveWindow?.let { findEditable(it) } ?: return null
        runCatching { edit.refresh() }
        return edit.text?.toString()
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val node = stack.removeLast()
            if (node.isEditable) return node
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        return null
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("jev_reply", text)
        // Android 13+ flashes clipboard contents in a preview overlay; a drafted
        // reply is chat content, so mark it sensitive and keep it out of there.
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        cm.setPrimaryClip(clip)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        // Tear the overlay down and cut its callback so a stale button tap can
        // never call back into this dead instance.
        overlay?.onManualAnalyze = null
        overlay?.onSaveContact = null
        overlay?.onOcrCapture = null
        overlay?.destroy()   // 摘 AppOps 监听、撤排队的权限重试，内含 hide()
        overlay = null
        // No debounce timers may outlive the service either.
        main.removeCallbacks(debounce)
        main.removeCallbacks(captureDebounce)
        worker.shutdownNow()
    }

    companion object {
        private const val TAG = "JEVASSIST"

        /** How many of my own past messages go into the prompt as style examples. */
        private const val STYLE_SAMPLES = 25

        /** Trailing debounce for WINDOW_CONTENT_CHANGED / VIEW_SCROLLED bursts. */
        private const val CAPTURE_DEBOUNCE_MS = 350L

        /** WeChat's package. Reading it (node tree / screenshot / OCR) is what
         *  trips WeChat's anti-screenshot risk control, so it is fully disabled:
         *  no adapter, no capture, only a one-time "not supported" notice. */
        private const val PKG_WECHAT = "com.tencent.mm"

        /** Shown once when the foreground is WeChat. Plain words, full-width
         *  punctuation; steers the user to a still-supported app. */
        private const val WECHAT_DISABLED_MSG =
            "微信已限制读取，请在别的软件上使用"

        /** Whole-screen OCR keeps the middle: no action bar, no input area. */
        private const val TOP_CROP = 0.12f
        private const val BOTTOM_CROP = 0.84f

        /** Said on the panel whenever a snapshot came from flat-screen OCR. */
        private const val OCR_NOTE = "OCR 未分边，把全部消息当作对方所说"

        private val PURE_TIME = Regex("""\d{1,2}[:：]\d{2}""")
        private val TAIL_TIME = Regex("""\d{1,2}[:：]\d{2}$""")

        /** Transient placeholder titles apps show while a chat page is still
         *  connecting/loading — see [isTransientTitle]. Matched as a substring,
         *  case-insensitive, after trimming a trailing ellipsis. */
        private val TRANSIENT_TITLE_WORDS = listOf(
            "连接中", "正在连接", "未连接", "Connecting",
            "加载中", "Loading", "同步中", "Syncing"
        )
    }
}
