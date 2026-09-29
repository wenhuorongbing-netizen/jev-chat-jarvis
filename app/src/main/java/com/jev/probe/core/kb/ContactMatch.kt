package com.jev.probe.core.kb

/**
 * 合并推荐的名称匹配：会话标题与联系人的名字或别名相同、或互相包含，就推荐。
 * 只负责挑出候选；合并永远由用户点选，这里不改任何数据。
 */
object ContactMatch {

    /** 互相包含时，较短的一方至少这么长；再短（如「王」）会把无关联系人全扫进来。 */
    private const val MIN_CONTAINED_LEN = 2

    /** How far [title] is from [name] in extra characters, or null when they do not match. */
    private fun distance(title: String, name: String): Int? {
        if (title == name) return 0
        val (short, long) = if (title.length <= name.length) title to name else name to title
        if (short.length < MIN_CONTAINED_LEN || !long.contains(short)) return null
        return long.length - short.length
    }

    /**
     * Contacts whose name or alias matches [title] (both through [KbStore.normalizeName],
     * so member counts and case are ignored), closest first, at most [max].
     */
    fun similar(title: String?, contacts: List<Contact>, max: Int = 3): List<Contact> {
        val want = KbStore.normalizeName(title)
        if (want.isEmpty() || max <= 0) return emptyList()
        return contacts
            .mapNotNull { c ->
                val d = (listOf(c.name) + c.aliases)
                    .map { KbStore.normalizeName(it) }
                    .filter { it.isNotEmpty() }
                    .mapNotNull { distance(want, it) }
                    .minOrNull()
                d?.let { c to it }
            }
            .sortedBy { it.second }   // stable: equally close ones keep the stored order
            .take(max)
            .map { it.first }
    }
}
