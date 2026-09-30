package com.jev.probe

import android.content.res.ColorStateList
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
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.jev.probe.core.Prefs
import com.jev.probe.core.VisionRoute
import com.jev.probe.core.kb.KbSelfCheck
import com.jev.probe.core.kb.KbStore
import com.jev.probe.core.ui.UiTokens
import com.jev.probe.core.ui.color
import com.jev.probe.jev.ModelCapabilities
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
        // SPEC A5：第一屏第一张业务卡（标题栏/说明除外）。即改即存，无保存按钮。
        val aboutMeCard = card()
        aboutMeCard.addView(label("关于我（回复会照这个人的口吻写）"))
        val aboutEdit = edit(prefs.aboutMe, "例：在德国生活的中国人，做什么工作，说话随意简短，不爱用表情，德语什么水平").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 2
        }
        aboutEdit.saveDebounced { prefs.aboutMe = it }
        aboutMeCard.addView(aboutEdit)
        aboutMeCard.addView(text("另外会自动学你真实发出去的消息（长度、语气、标点），只存在手机本地。", 11f, sub))
        root.addView(aboutMeCard)

        // =================== 接口 ===================
        root.addView(section("接口"))

        // §4-2/4-3：回复接口卡排第一；识图卡、上下文装进「高级」折叠容器（Sprint 5 删掉判断卡后，
        // 高级区默认折叠写死为 true）。
        val advancedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // --- 回复接口 ---
        val replyCard = card()
        replyCard.addView(cardTitle("回复接口"))
        replyCard.addView(text("生成 3 条候选回复。任何 OpenAI 兼容地址，填到 /v1 为止。", 12f, sub))

        val replyBaseEdit = edit(prefs.replyBaseUrl, Prefs.DEFAULT_REPLY_BASE)
        val replyModelEdit = edit(prefs.replyModel, Prefs.DEFAULT_REPLY_MODEL)

        fun saveReply() {
            prefs.replyBaseUrl = replyBaseEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_REPLY_BASE }
            prefs.replyKey = replyKeyEdit.text.toString()
            prefs.replyModel = replyModelEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_REPLY_MODEL }
        }

        // D5：provider 砍到三个。地址优先于胶囊：框里是已知预设 HOST 就选中对应档，
        // 其它（含老数据里的通义地址）一律落在「自定义」。
        val replyIdx = when (prefs.replyBaseUrl.trim().trimEnd('/')) {
            Prefs.DEEPSEEK_BASE -> 0
            Prefs.DEFAULT_REPLY_BASE -> 1
            else -> 2
        }
        replyCard.addView(pills(
            listOf("DeepSeek 官方", "OpenRouter", "自定义"), replyIdx) { idx ->
            when (idx) {
                0 -> { replyBaseEdit.setText(Prefs.DEEPSEEK_BASE); replyModelEdit.setText(Prefs.DEEPSEEK_MODEL) }
                1 -> { replyBaseEdit.setText(Prefs.DEFAULT_REPLY_BASE); replyModelEdit.setText(Prefs.DEFAULT_REPLY_MODEL) }
                // 自定义：不回填，用户框里是什么就发什么
            }
            saveReply()   // 即改即存
        })
        replyCard.addView(label("Base URL"))
        replyBaseEdit.saveDebounced { saveReply() }
        replyCard.addView(replyBaseEdit)
        replyCard.addView(label("密钥"))
        replyCard.addView(edit(prefs.replyKey, "sk-...", password = true).also {
            replyKeyEdit = it
            it.saveDebounced { saveReply() }
        })
        replyCard.addView(pasteBtn(replyKeyEdit))
        replyCard.addView(label("模型"))
        replyModelEdit.saveDebounced { saveReply() }
        replyCard.addView(replyModelEdit)
        val replyResult = resultText()
        replyCard.addView(cardBtn("测试回复") {
            val base = replyBaseEdit.text.toString().trim()
            val model = replyModelEdit.text.toString().trim()
            val probe = draftPrefs(SCRATCH_REPLY) {
                replyBaseUrl = base.ifBlank { Prefs.DEFAULT_REPLY_BASE }
                replyKey = replyKeyEdit.text.toString().trim()
                replyModel = model.ifBlank { Prefs.DEFAULT_REPLY_MODEL }
            }
            if (probe.effectiveReplyKey().isBlank()) { replyResult.text = "请先填密钥"; return@cardBtn }
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
                    else "成功 ${ms}ms · ${out.replace("\n", " ").take(60)}"
                }
            }
        })
        replyCard.addView(replyResult)
        root.addView(replyCard)

        // --- 识图接口 ---
        val visionCard = card()
        visionCard.addView(cardTitle("识图接口（默认与回复同一家，一般不用填）"))
        visionCard.addView(text("读不到控件文字的 App 会走截图识别。回复用 DeepSeek 时，识图直接用同一把密钥。", 12f, sub))

        val visionBaseEdit = edit(prefs.visionBaseUrl, Prefs.DEEPSEEK_BASE)
        val visionModelEdit = edit(prefs.visionModel, Prefs.DEEPSEEK_VISION_MODEL)

        // 框里正好是「跟随回复」的默认值就存空：这样只改密钥不会把默认值钉死，
        // 之后回复换成 DeepSeek，识图也跟着走。
        fun saveVision() {
            val replyBase = prefs.replyBaseUrl
            val base = visionBaseEdit.text.toString().trim()
                .let { if (it.trimEnd('/') == VisionRoute.defaultBase(replyBase)) "" else it }
            prefs.visionBaseUrl = base
            prefs.visionKey = visionKeyEdit.text.toString()
            val effectiveBase = base.ifBlank { VisionRoute.defaultBase(replyBase) }
            prefs.visionModel = visionModelEdit.text.toString().trim()
                .let { if (it == VisionRoute.defaultModel(effectiveBase)) "" else it }
        }

        val visionIdx = when (prefs.visionBaseUrl.trim().trimEnd('/')) {
            Prefs.DEEPSEEK_BASE -> 0
            Prefs.DEFAULT_VISION_BASE -> 1
            Prefs.DASHSCOPE_BASE -> 2
            else -> 3
        }
        visionCard.addView(pills(
            listOf("DeepSeek 官方", "OpenRouter", "通义兼容", "自定义"), visionIdx) { idx ->
            when (idx) {
                0 -> { visionBaseEdit.setText(Prefs.DEEPSEEK_BASE); visionModelEdit.setText(Prefs.DEEPSEEK_VISION_MODEL) }
                1 -> { visionBaseEdit.setText(Prefs.DEFAULT_VISION_BASE); visionModelEdit.setText(Prefs.DEFAULT_VISION_MODEL) }
                2 -> { visionBaseEdit.setText(Prefs.DASHSCOPE_BASE); visionModelEdit.setText(Prefs.DASHSCOPE_VISION_MODEL) }
            }
            saveVision()   // 即改即存
        })
        visionCard.addView(label("Base URL"))
        visionBaseEdit.saveDebounced { saveVision() }
        visionCard.addView(visionBaseEdit)
        visionCard.addView(label("密钥"))
        visionCard.addView(edit(prefs.visionKey, "留空则用回复接口密钥（仅限同一家）", password = true).also {
            visionKeyEdit = it
            it.saveDebounced { saveVision() }
        })
        visionCard.addView(pasteBtn(visionKeyEdit))
        visionCard.addView(label("模型"))
        visionModelEdit.saveDebounced { saveVision() }
        visionCard.addView(visionModelEdit)
        val visionResult = resultText()
        visionCard.addView(cardBtn("测试视觉") {
            val visionBase = visionBaseEdit.text.toString().trim()
            val probe = draftPrefs(SCRATCH_VISION) {
                replyBaseUrl = replyBaseEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_REPLY_BASE }
                replyKey = replyKeyEdit.text.toString().trim()
                replyModel = replyModelEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_REPLY_MODEL }
                visionBaseUrl = visionBase
                visionKey = visionKeyEdit.text.toString().trim()
                visionModel = visionModelEdit.text.toString().trim()
            }
            if (probe.effectiveVisionKey().isBlank()) { visionResult.text = "请先填密钥（和回复是同一家时可留空用回复密钥）"; return@cardBtn }
            visionResult.text = "测试中…"
            worker.execute {
                val t0 = System.currentTimeMillis()
                var err: String? = null
                val out = try {
                    VisionClient(probe).ask(whitePixelJpegB64(), "这张图是什么颜色？只回答颜色。")
                } catch (e: Exception) { err = e.message; "" }
                val ms = System.currentTimeMillis() - t0
                // 回复模型能不能直接看图（供后面「图直接附进回复请求」用），顺手查一次
                val replySees = if (err != null) null else ModelCapabilities.shared.supportsImage(
                    probe.replyBaseUrl, probe.effectiveReplyKey(), probe.replyModel)
                main.post {
                    visionResult.text = if (err != null) "失败（${ms}ms）：$err"
                    else "成功 ${ms}ms · ${out.replace("\n", " ").take(60)}" +
                        "\n回复模型${if (replySees == true) "支持" else "不支持（或查不到）"}直接看图"
                }
            }
        })
        visionCard.addView(visionResult)
        advancedBox.addView(visionCard)

        // --- 上下文（记录聊天历史 + 条数），随识图卡一起沉进「高级」 ---
        val ctxCard = card()
        val ctxRow = toggleRow("记录聊天历史（只存本机，用于关联上下文）", prefs.contextEnabled) { on ->
            prefs.contextEnabled = on
        }
        ctxCard.addView(ctxRow)
        ctxCard.addView(text("关闭时不写任何聊天内容到磁盘；笔记与联系人匹配仍然照常工作。", 11f, sub))
        ctxCard.addView(label("上下文条数"))
        val ctxCountEdit = edit(prefs.contextHistoryCount.toString(), "30").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        ctxCountEdit.saveDebounced { v ->
            prefs.contextHistoryCount = v.trim().toIntOrNull()?.coerceIn(0, 100) ?: 30
        }
        ctxCard.addView(ctxCountEdit)
        advancedBox.addView(ctxCard)

        // --- 「高级」折叠条：点击只切 visibility，容器与卡内状态不重建、不丢失 ---
        // Sprint 5：判断卡已删，高级区默认折叠写死为 true（原 foldAdvancedByDefault 随之删除）。
        var advancedFolded = true
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
        foldBar.addView(text("高级：识图 · 上下文", 13f, sub, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        foldBar.addView(foldArrow)
        root.addView(foldBar)
        root.addView(advancedBox)
        setAdvancedFolded(advancedFolded)

        // =================== 分析 ===================
        root.addView(section("分析"))
        val card2 = card()
        // 「关于我」已上移到第一屏独立成卡（SPEC A5），本卡从关系开始。
        card2.addView(label("关系（回复会参考）"))
        val relEdit = edit(prefs.relationship, Prefs.DEFAULT_REL)
        relEdit.saveDebounced { prefs.relationship = it }   // blank stays blank, on purpose
        card2.addView(relEdit)
        card2.addView(label("会话白名单（每行一个关键词，空=所有会话）"))
        val wlEdit = edit(prefs.whitelist.joinToString("\n"), "留空则对所有会话生效").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 2
        }
        wlEdit.saveDebounced { v ->
            prefs.whitelist = v.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
        card2.addView(wlEdit)
        val autoRow = toggleRow("新消息自动在后台生成（气泡亮起，点开即看）", prefs.autoAnalyze) { on ->
            prefs.autoAnalyze = on
        }
        card2.addView(autoRow)
        // D3：新版微信对读屏隐藏消息文字且禁止截屏，开关保留但标注暂不可用。
        val wechatRow = toggleRow("微信（暂不可用）", prefs.wechatEnabled) { on ->
            prefs.wechatEnabled = on
        }
        card2.addView(wechatRow)
        card2.addView(text("新版微信对读屏隐藏消息文字且禁止截屏，读不到。开关保留，恢复支持后可用。", 11f, sub))

        // --- 截图识别兜底 ---
        val ocrFallbackRow = toggleRow("读不到文字时用截图识别", prefs.ocrFallback) { on ->
            prefs.ocrFallback = on
        }
        card2.addView(ocrFallbackRow)
        card2.addView(text("飞书的消息文字读不出来，这时截一次屏在本地识别，不上传。", 11f, sub))
        val ocrAutoRow = toggleRow("OCR 模式自动分析", prefs.ocrAutoAnalyze) { on ->
            prefs.ocrAutoAnalyze = on
        }
        card2.addView(ocrAutoRow)
        card2.addView(text("关闭时 OCR 认完只亮悬浮球，点一下再分析。", 11f, sub))

        // --- 知识库 ---
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
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(color(pal.accentSoft))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    opacityLabel.text = "悬浮窗不透明度：${p + 60}%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    prefs.overlayOpacity = (sb?.progress ?: 0) + 60   // 松手即存
                }
            })
        }
        card3.addView(seek)
        // Sprint 7：吸边可选，默认关（现状「停在哪就在哪」，躲 MIUI 边缘手势）
        val snapRow = toggleRow("气泡松手后吸边", prefs.bubbleSnap) { on ->
            prefs.bubbleSnap = on   // 即改即存
        }
        card3.addView(snapRow)
        card3.addView(text("默认关：停在哪就在哪，避开手机边缘手势", 11f, sub))
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
        // Deliberately low-key: a developer aid, not a user feature.
        val selfCheckResult = resultText()
        aboutCard.addView(text("自检", 12f, sub).apply {
            setPadding(dp(4), dp(12), dp(8), dp(4))
            setOnClickListener {
                selfCheckResult.text = "自检中…"
                worker.execute {
                    val out = try { KbSelfCheck.run(this@SettingsActivity) }
                    catch (e: Exception) { "自检异常：${e.javaClass.simpleName} ${e.message ?: ""}" }
                    main.post { selfCheckResult.text = out }
                }
            }
        })
        aboutCard.addView(selfCheckResult)
        root.addView(aboutCard)

        // 即改即存：没有保存按钮，留一行小字说明。
        root.addView(text("改动即保存", 11f, sub).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(20) }
        })

        setContentView(scroll)
    }

    // Held as fields because several test buttons read each other's key box.
    private lateinit var replyKeyEdit: EditText
    private lateinit var visionKeyEdit: EditText

    /** 即改即存：停止输入 400ms 后落盘；连续输入只记最后一版。 */
    private fun EditText.saveDebounced(save: (String) -> Unit) {
        var pending: Runnable? = null
        doAfterTextChanged { editable ->
            val v = editable?.toString() ?: ""
            pending?.let { main.removeCallbacks(it) }
            val r = Runnable { save(v) }
            pending = r
            main.postDelayed(r, 400L)
        }
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
            setHorizontalFadingEdgeEnabled(true)
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
            onToggle?.invoke(now)   // 即改即存：点击时立即写 prefs
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
                Toast.makeText(this@SettingsActivity, "已粘贴（${clip.length} 位）", Toast.LENGTH_SHORT).show()
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

    private fun round(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = radius.toFloat(); setColor(color); if (stroke) setStroke(dp(1), accent)
    }

    override fun onDestroy() { super.onDestroy(); worker.shutdownNow() }

    companion object {
        private const val TAG = "JEVASSIST"

        /** One scratch prefs file per test button; never the real config. */
        private const val SCRATCH_REPLY = "jev_probe_scratch_reply"
        private const val SCRATCH_VISION = "jev_probe_scratch_vision"

        private const val PRIVACY_URL = "https://chatjevs.com/privacy.html"
        private const val REPO_URL = "https://github.com/jev-chat/jev-chat-jarvis"
    }
}
