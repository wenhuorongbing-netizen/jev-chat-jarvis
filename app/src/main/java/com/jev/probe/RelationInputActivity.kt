package com.jev.probe

import androidx.appcompat.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.kb.KbStore

/**
 * 关系提议里「自己输入」的落点：悬浮窗不可聚焦、打不了字，所以弹这个透明
 * Activity 的输入框。用户点「保存」才建档（复用 [KbStore.saveOrMergeContact]）；
 * 取消什么都不做。
 */
class RelationInputActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val app = intent.getStringExtra(EXTRA_APP).orEmpty()
        if (title.isBlank()) { finish(); return }

        val input = EditText(this).apply {
            hint = "例如：同事，带我做项目的组长"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle("对方是？")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val relation = input.text.toString().trim()
                if (relation.isNotEmpty()) save(title, app, relation)
                finish()
            }
            .setNegativeButton("取消") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
        input.requestFocus()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    private fun save(title: String, app: String, relation: String) {
        val ctx = applicationContext
        Thread {
            val msg = try {
                KbStore.get(ctx).saveOrMergeContact(title, app, relation).message
            } catch (e: Exception) { "保存失败：${e.javaClass.simpleName}" }
            runOnUiThread { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
        }.start()
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_APP = "app"
    }
}
