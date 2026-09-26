package com.jev.probe

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.kb.KbSelfCheck
import com.jev.probe.core.kb.KbStore
import com.jev.probe.core.ui.OverlayRules
import com.jev.probe.core.ui.UiTokens
import com.jev.probe.core.ui.color
import com.jev.probe.jev.JudgeClient
import com.jev.probe.jev.ReplyClient
import com.jev.probe.jev.VisionClient
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // §4-1 token 化 + 深色：以打开瞬间的 uiMode 为准（Activity 不重建，规格允许）。
    private val pal: UiTokens.Palette by lazy {
        UiTokens.palette(
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES)
    }
    private val accent by lazy { color(pal.accent) }
    private val ink by lazy { color(pal.ink) }
    private val sub by lazy { color(pal.sub) }
    private val pillOff by lazy { color(pal.card) }

    /** Selected provider index per card, held so Save can read it back. */
    private var judgeProviderIdx = 0

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Log.i(TAG, "settings opened judgeKey.len=${prefs.judgeKey.length}" +
            " replyKey.len=${prefs.replyKey.length} visionKey.len=${prefs.visionKey.length}")
        val pal = this.pal
        window.decorView.setBackgroundColor(color(pal.canvas))

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
        }
        root.padForSystemBars()   // edge-to-edge: keep the title off the status bar
        scroll.addView(root)

        root.addView(header("设置"))

        // =================== 关于我 ===================
        // SPEC A5：第一屏第一张业务卡（标题栏/说明除外）。保存在底部统一进行。
        val aboutMeCard = card()
        aboutMeCard.addView(label("关于我（回复会照这个人的口吻写）"))
        val aboutEdit = edit(prefs.aboutMe, "例：在德国生活的中国人，做什么工作，说话随意简短，不爱用表情，德语什么水平").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 2
        }
        aboutMeCard.addView(aboutEdit)
        aboutMeCard.addView(text("另外会自动学你真实发出去的消息（长度、语气、标点），只存在手机本地。", 11f, sub))
        root.addView(aboutMeCard)

        // =================== 接口 ===================
        root.addView(section("接口"))

        // §4-2/4-3：回复接口卡排第一；判断卡与视觉卡装进「高级」折叠容器。
        // 容器先建好，判断/视觉卡的构建代码原样保留，只是 addView 目标换成它。
        val advancedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // --- 判断接口（Jev） ---
        val judgeCard = card()
        judgeCard.addView(cardTitle("判断接口（Jev）"))
        judgeCard.addView(text("读对方消息、给意图判断和候选排序。只有关掉「智能回复」时才需要。", 12f, sub))

        val judgeBaseEdit = edit(prefs.judgeBaseUrl, Prefs.DEFAULT_JUDGE_BASE_OPENROUTER)
        val judgeModelEdit = edit(prefs.judgeModel, Prefs.DEFAULT_JUDGE_MODEL_OPENROUTER)
        judgeProviderIdx = when (prefs.judgeProvider) {
            Prefs.PROVIDER_OPENROUTER -> 0
            Prefs.PROVIDER_BOCHA -> 1
            Prefs.PROVIDER_TYPESAFE -> 2
            Prefs.PROVIDER_VERCEL -> 3
            Prefs.PROVIDER_ZEN -> 4
            Prefs.PROVIDER_CUSTOM -> 5
            else -> 0
        }
        // Bocha promo block — official address + one-tap copy (limited-time free).
        // Shown ONLY when Bocha Jev is the selected provider; picking any other
        // provider hides it. It used to be added unconditionally, which made every
        // tab look like it was still showing Bocha.
        val bochaBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bochaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        bochaRow.addView(text("${Prefs.DEFAULT_JUDGE_BASE_BOCHA}（限时免费）", 12.5f, ink).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        bochaRow.addView(TextView(this).apply {
            text = "复制"; textSize = 13f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accent); background = round(dp(10), color(pal.surface), stroke = true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText(
                    "jev_bocha", Prefs.DEFAULT_JUDGE_BASE_BOCHA))
                Toast.makeText(this@SettingsActivity, "已复制", Toast.LENGTH_SHORT).show()
            }
        })
        bochaBox.addView(bochaRow)
        bochaBox.addView(text("去 jev.bocha.cn 领取限时免费 API Key", 11f, sub))
        bochaBox.visibility = if (judgeProviderIdx == 1) View.VISIBLE else View.GONE

        judgeCard.addView(pills(
            listOf("OpenRouter", "博查 Jev", "TypeSafe 直连", "Vercel", "OpenCode Zen", "自定义"), judgeProviderIdx) { idx ->
            judgeProviderIdx = idx
            when (idx) {
                0 -> {
                    judgeBaseEdit.setText(Prefs.DEFAULT_JUDGE_BASE_OPENROUTER)
                    judgeModelEdit.setText(Prefs.DEFAULT_JUDGE_MODEL_OPENROUTER)
                }
                1 -> {
                    judgeBaseEdit.setText(Prefs.DEFAULT_JUDGE_BASE_BOCHA)
                    judgeModelEdit.setText(Prefs.DEFAULT_JUDGE_MODEL_BOCHA)
                }
                2 -> {
                    judgeBaseEdit.setText(Prefs.DEFAULT_JUDGE_BASE_TYPESAFE)
                    judgeModelEdit.setText(Prefs.DEFAULT_JUDGE_MODEL_TYPESAFE)
                }
                3 -> {
                    judgeBaseEdit.setText(Prefs.DEFAULT_JUDGE_BASE_VERCEL)
                    judgeModelEdit.setText(Prefs.DEFAULT_JUDGE_MODEL_VERCEL)
                }
                4 -> {
                    judgeBaseEdit.setText(Prefs.DEFAULT_JUDGE_BASE_ZEN)
                    judgeModelEdit.setText(Prefs.DEFAULT_JUDGE_MODEL_ZEN)
                }
                // Custom POSTs the box verbatim, so a preset HOST left in the box
                // would hit the API root. Expand it into the full endpoint the
                // preset would have used; anything hand-typed is left alone.
                5 -> judgeBaseEdit.setText(expandJudgeUrl(judgeBaseEdit.text.toString()))
            }
            bochaBox.visibility = if (idx == 1) View.VISIBLE else View.GONE
        })
        judgeCard.addView(label("Base URL"))
        judgeCard.addView(judgeBaseEdit)
        judgeCard.addView(text("OpenRouter 拼 /alpha/decisions；博查 Jev / TypeSafe / Vercel / OpenCode Zen 拼 /v1/systemone；自定义按原样 POST。Vercel 用 AI Gateway 的密钥，OpenCode Zen 用 Zen 的密钥。",
            11f, sub))
        judgeCard.addView(bochaBox)
        judgeCard.addView(label("密钥"))
        judgeCard.addView(edit(prefs.judgeKey, "sk-...", password = true).also { judgeKeyEdit = it })
        judgeCard.addView(pasteBtn(judgeKeyEdit))
        judgeCard.addView(label("模型"))
        judgeCard.addView(judgeModelEdit)
        val judgeResult = resultText()
        judgeCard.addView(cardBtn("测试判断") {
            val base = judgeBaseEdit.text.toString().trim()
            val key = judgeKeyEdit.text.toString().trim()
            val model = judgeModelEdit.text.toString().trim()
            if (key.isBlank()) { judgeResult.text = "请先填密钥"; return@cardBtn }
            judgeResult.text = "测试中…"
            // Provider follows the address when it is still a known preset host,
            // so a stale pill selection cannot send a TypeSafe path to OpenRouter.
            val provider = resolveJudgeProvider(judgeProviderIdx, base)
            if (provider == Prefs.PROVIDER_CUSTOM && base.isBlank()) {
                judgeResult.text = "自定义档要填完整 URL（带路径）"; return@cardBtn
            }
            // Custom means we know nothing about the endpoint — guessing a model
            // name here would test something the user never asked for.
            if (provider == Prefs.PROVIDER_CUSTOM && model.isBlank()) {
                judgeResult.text = "请填写模型名"; return@cardBtn
            }
            val probe = draftPrefs(SCRATCH_JUDGE) {
                judgeProvider = provider
                judgeBaseUrl = base.ifBlank { defaultJudgeBase(provider) }
                judgeKey = key
                judgeModel = model.ifBlank { defaultJudgeModel(provider) }
            }
            worker.execute {
                val t0 = System.currentTimeMillis()
                val demo = ChatSnapshot("连通测试", listOf(
                    Msg("other", "在吗？"), Msg("me", "在")))
                val a = JudgeClient(probe).judge(demo, prefs.relationship)
                val ms = System.currentTimeMillis() - t0
                main.post {
                    judgeResult.text = if (a.error != null) "失败（${ms}ms）：${a.error}"
                    else "成功 ${ms}ms · 意图=${a.trueIntent?.choice ?: "?"}" +
                        "（置信 ${pct(a.trueIntent?.confidence)}）"
                }
            }
        })
        judgeCard.addView(judgeResult)
        advancedBox.addView(judgeCard)

        // --- 回复接口 ---
        val replyCard = card()
        replyCard.addView(cardTitle("回复接口"))
        replyCard.addView(text("生成 3 条候选回复。任何 OpenAI 兼容地址，填到 /v1 为止。", 12f, sub))

        val replyBaseEdit = edit(prefs.replyBaseUrl, Prefs.DEFAULT_REPLY_BASE)
        val replyModelEdit = edit(prefs.replyModel, Prefs.DEFAULT_REPLY_MODEL)
        val replyIdx = when (prefs.replyBaseUrl.trim().trimEnd('/')) {
            Prefs.DEFAULT_REPLY_BASE -> 0
            Prefs.DEEPSEEK_BASE -> 1
            Prefs.DASHSCOPE_BASE -> 2
            else -> 3
        }
        replyCard.addView(pills(
            listOf("OpenRouter", "DeepSeek 官方", "通义兼容", "自定义"), replyIdx) { idx ->
            when (idx) {
                0 -> { replyBaseEdit.setText(Prefs.DEFAULT_REPLY_BASE); replyModelEdit.setText(Prefs.DEFAULT_REPLY_MODEL) }
                1 -> { replyBaseEdit.setText(Prefs.DEEPSEEK_BASE); replyModelEdit.setText(Prefs.DEEPSEEK_MODEL) }
                2 -> { replyBaseEdit.setText(Prefs.DASHSCOPE_BASE); replyModelEdit.setText(Prefs.DASHSCOPE_MODEL) }
            }
        })
        replyCard.addView(label("Base URL"))
        replyCard.addView(replyBaseEdit)
        replyCard.addView(label("密钥"))
        replyCard.addView(edit(prefs.replyKey, "留空则用判断接口密钥", password = true).also { replyKeyEdit = it })
        replyCard.addView(pasteBtn(replyKeyEdit))
        replyCard.addView(label("模型"))
        replyCard.addView(replyModelEdit)
        val replyResult = resultText()
        replyCard.addView(cardBtn("测试回复") {
            val base = replyBaseEdit.text.toString().trim()
            val model = replyModelEdit.text.toString().trim()
            val probe = draftPrefs(SCRATCH_REPLY) {
                judgeKey = judgeKeyEdit.text.toString().trim()
                replyBaseUrl = base.ifBlank { Prefs.DEFAULT_REPLY_BASE }
                replyKey = replyKeyEdit.text.toString().trim()
                replyModel = model.ifBlank { Prefs.DEFAULT_REPLY_MODEL }
            }
            if (probe.effectiveReplyKey().isBlank()) { replyResult.text = "请先填密钥（或填判断接口密钥）"; return@cardBtn }
            replyResult.text = "测试中…"
            worker.execute {
                val t0 = System.currentTimeMillis()
                var err: String? = null
                val out = try {
                    ReplyClient(probe).ping()
                } catch (e: Exception) { err = e.message; "" }
                val ms = System.currentTimeMillis() - t0
                main.post {
                    replyResult.text = if (err != null) "失败（${ms}ms）：$err"
                    else "成功 ${ms}ms · 返回：${out.replace("\n", " ").take(60)}"
                }
            }
        })
        replyCard.addView(replyResult)
        root.addView(replyCard)

        // --- 视觉接口 ---
        val visionCard = card()
        visionCard.addView(cardTitle("视觉接口（OCR 用，可先不填）"))
        visionCard.addView(text("读不到控件树的 App 走截图识别。B 阶段才用到，现在填不填都不影响。", 12f, sub))

        val visionBaseEdit = edit(prefs.visionBaseUrl, Prefs.DEFAULT_VISION_BASE)
        val visionModelEdit = edit(prefs.visionModel, Prefs.DEFAULT_VISION_MODEL)
        val visionIdx = when (prefs.visionBaseUrl.trim().trimEnd('/')) {
            Prefs.DEFAULT_VISION_BASE -> 0
            Prefs.DASHSCOPE_BASE -> 1
            else -> 2
        }
        visionCard.addView(pills(
            listOf("OpenRouter", "通义兼容", "自定义"), visionIdx) { idx ->
            when (idx) {
                0 -> { visionBaseEdit.setText(Prefs.DEFAULT_VISION_BASE); visionModelEdit.setText(Prefs.DEFAULT_VISION_MODEL) }
                1 -> { visionBaseEdit.setText(Prefs.DASHSCOPE_BASE); visionModelEdit.setText(Prefs.DASHSCOPE_VISION_MODEL) }
            }
        })
        visionCard.addView(label("Base URL"))
        visionCard.addView(visionBaseEdit)
        visionCard.addView(label("密钥"))
        visionCard.addView(edit(prefs.visionKey, "留空则用回复接口密钥", password = true).also { visionKeyEdit = it })
        visionCard.addView(pasteBtn(visionKeyEdit))
        visionCard.addView(label("模型"))
        visionCard.addView(visionModelEdit)
        val visionResult = resultText()
        visionCard.addView(cardBtn("测试视觉") {
            val visionBase = visionBaseEdit.text.toString().trim()
            if (!VisionClient.supportsVision(visionBase.ifBlank { Prefs.DEFAULT_VISION_BASE })) {
                visionResult.text = GUARD_NO_VISION
                return@cardBtn
            }
            val probe = draftPrefs(SCRATCH_VISION) {
                judgeKey = judgeKeyEdit.text.toString().trim()
                replyBaseUrl = replyBaseEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_REPLY_BASE }
                replyKey = replyKeyEdit.text.toString().trim()
                visionBaseUrl = visionBase
                visionKey = visionKeyEdit.text.toString().trim()
                visionModel = visionModelEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_VISION_MODEL }
            }
            if (probe.effectiveVisionKey().isBlank()) { visionResult.text = "请先填密钥（或填回复/判断接口密钥）"; return@cardBtn }
            visionResult.text = "测试中…"
            worker.execute {
                val t0 = System.currentTimeMillis()
                var err: String? = null
                val out = try {
                    VisionClient(probe).ask(whitePixelJpegB64(), "这张图是什么颜色？只回答颜色。")
                } catch (e: Exception) { err = e.message; "" }
                val ms = System.currentTimeMillis() - t0
                main.post {
                    visionResult.text = if (err != null) "失败（${ms}ms）：$err"
                    else "成功 ${ms}ms · 返回：${out.replace("\n", " ").take(60)}"
                }
            }
        })
        visionCard.addView(visionResult)
        advancedBox.addView(visionCard)

        // --- 「高级」折叠条：点击只切 visibility，容器与卡内状态不重建、不丢失 ---
        var advancedFolded = OverlayRules.foldAdvancedByDefault(prefs.bilingualMode)
        val foldArrow = text("", 13f, accent, bold = true)
        fun setAdvancedFolded(folded: Boolean) {
            advancedFolded = folded
            advancedBox.visibility = if (folded) View.GONE else View.VISIBLE
            foldArrow.text = if (folded) "展开" else "收起"
        }
        val foldBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(16), dp(4), dp(0))
            // v2 施工 8：整行按压反馈（alpha 瞬态），返回 false 不吞 click。
            setOnTouchListener { v, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> v.alpha = 0.6f
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.alpha = 1f
                }
                false
            }
            setOnClickListener { setAdvancedFolded(!advancedFolded) }
        }
        foldBar.addView(text("高级：判断接口 · 识图接口", 13f, sub, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        foldBar.addView(foldArrow)
        root.addView(foldBar)
        root.addView(advancedBox)
        setAdvancedFolded(advancedFolded)

        // =================== 分析 ===================
        root.addView(section("分析"))
        val card2 = card()
        // 「关于我」已上移到第一屏独立成卡（SPEC A5），本卡从关系描述开始。
        card2.addView(label("关系描述（给 Jev 判断用）"))
        val relEdit = edit(prefs.relationship, Prefs.DEFAULT_REL)
        card2.addView(relEdit)
        card2.addView(label("会话白名单（每行一个关键词，空=所有会话）"))
        val wlEdit = edit(prefs.whitelist.joinToString("\n"), "留空则对所有会话生效").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 2
        }
        card2.addView(wlEdit)
        val autoRow = toggleRow("新消息自动在后台生成（气泡变绿，点开即看）", prefs.autoAnalyze)
        card2.addView(autoRow)
        val wechatRow = toggleRow("微信（实验）", prefs.wechatEnabled)
        card2.addView(wechatRow)
        card2.addView(text("新版微信对读屏服务隐藏了消息文字，部分设备还禁止截屏，可能读不到。", 11f, sub))

        // --- 双语模式 ---
        // §4-3：开关切换时同步高级容器折叠（开→折叠，关→展开），只改 visibility，不改 prefs。
        val bilingualRow = toggleRow("智能回复（跟随对方语言，不用 Jev）", prefs.bilingualMode) { on ->
            setAdvancedFolded(on)
        }
        card2.addView(bilingualRow)
        card2.addView(text("只用「回复接口」一把密钥：对方说中文就中文回；说外语就翻成中文给你看，3 条回复用对方的语言写并附中文意思，填入只填外语。", 11f, sub))

        // --- OCR 兜底（B 阶段）---
        val ocrFallbackRow = toggleRow("树读不到正文时用 OCR 兜底", prefs.ocrFallback)
        card2.addView(ocrFallbackRow)
        card2.addView(text("飞书正文是画上去的，节点树里读不到，这时截一次屏本地识别（不上传）。", 11f, sub))
        val ocrAutoRow = toggleRow("OCR 模式自动分析", prefs.ocrAutoAnalyze)
        card2.addView(ocrAutoRow)
        card2.addView(text("关闭时 OCR 认完只亮悬浮球，点一下再分析。", 11f, sub))

        // --- 知识库 / 关联上下文（D 阶段） ---
        val ctxRow = toggleRow("记录聊天历史（只存本机，用于关联上下文）", prefs.contextEnabled)
        card2.addView(ctxRow)
        card2.addView(text("关闭时不写任何聊天内容到磁盘；笔记与联系人匹配仍然照常工作。", 11f, sub))
        card2.addView(label("注入最近历史条数（0–100）"))
        val ctxCountEdit = edit(prefs.contextHistoryCount.toString(), "30").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        card2.addView(ctxCountEdit)
        card2.addView(cardBtn("知识库与联系人") {
            startActivity(android.content.Intent(this, KnowledgeActivity::class.java))
        })
        val kbResult = resultText()
        card2.addView(cardBtn("清空知识库与历史") {
            val c = KbStore.get(this).counts()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("清空知识库与历史")
                .setMessage("将删除 ${c.notes} 条笔记、${c.contacts} 个联系人、${c.logLines} 条聊天历史。" +
                    "密钥、白名单等设置不受影响。不可恢复。")
                .setPositiveButton("清空") { _, _ ->
                    KbStore.get(this).clearAll()
                    kbResult.text = "已清空知识库与历史"
                }
                .setNegativeButton("取消", null)
                .show()
        })
        // Deliberately low-key: a developer aid, not a user feature.
        card2.addView(text("自检", 12f, sub).apply {
            setPadding(dp(4), dp(12), dp(8), dp(4))
            setOnClickListener {
                kbResult.text = "自检中…"
                worker.execute {
                    val out = try { KbSelfCheck.run(this@SettingsActivity) }
                    catch (e: Exception) { "自检异常：${e.javaClass.simpleName} ${e.message ?: ""}" }
                    main.post { kbResult.text = out }
                }
            }
        })
        card2.addView(kbResult)
        root.addView(card2)

        // =================== 外观 ===================
        root.addView(section("外观"))
        val card3 = card()
        val opacityLabel = label("悬浮窗不透明度：${prefs.overlayOpacity}%")
        card3.addView(opacityLabel)
        card3.addView(text("越低越透，越能看清下面的聊天", 12f, sub))
        val seek = SeekBar(this).apply {
            max = 40; progress = prefs.overlayOpacity - 60  // 60..100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    opacityLabel.text = "悬浮窗不透明度：${p + 60}%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        card3.addView(seek)
        root.addView(card3)

        // =================== 关于与隐私 ===================
        root.addView(section("关于与隐私"))
        val aboutCard = card()
        aboutCard.addView(text(
            "这个 App 会读取你当前聊天窗口的文字，发给你自己配置的模型接口做判断和起草回复。作者不运营服务器，收不到你的数据。",
            12f, sub))
        aboutCard.addView(cardBtn("隐私政策") { openUrl(PRIVACY_URL) })
        aboutCard.addView(cardBtn("开源仓库") { openUrl(REPO_URL) })
        aboutCard.addView(text(versionLabel(), 11f, sub).apply { setPadding(0, dp(12), 0, dp(4)) })
        root.addView(aboutCard)

        // =================== 保存 ===================
        root.addView(primaryBtn("保存全部设置") {
            // Address wins over the pill: a preset HOST in the box means that
            // preset's provider (and so its path), whatever the pill last said.
            val judgeBaseTyped = judgeBaseEdit.text.toString().trim()
            val judgeProv = resolveJudgeProvider(judgeProviderIdx, judgeBaseTyped)
            val judgeModelTyped = judgeModelEdit.text.toString().trim()
            prefs.judgeProvider = judgeProv
            // Blank falls back to THIS provider's preset — never OpenRouter's by
            // default. Custom is left exactly as typed (blank included): guessing
            // a URL for it would silently point somewhere the user did not choose.
            prefs.judgeBaseUrl = when {
                judgeBaseTyped.isNotBlank() -> judgeBaseTyped
                judgeProv == Prefs.PROVIDER_CUSTOM -> ""
                else -> defaultJudgeBase(judgeProv)
            }
            prefs.judgeKey = judgeKeyEdit.text.toString()
            prefs.judgeModel = when {
                judgeModelTyped.isNotBlank() -> judgeModelTyped
                judgeProv == Prefs.PROVIDER_CUSTOM -> ""
                else -> defaultJudgeModel(judgeProv)
            }

            prefs.replyBaseUrl = replyBaseEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_REPLY_BASE }
            prefs.replyKey = replyKeyEdit.text.toString()
            prefs.replyModel = replyModelEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_REPLY_MODEL }

            prefs.visionBaseUrl = visionBaseEdit.text.toString().trim()
            prefs.visionKey = visionKeyEdit.text.toString()
            prefs.visionModel = visionModelEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_VISION_MODEL }

            prefs.relationship = relEdit.text.toString()   // blank stays blank, on purpose
            prefs.whitelist = wlEdit.text.toString().split("\n")
                .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            prefs.autoAnalyze = (autoRow.tag as? Boolean) ?: true
            prefs.wechatEnabled = (wechatRow.tag as? Boolean) ?: false
            prefs.aboutMe = aboutEdit.text.toString()
            prefs.bilingualMode = (bilingualRow.tag as? Boolean) ?: false
            prefs.ocrFallback = (ocrFallbackRow.tag as? Boolean) ?: true
            prefs.ocrAutoAnalyze = (ocrAutoRow.tag as? Boolean) ?: false
            prefs.contextEnabled = (ctxRow.tag as? Boolean) ?: false
            prefs.contextHistoryCount =
                ctxCountEdit.text.toString().trim().toIntOrNull()?.coerceIn(0, 100) ?: 30
            prefs.overlayOpacity = seek.progress + 60
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        })

        setContentView(scroll)
    }

    // Held as fields because several test buttons read each other's key box.
    private lateinit var judgeKeyEdit: EditText
    private lateinit var replyKeyEdit: EditText
    private lateinit var visionKeyEdit: EditText

    private fun providerOf(idx: Int) = when (idx) {
        1 -> Prefs.PROVIDER_BOCHA
        2 -> Prefs.PROVIDER_TYPESAFE
        3 -> Prefs.PROVIDER_VERCEL
        4 -> Prefs.PROVIDER_ZEN
        5 -> Prefs.PROVIDER_CUSTOM
        else -> Prefs.PROVIDER_OPENROUTER
    }

    /**
     * The provider actually implied by what is in the address box. A preset host
     * carries its own path (`/alpha/decisions`, `/v1/systemone`), so leaving that
     * host in the box while the pill says something else would POST the wrong
     * path — or, for custom, the bare API root.
     */
    private fun resolveJudgeProvider(idx: Int, base: String): String =
        when (base.trim().trimEnd('/')) {
            Prefs.DEFAULT_JUDGE_BASE_BOCHA -> Prefs.PROVIDER_BOCHA
            Prefs.DEFAULT_JUDGE_BASE_OPENROUTER -> Prefs.PROVIDER_OPENROUTER
            Prefs.DEFAULT_JUDGE_BASE_TYPESAFE -> Prefs.PROVIDER_TYPESAFE
            Prefs.DEFAULT_JUDGE_BASE_VERCEL -> Prefs.PROVIDER_VERCEL
            Prefs.DEFAULT_JUDGE_BASE_ZEN -> Prefs.PROVIDER_ZEN
            else -> providerOf(idx)
        }

    /** The full endpoint a preset host would have been expanded to. */
    private fun expandJudgeUrl(base: String): String = when (base.trim().trimEnd('/')) {
        Prefs.DEFAULT_JUDGE_BASE_BOCHA -> Prefs.DEFAULT_JUDGE_BASE_BOCHA + "/v1/systemone"
        Prefs.DEFAULT_JUDGE_BASE_OPENROUTER -> Prefs.DEFAULT_JUDGE_BASE_OPENROUTER + "/alpha/decisions"
        Prefs.DEFAULT_JUDGE_BASE_TYPESAFE -> Prefs.DEFAULT_JUDGE_BASE_TYPESAFE + "/v1/systemone"
        Prefs.DEFAULT_JUDGE_BASE_VERCEL -> Prefs.DEFAULT_JUDGE_BASE_VERCEL + "/v1/systemone"
        Prefs.DEFAULT_JUDGE_BASE_ZEN -> Prefs.DEFAULT_JUDGE_BASE_ZEN + "/v1/systemone"
        else -> base.trim()
    }

    private fun defaultJudgeBase(provider: String): String = when (provider) {
        Prefs.PROVIDER_BOCHA -> Prefs.DEFAULT_JUDGE_BASE_BOCHA
        Prefs.PROVIDER_TYPESAFE -> Prefs.DEFAULT_JUDGE_BASE_TYPESAFE
        Prefs.PROVIDER_VERCEL -> Prefs.DEFAULT_JUDGE_BASE_VERCEL
        Prefs.PROVIDER_ZEN -> Prefs.DEFAULT_JUDGE_BASE_ZEN
        else -> Prefs.DEFAULT_JUDGE_BASE_OPENROUTER
    }

    private fun defaultJudgeModel(provider: String): String = when (provider) {
        Prefs.PROVIDER_BOCHA -> Prefs.DEFAULT_JUDGE_MODEL_BOCHA
        Prefs.PROVIDER_TYPESAFE -> Prefs.DEFAULT_JUDGE_MODEL_TYPESAFE
        Prefs.PROVIDER_VERCEL -> Prefs.DEFAULT_JUDGE_MODEL_VERCEL
        Prefs.PROVIDER_ZEN -> Prefs.DEFAULT_JUDGE_MODEL_ZEN
        else -> Prefs.DEFAULT_JUDGE_MODEL_OPENROUTER
    }

    /**
     * A throwaway [Prefs] view carrying exactly what is in the boxes right now,
     * so a test button probes the typed values rather than the saved ones. Each
     * button gets its OWN scratch file — they used to share one and clear it out
     * from under each other when two tests overlapped. The real config is never
     * touched either way.
     */
    private fun draftPrefs(scratchName: String, fill: Prefs.() -> Unit): Prefs {
        getSharedPreferences(scratchName, MODE_PRIVATE).edit().clear().commit()
        return Prefs(this, scratchName).apply(fill)
    }

    /** Opens an external link; swallows the failure with a toast rather than crashing. */
    private fun openUrl(url: String) {
        runCatching {
            startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Toast.makeText(this, "打不开浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun versionLabel(): String = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        "版本 v${pi.versionName}（${pi.longVersionCode}）"
    } catch (e: Exception) {
        "版本 —"
    }

    /** 1x1 white JPEG for the vision smoke test, via the real encoder path. */
    private fun whitePixelJpegB64(): String {
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        return VisionClient.encodeJpeg(bmp)
    }

    private fun pct(d: Double?): String =
        if (d == null) "?" else "${(d * 100).roundToInt()}%"

    /** Horizontal selectable pills; calls [onPick] with the chosen index. */
    private fun pills(options: List<String>, initial: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val views = ArrayList<TextView>()
        options.forEachIndexed { i, opt ->
            val pill = TextView(this).apply {
                text = opt; textSize = 12.5f; gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
            }
            views.add(pill)
            pill.setOnClickListener {
                views.forEachIndexed { j, v -> paintPill(v, j == i) }
                onPick(i)
            }
            row.addView(pill)
        }
        views.forEachIndexed { j, v -> paintPill(v, j == initial) }
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        return scroller
    }

    private fun paintPill(v: TextView, on: Boolean) {
        v.setTextColor(if (on) Color.WHITE else sub)
        v.setTypeface(v.typeface, if (on) Typeface.BOLD else Typeface.NORMAL)
        // v2 施工 6：选中态换渐变，未选中保持 pal.card。
        v.background = if (on) accentGradientBg(9) else round(dp(9), pillOff)
    }

    private fun toggleRow(
        labelText: String,
        initial: Boolean,
        onToggle: ((Boolean) -> Unit)? = null,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(4)); tag = initial
        }
        val lab = text(labelText, 14f, ink).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = TextView(this).apply {
            text = if (initial) "开" else "关"; textSize = 13f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (initial) Color.WHITE else sub)
            // v2 施工 6：toggle 选中态换渐变，未选中保持 pal.card。
            background = if (initial) accentGradientBg(10) else round(dp(10), color(pal.card))
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        sw.setOnClickListener {
            val now = !((row.tag as? Boolean) ?: true); row.tag = now
            sw.text = if (now) "开" else "关"
            sw.setTextColor(if (now) Color.WHITE else sub)
            sw.background = if (now) accentGradientBg(10) else round(dp(10), color(pal.card))
            onToggle?.invoke(now)
        }
        row.addView(lab); row.addView(sw)
        return row
    }

    // atoms
    private fun header(t: String) = text(t, 24f, ink, bold = true).apply { setPadding(0, 0, 0, dp(4)) }
    // v2.3：section 标题 12sp 粗体 + letterSpacing 0.08，颜色 faint。
    private fun section(t: String) = text(t, 12f, color(pal.faint), bold = true).apply {
        letterSpacing = 0.08f
        setPadding(dp(4), dp(16), 0, dp(8))
    }
    private fun label(t: String) = text(t, 13f, ink, bold = true).apply { setPadding(0, dp(12), 0, dp(4)) }
    private fun cardTitle(t: String) = text(t, 16f, ink, bold = true).apply { setPadding(0, dp(12), 0, dp(4)) }
    private fun resultText() = text("", 12.5f, sub).apply { setPadding(0, dp(12), 0, dp(4)) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(dp(14), color(pal.surface))
        elevation = dp(2).toFloat()   // v2.3：卡片柔和投影
        clipToOutline = true
        setPadding(dp(16), dp(4), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) }
    }

    /** 有些 ROM 的安全键盘在密码框里不给粘贴，单独放一个按钮：只读剪贴板写进输入框，不显示内容。 */
    private fun pasteBtn(target: EditText) = TextView(this).apply {
        text = "粘贴"; textSize = 13f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent); background = round(dp(10), color(pal.surface), stroke = true)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                ?.coerceToText(this@SettingsActivity)?.toString()?.trim().orEmpty()
            if (clip.isEmpty()) {
                Toast.makeText(this@SettingsActivity, "剪贴板是空的", Toast.LENGTH_SHORT).show()
            } else {
                target.setText(clip)
                Toast.makeText(this@SettingsActivity, "已粘贴（${clip.length} 位），记得保存", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun edit(value: String, hint: String, password: Boolean = false) = EditText(this).apply {
        setText(value); this.hint = hint; textSize = 14f; setTextColor(ink)
        setHintTextColor(color(pal.faint))
        background = round(dp(8), color(pal.card))
        setPadding(dp(12), dp(12), dp(12), dp(12))
        // Masked, not VISIBLE_PASSWORD: an API key should not sit in plain sight.
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun primaryBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        // v2.1：主按钮改 accentLight → accent 渐变，圆角不变；按压回弹见 pressBounce。
        setTextColor(Color.WHITE); background = accentGradientBg(12)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) }
        pressBounce()
        setOnClickListener { onClick() }
    }

    /** Outlined button sized for inside a card. */
    private fun cardBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent); background = round(dp(10), color(pal.surface), stroke = true)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
        setOnClickListener { onClick() }
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

    private fun round(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = radius.toFloat(); setColor(color); if (stroke) setStroke(dp(1), accent)
    }

    override fun onDestroy() { super.onDestroy(); worker.shutdownNow() }

    companion object {
        private const val TAG = "JEVASSIST"

        /** DeepSeek's official API has no vision model; say so instead of a 400. */
        private const val GUARD_NO_VISION =
            "该接口不支持视觉（DeepSeek 官方没有 image_url），请换 OpenRouter 或通义兼容"

        /** One scratch prefs file per test button; never the real config. */
        private const val SCRATCH_JUDGE = "jev_probe_scratch_judge"
        private const val SCRATCH_REPLY = "jev_probe_scratch_reply"
        private const val SCRATCH_VISION = "jev_probe_scratch_vision"

        private const val PRIVACY_URL = "https://chatjevs.com/privacy.html"
        private const val REPO_URL = "https://github.com/jev-chat/jev-chat-jarvis"
    }
}
