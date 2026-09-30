package com.jev.probe.capture

/** Every adapter the app knows, in one place so the service and the fill-support test see the same list. */
object ChatAdapters {
    /** Adapted chat apps, keyed by package name. WeChat is not in here: see [wechat]. */
    val live: Map<String, ChatAppAdapter> =
        listOf(QQAdapter(), XAdapter(), FeishuAdapter(), WhatsAppAdapter(), WhatsAppAdapter(WhatsAppAdapter.PKG_BUSINESS))
            .associateBy { it.pkg }

    /** Kept for a possible restore: WeChat is fully disabled in the product unless the user turns it on. */
    val wechat: ChatAppAdapter by lazy { WeChatAdapter() }

    val all: List<ChatAppAdapter> get() = live.values.toList() + wechat
}
