package com.bharatdroid.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        // Singleton — SkillRunner grabs this to execute actions
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        val isConnected: Boolean get() = instance != null
    }

    override fun onServiceConnected() {
        instance = this
        serviceInfo = serviceInfo.also { info ->
            info.flags = info.flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op — we use pull-model (read screen on demand) not push-model
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ── Screen Reading ────────────────────────

    fun getScreenText(): String {
        val sb = StringBuilder()
        collectText(rootInActiveWindow, sb, depth = 0)
        return sb.toString().trim()
    }

    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        return rootInActiveWindow
            ?.findAccessibilityNodeInfosByText(text)
            ?.firstOrNull()
    }

    fun findNodeById(viewId: String): AccessibilityNodeInfo? {
        return rootInActiveWindow
            ?.findAccessibilityNodeInfosByViewId(viewId)
            ?.firstOrNull()
    }

    fun getCurrentPackage(): String? {
        return rootInActiveWindow?.packageName?.toString()
    }

    // ── Helpers ───────────────────────────────

    private fun collectText(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        node ?: return
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val hint = node.hintText?.toString()
        when {
            !text.isNullOrBlank() -> sb.appendLine(text)
            !desc.isNullOrBlank() -> sb.appendLine(desc)
            !hint.isNullOrBlank() -> sb.appendLine("[hint: $hint]")
        }
        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), sb, depth + 1)
        }
    }
}
