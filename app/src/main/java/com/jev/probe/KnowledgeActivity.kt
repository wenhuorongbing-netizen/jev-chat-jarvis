package com.jev.probe

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.kb.Contact
import com.jev.probe.core.kb.KbResult
import com.jev.probe.core.kb.KbStore
import com.jev.probe.core.kb.Note
import com.jev.probe.core.ui.UiTokens
import com.jev.probe.core.ui.color
import kotlin.math.roundToInt

/**
 * Knowledge base manager: the notes the assistant may quote, and the contacts
 * that tie a conversation title (across apps) to a person.
 *
 * Everything shown here lives in the app-private `filesDir/kb` directory and
 * nowhere else. Plain code-built views, same card/pill vocabulary as
 * [SettingsActivity].
 */
class KnowledgeActivity : AppCompatActivity() {

    private lateinit var store: KbStore
    private lateinit var container: LinearLayout

    /** 0 = notes, 1 = contacts. */
    private var tab = 0

    private val pal by lazy {
        val dark = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        UiTokens.palette(dark)
    }
    private val accent by lazy { color(pal.accent) }
    private val ink by lazy { color(pal.ink) }
    private val sub by lazy { color(pal.sub) }
    private val pillOff by lazy { color(pal.card) }
    private val red by lazy { color(pal.danger) }
    private val surface by lazy { color(pal.surface) }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = KbStore.get(this)
        window.decorView.setBackgroundColor(color(pal.canvas))

        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
        }
        container.padForSystemBars()   // edge-to-edge: keep the title off the status bar
        scroll.addView(container)
        setContentView(scroll)
        render()
    }

    // --------------------------------------------------------------- screens

    private fun render() {
        container.removeAllViews()
        container.addView(text("知识库与联系人", 24f, ink, bold = true))
        container.addView(text("只存在本机，不上传。分析时按会话标题匹配联系人、按关键词命中笔记。",
            12f, sub).apply { setPadding(0, dp(8), 0, dp(4)) })
        container.addView(tabs())
        if (tab == 0) renderNotes() else renderContacts()
    }

    private fun tabs(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        listOf("笔记", "联系人").forEachIndexed { i, name ->
            val pill = TextView(this).apply {
                text = name; textSize = 13f; gravity = Gravity.CENTER
                setPadding(dp(20), dp(8), dp(20), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
                setTextColor(if (i == tab) Color.WHITE else sub)
                setTypeface(typeface, if (i == tab) Typeface.BOLD else Typeface.NORMAL)
                background = if (i == tab) accentGradientBg(9) else round(dp(9), pillOff)
                setOnClickListener { tab = i; render() }
            }
            row.addView(pill)
        }
        return row
    }

    // ----------------------------------------------------------------- notes

    private fun renderNotes() {
        val notes = store.notes().sortedByDescending { it.updatedAt }
        container.addView(twoButtons("新建笔记", { editNoteDialog(null) },
            "从文本导入", { importNotesDialog() }))
        if (notes.isEmpty()) {
            container.addView(emptyCard("还没有笔记。写点该记住的事实：习惯、忌口、项目代号、约定过的时间。"))
            return
        }
        notes.forEach { container.addView(noteRow(it)) }
        container.addView(text("点条目编辑，长按删除。命中规则：任一标签或标题出现在会话标题或最近 6 条消息里。",
            11f, sub).apply { setPadding(dp(4), dp(12), 0, 0) })
    }

    private fun noteRow(n: Note): View {
        val c = card()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val head = n.title.ifBlank { "（无标题）" } + if (n.alwaysOn) "  · 常驻" else ""
        left.addView(text(head, 15f, ink, bold = true))
        left.addView(text(
            if (n.tags.isEmpty()) "无标签" else "标签：" + n.tags.joinToString("、"),
            12f, sub).apply { setPadding(0, dp(4), 0, 0) })
        left.addView(text(n.content.replace("\n", " ").take(46), 12f, sub)
            .apply { setPadding(0, dp(4), 0, 0) })
        row.addView(left)
        row.addView(smallToggle(n.enabled) {
            toastIfFailed(store.saveNote(n.copy(enabled = !n.enabled), mustExist = true)); render()
        })
        c.addView(row)
        c.setOnClickListener { editNoteDialog(n) }
        c.setOnLongClickListener {
            confirm("删除笔记", "删除「${n.title}」？不可恢复。") {
                toastIfFailed(store.deleteNote(n.id)); render()
            }
            true
        }
        return c
    }

    private fun editNoteDialog(existing: Note?) {
        val box = dialogBox()
        val titleEdit = edit(existing?.title ?: "", "标题，例如：口味忌口")
        val contentEdit = edit(existing?.content ?: "", "正文，写清楚事实本身").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4; gravity = Gravity.TOP
        }
        val tagsEdit = edit(existing?.tags?.joinToString("，") ?: "", "逗号分隔，例如：吃饭，周末")
        val alwaysRow = toggleRow("常驻（每次分析都带上）", existing?.alwaysOn ?: false)
        val enabledRow = toggleRow("启用", existing?.enabled ?: true)
        box.addView(label("标题")); box.addView(titleEdit)
        box.addView(label("正文")); box.addView(contentEdit)
        box.addView(label("标签")); box.addView(tagsEdit)
        box.addView(alwaysRow); box.addView(enabledRow)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新建笔记" else "编辑笔记")
            .setView(wrapScroll(box))
            .setPositiveButton("保存") { _, _ ->
                val title = titleEdit.text.toString().trim()
                val content = contentEdit.text.toString().trim()
                if (title.isBlank() && content.isBlank()) {
                    toast("标题和正文不能都空着"); return@setPositiveButton
                }
                toastIfFailed(store.saveNote(Note(
                    id = existing?.id ?: KbStore.newId(),
                    title = title,
                    content = content,
                    tags = splitTags(tagsEdit.text.toString()),
                    alwaysOn = (alwaysRow.tag as? Boolean) ?: false,
                    enabled = (enabledRow.tag as? Boolean) ?: true
                ), mustExist = existing != null))
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun importNotesDialog() {
        val box = dialogBox()
        box.addView(text("按空行分段，每段第一行当标题，其余当正文。", 12f, sub))
        val input = edit("", "口味忌口\n不吃香菜，海鲜过敏\n\n项目代号\n内部叫小蓝").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 8; gravity = Gravity.TOP
        }
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle("从文本导入")
            .setView(wrapScroll(box))
            .setPositiveButton("导入") { _, _ ->
                val chunks = input.text.toString().split(Regex("\\r?\\n[ \\t]*\\r?\\n"))
                var n = 0
                var failed = 0
                chunks.forEach { chunk ->
                    val lines = chunk.trim().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                    if (lines.isNotEmpty()) {
                        val saved = store.saveNote(Note(
                            id = KbStore.newId(),
                            title = lines.first(),
                            content = lines.drop(1).joinToString("\n")
                        ))
                        if (saved.committed) n++ else failed++
                    }
                }
                toast(when {
                    n == 0 && failed == 0 -> "没解析出内容"
                    failed == 0 -> "已导入 ${n} 条"
                    else -> "已导入 ${n} 条，${failed} 条没保存成功"
                })
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun splitTags(raw: String): List<String> =
        raw.split(",", "，", "、").map { it.trim() }.filter { it.isNotEmpty() }

    // -------------------------------------------------------------- contacts

    private fun renderContacts() {
        val contacts = store.contacts().sortedByDescending { it.updatedAt }
        container.addView(twoButtons("新建联系人", { editContactDialog(null) }, null, null))
        if (contacts.isEmpty()) {
            container.addView(emptyCard(
                "还没有联系人。也可以在聊天里长按悬浮球，选「把当前会话存为联系人」。"))
            return
        }
        contacts.forEach { container.addView(contactRow(it)) }
        container.addView(text("点条目编辑，长按删除。会话标题等于名字或任一别名即算命中（忽略大小写与群人数后缀）。",
            11f, sub).apply { setPadding(dp(4), dp(12), 0, 0) })
    }

    private fun contactRow(c0: Contact): View {
        val c = card()
        c.addView(text(c0.name.ifBlank { "（无名）" }, 15f, ink, bold = true))
        if (c0.aliases.isNotEmpty())
            c.addView(text("别名：" + c0.aliases.joinToString("、"), 12f, sub)
                .apply { setPadding(0, dp(4), 0, 0) })
        if (c0.apps.isNotEmpty())
            c.addView(text("来源：" + c0.apps.joinToString("、") { appLabel(it) }, 12f, sub)
                .apply { setPadding(0, dp(4), 0, 0) })
        if (c0.relationship.isNotBlank())
            c.addView(text("关系：" + c0.relationship.replace("\n", " ").take(40), 12f, sub)
                .apply { setPadding(0, dp(4), 0, 0) })
        if (c0.notes.isNotBlank())
            c.addView(text("备注：" + c0.notes.replace("\n", " ").take(40), 12f, sub)
                .apply { setPadding(0, dp(4), 0, 0) })

        val logN = store.logSize(c0.id)
        val clear = TextView(this).apply {
            text = "清空此人历史（$logN 条）"
            textSize = 12.5f; setTextColor(red); setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
            setOnClickListener {
                if (logN == 0) { toast("本来就没有历史"); return@setOnClickListener }
                confirm("清空历史", "删掉「${c0.name}」的 $logN 条聊天历史？联系人档案保留。") {
                    store.clearLog(c0.id); render()
                }
            }
        }
        c.addView(clear)
        c.setOnClickListener { editContactDialog(c0) }
        c.setOnLongClickListener {
            confirm("删除联系人", "删除「${c0.name}」及其全部历史？不可恢复。") {
                toastIfFailed(store.deleteContact(c0.id)); render()
            }
            true
        }
        return c
    }

    private fun editContactDialog(existing: Contact?) {
        val box = dialogBox()
        val nameEdit = edit(existing?.name ?: "", "名字，一般就是会话标题")
        val aliasEdit = edit(existing?.aliases?.joinToString("\n") ?: "", "每行一个，例如另一个 App 里的昵称").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3; gravity = Gravity.TOP
        }
        val relEdit = edit(existing?.relationship ?: "", "例如：同事，带我做项目的组长")
        val notesEdit = edit(existing?.notes ?: "", "关于这个人要记住的事").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3; gravity = Gravity.TOP
        }
        box.addView(label("名字")); box.addView(nameEdit)
        box.addView(label("别名（每行一个）")); box.addView(aliasEdit)
        box.addView(label("关系")); box.addView(relEdit)
        box.addView(label("备注")); box.addView(notesEdit)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新建联系人" else "编辑联系人")
            .setView(wrapScroll(box))
            .setPositiveButton("保存") { _, _ ->
                val name = nameEdit.text.toString().trim()
                if (name.isBlank()) { toast("名字不能空"); return@setPositiveButton }
                toastIfFailed(store.saveContact(Contact(
                    id = existing?.id ?: KbStore.newId(),
                    name = name,
                    aliases = aliasEdit.text.toString().split("\n")
                        .map { it.trim() }.filter { it.isNotEmpty() },
                    apps = existing?.apps ?: emptyList(),
                    relationship = relEdit.text.toString().trim(),
                    notes = notesEdit.text.toString().trim(),
                    autoSummary = existing?.autoSummary ?: "",
                    isGroup = existing?.isGroup ?: false
                ), mustExist = existing != null))
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** Tells the user when a write did not go through; true only for a real commit. */
    private fun toastIfFailed(r: KbResult): Boolean {
        if (!r.committed) toast(r.message)
        return r.committed
    }

    private fun appLabel(pkg: String): String = when (pkg) {
        "com.tencent.mobileqq" -> "QQ"
        "com.ss.android.lark" -> "飞书"
        "com.twitter.android" -> "X"
        else -> pkg
    }

    // ----------------------------------------------------------------- atoms

    private fun confirm(title: String, msg: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title).setMessage(msg)
            .setPositiveButton("确定") { _, _ -> onYes() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun dialogBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(4))
    }

    private fun wrapScroll(v: View) = ScrollView(this).apply { addView(v) }

    private fun twoButtons(
        a: String, onA: () -> Unit,
        b: String?, onB: (() -> Unit)?
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        row.addView(wideBtn(a, true, onA))
        if (b != null && onB != null) row.addView(wideBtn(b, false, onB))
        return row
    }

    private fun wideBtn(labelText: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = labelText; textSize = 14f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (primary) Color.WHITE else accent)
        // v2.1：主按钮改渐变；次按钮保持 surface 底 + accent 语义描边。
        background = if (primary) accentGradientBg(11) else round(dp(11), surface, stroke = true)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { rightMargin = dp(8) }
        if (primary) pressBounce()
        setOnClickListener { onClick() }
    }

    private fun smallToggle(on: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = if (on) "开" else "关"; textSize = 13f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (on) Color.WHITE else sub)
        // v2 施工 6：toggle 选中态换渐变，未选中保持 pal.card。
        background = if (on) accentGradientBg(10) else round(dp(10), pillOff)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setOnClickListener { onClick() }
    }

    private fun toggleRow(labelText: String, initial: Boolean): LinearLayout {
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
            background = if (initial) accentGradientBg(10) else round(dp(10), pillOff)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        sw.setOnClickListener {
            val now = !((row.tag as? Boolean) ?: true); row.tag = now
            sw.text = if (now) "开" else "关"
            sw.setTextColor(if (now) Color.WHITE else sub)
            sw.background = if (now) accentGradientBg(10) else round(dp(10), pillOff)
        }
        row.addView(lab); row.addView(sw)
        return row
    }

    private fun emptyCard(msg: String): View = card().apply { addView(text(msg, 13f, sub)) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(dp(14), surface)
        elevation = dp(2).toFloat()   // v2.3：卡片柔和投影
        clipToOutline = true
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) }
    }

    private fun label(t: String) = text(t, 13f, ink, bold = true).apply { setPadding(0, dp(12), 0, dp(4)) }

    private fun edit(value: String, hintText: String) = EditText(this).apply {
        setText(value); hint = hintText; textSize = 14f; setTextColor(ink)
        setHintTextColor(color(pal.faint))
        background = round(dp(8), pillOff)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(4) }
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
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
        cornerRadius = radius.toFloat(); setColor(color)
        if (stroke) setStroke(dp(1), accent)
    }
}
