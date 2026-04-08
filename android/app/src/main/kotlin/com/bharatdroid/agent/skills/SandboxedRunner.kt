package com.bharatdroid.agent.skills

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.bharatdroid.agent.AgentAccessibilityService
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────
// SANDBOXED RUNNER
//
// This is what skills actually call. Every method
// checks permissions before doing anything.
// A skill cannot bypass this — it has no direct
// access to the accessibility service or Android APIs.
// ─────────────────────────────────────────────

class SandboxedRunner(
    private val manifest: SkillManifest,
    private val service: AgentAccessibilityService,
    private val context: Context,
) {
    // ── App Control ──────────────────────────

    fun openApp(packageName: String) {
        requirePermission(Permission.OPEN_APP)
        require(packageName in manifest.allowedPackages) {
            "Skill '${manifest.id}' tried to open '$packageName' which is not in its allowedPackages."
        }
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw SecurityException("Cannot find app: $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // ── Screen Reading ────────────────────────

    fun readScreen(): String {
        requirePermission(Permission.READ_SCREEN)
        return service.getScreenText()
    }

    fun findByText(text: String): AccessibilityNodeInfo? {
        requirePermission(Permission.READ_SCREEN)
        return service.findNodeByText(text)
    }

    fun findById(viewId: String): AccessibilityNodeInfo? {
        requirePermission(Permission.READ_SCREEN)
        return service.findNodeById(viewId)
    }

    fun screenContains(text: String): Boolean {
        requirePermission(Permission.READ_SCREEN)
        return service.getScreenText().contains(text, ignoreCase = true)
    }

    // ── Interaction ───────────────────────────

    fun tap(node: AccessibilityNodeInfo): Boolean {
        requirePermission(Permission.TAP)
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun tapByText(text: String): Boolean {
        requirePermission(Permission.TAP)
        val node = service.findNodeByText(text) ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun typeText(node: AccessibilityNodeInfo, text: String): Boolean {
        requirePermission(Permission.TYPE)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun typeInFieldWithHint(hint: String, text: String): Boolean {
        requirePermission(Permission.TYPE)
        val node = service.findNodeByText(hint) ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scrollDown(): Boolean {
        requirePermission(Permission.SCROLL)
        val root = service.rootInActiveWindow ?: return false
        return root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    fun scrollUp(): Boolean {
        requirePermission(Permission.SCROLL)
        val root = service.rootInActiveWindow ?: return false
        return root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    fun pressBack() {
        requirePermission(Permission.NAVIGATE_BACK)
        service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
    }

    // ── Clipboard ─────────────────────────────

    fun setClipboard(text: String) {
        requirePermission(Permission.CLIPBOARD)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("BharatDroid", text))
    }

    // ── Wait Helpers ─────────────────────────

    suspend fun waitForText(text: String, timeoutMs: Long = 8000): Boolean {
        requirePermission(Permission.READ_SCREEN)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (screenContains(text)) return true
            delay(300)
        }
        return false
    }

    suspend fun waitForApp(packageName: String, timeoutMs: Long = 5000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (service.getCurrentPackage() == packageName) return true
            delay(300)
        }
        return false
    }

    // ── Internal ──────────────────────────────

    private fun requirePermission(permission: Permission) {
        if (permission !in manifest.permissions) {
            throw SecurityException(
                "Skill '${manifest.id}' attempted to use '$permission' without declaring it. " +
                "This is a skill bug — report to the skill author."
            )
        }
    }
}
