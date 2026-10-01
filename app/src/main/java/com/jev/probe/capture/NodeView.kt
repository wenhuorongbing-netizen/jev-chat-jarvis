package com.jev.probe.capture

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The few things an adapter's pure logic needs from a node, so it can run on an accessibility
 * tree in the service and on a uiautomator dump in a unit test. Bounds are screen coordinates.
 */
interface NodeView {
    val id: String?
    val text: String?
    val left: Int
    val top: Int
    val right: Int
    val bottom: Int
    val children: List<NodeView>
}

/** A live node. Children are read when asked for, so a walk that stops early costs nothing. */
class AccessibilityNodeView(private val node: AccessibilityNodeInfo) : NodeView {
    private val bounds = Rect().also { node.getBoundsInScreen(it) }
    override val id: String? get() = node.viewIdResourceName
    override val text: String? get() = node.text?.toString()
    override val left get() = bounds.left
    override val top get() = bounds.top
    override val right get() = bounds.right
    override val bottom get() = bounds.bottom
    override val children: List<NodeView>
        get() = (0 until node.childCount).mapNotNull { node.getChild(it)?.let(::AccessibilityNodeView) }
}
