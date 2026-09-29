package com.jev.probe.core.kb

/**
 * 关系提议的「跳过」记忆：按会话（App + 归一化标题）记，不写进联系人——
 * 跳过的时候联系人本来就不存在。纯函数，集合由 [com.jev.probe.core.Prefs] 持久化。
 */
object RelationSkips {

    /** null = 标题为空，这样的会话没法识别，也就不记。 */
    private fun key(app: String, title: String?): String? {
        val name = KbStore.normalizeName(title)
        return if (name.isEmpty()) null else app + "|" + name
    }

    fun contains(skips: Set<String>, app: String, title: String?): Boolean =
        key(app, title)?.let { it in skips } ?: false

    fun add(skips: Set<String>, app: String, title: String?): Set<String> =
        key(app, title)?.let { skips + it } ?: skips
}
