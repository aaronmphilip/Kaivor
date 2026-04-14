package com.bharatdroid.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred

data class ScreenElement(
    val text: String,
    val type: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val centerX: Int,
    val centerY: Int,
    val width: Int,
    val height: Int,
    val viewId: String = "",
    val packageName: String = "",
    val className: String = "",
    val hint: String = "",
    val contentDescription: String = "",
    val isCheckable: Boolean = false,
    val isScrollable: Boolean = false,
    val isSelected: Boolean = false,
)

class AgentAccessibilityService : AccessibilityService() {

    companion object {
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

    /**
     * Proactive popup dismisser — listens for TYPE_WINDOW_STATE_CHANGED events.
     *
     * Key insight from OpenClaw/AppAgent research: instead of waiting for the agent loop
     * to notice a popup (which costs 1-2 steps of stuck time), we can dismiss obvious
     * non-essential popups the moment their window appears. This is what makes fast agents
     * appear "seamless" — they never visibly pause on a rate-us dialog.
     *
     * We only auto-dismiss popups that are clearly non-essential:
     * - Notification permission requests (not needed for most tasks)
     * - Rate-us dialogs
     * - "Watch history" / "Search history" banners (YouTube specific)
     *
     * We do NOT dismiss: location dialogs, payment dialogs, login prompts
     * (those require the agent or user to handle intentionally).
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // Run popup dismissal on background thread — avoid blocking UI
        android.os.Handler(mainLooper).postDelayed({
            tryAutoDismissNonEssentialPopup()
        }, 300) // small delay to let window fully render
    }

    private fun tryAutoDismissNonEssentialPopup() {
        val root = rootInActiveWindow ?: return
        try {
            val sb = StringBuilder()
            collectText(root, sb)
            val screen = sb.toString().lowercase()

            // Notification permission popups — "Turn on notifications" / "Enable notifications"
            val isNotificationPopup = (screen.contains("turn on notifications")
                || screen.contains("enable notifications")
                || screen.contains("allow notifications")
                || screen.contains("send you notifications"))

            // Rate-us dialogs
            val isRateDialog = (screen.contains("rate") && (
                screen.contains("play store") || screen.contains("stars") || screen.contains("star rating")))

            // YouTube history banners
            val isHistoryBanner = (screen.contains("watch history is off")
                || screen.contains("search history is off")
                || screen.contains("turn on watch history"))

            if (!isNotificationPopup && !isRateDialog && !isHistoryBanner) return

            // Try to tap dismiss buttons in priority order
            val dismissCandidates = listOf(
                "Not now", "Maybe later", "No thanks", "Skip",
                "Got it", "Dismiss", "Cancel", "No, thanks",
            )
            for (text in dismissCandidates) {
                val nodes = root.findAccessibilityNodeInfosByText(text) ?: continue
                val node = nodes.firstOrNull { it.isClickable } ?: continue
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return // dismissed — stop looking
            }
        } catch (_: Exception) { /* ignore — never crash in event handler */ }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun getScreenText(): String {
        val sb = StringBuilder()
        collectText(rootInActiveWindow, sb)
        return sb.toString().trim()
    }

    fun getClickableElements(): List<ScreenElement> {
        val root = rootInActiveWindow ?: return emptyList()
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val elements = mutableListOf<ScreenElement>()

        collectInteractiveNodes(root, elements, screenWidth, screenHeight)

        return elements
            .distinctBy { element ->
                listOf(
                    element.text.lowercase(),
                    element.type,
                    element.centerX / 8,
                    element.centerY / 8,
                    element.width / 8,
                    element.height / 8,
                ).joinToString(":")
            }
            .sortedWith(
                compareByDescending<ScreenElement> {
                    elementPriority(it, screenWidth, screenHeight)
                }.thenBy { it.centerY }.thenBy { it.centerX }
            )
            .take(28)
            .sortedWith(compareBy({ it.centerY }, { it.centerX }))
    }

    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val query = text.trim()
        if (query.isBlank()) return null
        val nodes = rootInActiveWindow?.findAccessibilityNodeInfosByText(query) ?: return null
        return nodes.maxByOrNull { scoreTextMatch(it, query, requireClickable = false) }
    }

    fun findNodeById(viewId: String): AccessibilityNodeInfo? {
        return rootInActiveWindow
            ?.findAccessibilityNodeInfosByViewId(viewId)
            ?.firstOrNull()
    }

    fun getCurrentPackage(): String? {
        return rootInActiveWindow?.packageName?.toString()
    }

    fun findClickableByText(text: String): AccessibilityNodeInfo? {
        val query = text.trim()
        if (query.isBlank()) return null
        val nodes = rootInActiveWindow?.findAccessibilityNodeInfosByText(query) ?: return null

        return nodes
            .mapNotNull { candidate ->
                findClickableAncestor(candidate)?.let { clickable ->
                    clickable to scoreTextMatch(clickable, query, requireClickable = true)
                }
            }
            .maxByOrNull { it.second }
            ?.first
            ?: nodes.maxByOrNull { scoreTextMatch(it, query, requireClickable = false) }
    }

    fun findAllByText(text: String): List<AccessibilityNodeInfo> {
        return rootInActiveWindow?.findAccessibilityNodeInfosByText(text) ?: emptyList()
    }

    fun findByContentDescription(desc: String): AccessibilityNodeInfo? {
        return findNodeWithDescription(rootInActiveWindow, desc)
    }

    fun findFocusedInput(): AccessibilityNodeInfo? {
        return rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }

    fun findBestTextInput(preferredKeywords: List<String> = emptyList()): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val screenWidth = resources.displayMetrics.widthPixels
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, nodes)

        return nodes
            .map { node -> node to scoreTextInput(node, preferredKeywords, screenWidth) }
            .filter { it.second > Int.MIN_VALUE / 4 }
            .maxByOrNull { it.second }
            ?.first
    }

    fun findBestActionButton(preferredKeywords: List<String>): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val screenWidth = resources.displayMetrics.widthPixels
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, nodes)

        return nodes
            .mapNotNull { node ->
                val clickable = findClickableAncestor(node) ?: return@mapNotNull null
                clickable to scoreActionButton(clickable, preferredKeywords, screenWidth)
            }
            .filter { it.second > Int.MIN_VALUE / 4 }
            .maxByOrNull { it.second }
            ?.first
    }

    suspend fun tapAtPoint(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return dispatchGestureAndWait(gesture)
    }

    suspend fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 300,
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGestureAndWait(gesture)
    }

    fun pressEnterOnFocused(): Boolean {
        val focused = findFocusedInput() ?: return false
        if (Build.VERSION.SDK_INT >= 30) {
            val ok = focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            if (ok) return true
        }

        val args = Bundle().apply {
            putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE,
            )
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, args)
    }

    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)

    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)

    fun openRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun openNotifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun getNodeCenter(node: AccessibilityNodeInfo): Pair<Float, Float> {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return Pair(rect.exactCenterX(), rect.exactCenterY())
    }

    suspend fun longPress(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 800))
            .build()
        return dispatchGestureAndWait(gesture)
    }

    suspend fun swipeLeft(): Boolean {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        return swipe(w * 0.8f, h * 0.5f, w * 0.2f, h * 0.5f, 250)
    }

    suspend fun swipeRight(): Boolean {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        return swipe(w * 0.2f, h * 0.5f, w * 0.8f, h * 0.5f, 250)
    }

    suspend fun captureScreenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        val deferred = CompletableDeferred<Bitmap?>()
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hardwareBuffer = result.hardwareBuffer
                    val bitmap = if (hardwareBuffer != null) {
                        Bitmap.wrapHardwareBuffer(hardwareBuffer, result.colorSpace)
                    } else {
                        null
                    }
                    deferred.complete(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    deferred.complete(null)
                }
            },
        )
        return deferred.await()
    }

    private fun collectInteractiveNodes(
        node: AccessibilityNodeInfo,
        list: MutableList<ScreenElement>,
        screenW: Int,
        screenH: Int,
    ) {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        if (!isVisibleCandidate(node, rect, screenW, screenH)) {
            recurseChildren(node, list, screenW, screenH)
            return
        }

        val isClickable = node.isClickable
        val isEditable = node.isEditable
        val isCheckable = node.isCheckable
        val actionable = isClickable || isEditable || isCheckable

        if (actionable) {
            val label = buildElementLabel(node)
            if (label.isNotBlank() || isEditable) {
                val className = node.className?.toString()?.substringAfterLast('.') ?: ""
                val hint = node.hintText?.toString()?.normalizeUiText().orEmpty()
                val description = node.contentDescription?.toString()?.normalizeUiText().orEmpty()
                val type = when {
                    isEditable || className.contains("EditText", ignoreCase = true) -> "input"
                    isCheckable -> "check"
                    className.contains("Button", ignoreCase = true) -> "button"
                    className.contains("Image", ignoreCase = true) -> "image"
                    else -> "clickable"
                }

                list.add(
                    ScreenElement(
                        text = label.take(90),
                        type = type,
                        isClickable = isClickable,
                        isEditable = isEditable,
                        centerX = rect.centerX(),
                        centerY = rect.centerY(),
                        width = rect.width(),
                        height = rect.height(),
                        viewId = node.viewIdResourceName.orEmpty(),
                        packageName = node.packageName?.toString().orEmpty(),
                        className = className,
                        hint = hint.take(60),
                        contentDescription = description.take(60),
                        isCheckable = isCheckable,
                        isScrollable = node.isScrollable,
                        isSelected = node.isSelected,
                    ),
                )
            }
        }

        recurseChildren(node, list, screenW, screenH)
    }

    private fun recurseChildren(
        node: AccessibilityNodeInfo,
        list: MutableList<ScreenElement>,
        screenW: Int,
        screenH: Int,
    ) {
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectInteractiveNodes(it, list, screenW, screenH) }
        }
    }

    private fun collectNodes(node: AccessibilityNodeInfo?, result: MutableList<AccessibilityNodeInfo>) {
        node ?: return
        result.add(node)
        for (i in 0 until node.childCount) {
            collectNodes(node.getChild(i), result)
        }
    }

    private fun buildElementLabel(node: AccessibilityNodeInfo): String {
        val direct = listOf(
            node.text?.toString(),
            node.contentDescription?.toString(),
            node.hintText?.toString(),
        )
            .firstOrNull { !it.isNullOrBlank() }
            ?.normalizeUiText()

        if (!direct.isNullOrBlank()) return direct

        val childText = collectNodeText(node).normalizeUiText()
        if (childText.isNotBlank()) return childText

        val viewId = node.viewIdResourceName
            ?.substringAfterLast('/')
            ?.replace('_', ' ')
            ?.replace('-', ' ')
            ?.normalizeUiText()
        if (!viewId.isNullOrBlank()) return viewId

        val className = node.className?.toString()?.substringAfterLast('.') ?: ""
        return when {
            node.isEditable -> "input field"
            className.isNotBlank() -> className
            else -> ""
        }
    }

    private fun collectNodeText(node: AccessibilityNodeInfo): String {
        val pieces = mutableListOf<String>()
        node.text?.toString()?.let { pieces += it }
        node.contentDescription?.toString()?.let { pieces += it }
        node.hintText?.toString()?.let { pieces += it }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childText = collectNodeText(child)
            if (childText.isNotBlank()) pieces += childText
        }
        return pieces.joinToString(" ").normalizeUiText()
    }

    private fun findNodeWithDescription(node: AccessibilityNodeInfo?, desc: String): AccessibilityNodeInfo? {
        node ?: return null
        val target = desc.trim()
        if (target.isBlank()) return null
        val nodeDesc = node.contentDescription?.toString().orEmpty()
        if (nodeDesc.contains(target, ignoreCase = true)) return node
        for (i in 0 until node.childCount) {
            val result = findNodeWithDescription(node.getChild(i), target)
            if (result != null) return result
        }
        return null
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 8) {
            if (parent.isClickable) return parent
            parent = parent.parent
            depth++
        }
        return null
    }

    private fun scoreTextMatch(
        node: AccessibilityNodeInfo,
        query: String,
        requireClickable: Boolean,
    ): Int {
        if (requireClickable && findClickableAncestor(node) == null) return Int.MIN_VALUE

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val label = buildElementLabel(node).lowercase()
        val normalizedQuery = query.lowercase()

        var score = 0
        if (label == normalizedQuery) score += 140
        if (label.contains(normalizedQuery)) score += 90
        if (node.isClickable) score += 30
        if (node.isEditable) score += 40
        if (rect.width() > resources.displayMetrics.widthPixels * 0.35f) score += 20
        if (label.contains("mic") || label.contains("voice")) score -= 80
        if (rect.width() < 60 && rect.height() < 60) score -= 20
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 && !node.isVisibleToUser) score -= 40
        return score
    }

    private fun scoreTextInput(
        node: AccessibilityNodeInfo,
        preferredKeywords: List<String>,
        screenWidth: Int,
    ): Int {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!isVisibleCandidate(node, rect, screenWidth, resources.displayMetrics.heightPixels)) {
            return Int.MIN_VALUE
        }

        val combined = listOf(
            buildElementLabel(node),
            node.viewIdResourceName.orEmpty(),
            node.className?.toString().orEmpty(),
        ).joinToString(" ").lowercase()

        val looksLikeInput = node.isEditable ||
            node.className?.toString()?.contains("EditText", ignoreCase = true) == true ||
            node.className?.toString()?.contains("TextInput", ignoreCase = true) == true ||
            combined.contains("search") ||
            combined.contains("message") ||
            combined.contains("reply") ||
            combined.contains("type a message") ||
            combined.contains("compose") ||
            combined.contains("to") ||
            combined.contains("contact") ||
            combined.contains("name")

        if (!looksLikeInput) return Int.MIN_VALUE

        var score = 0
        if (node.isEditable) score += 180
        if (node == findFocusedInput()) score += 150
        if (combined.contains("search")) score += 130
        if (combined.contains("message") || combined.contains("reply")) score += 120
        if (combined.contains("contact") || combined.contains("name") || combined.contains("to")) score += 80
        if (rect.width() > screenWidth * 0.45f) score += 80
        if (rect.width() > screenWidth * 0.30f) score += 30
        if (rect.height() > 60) score += 10
        if (node.isClickable) score += 15

        preferredKeywords.forEach { keyword ->
            val normalized = keyword.trim().lowercase()
            if (normalized.isNotBlank() && combined.contains(normalized)) {
                score += 70
            }
        }

        if (combined.contains("mic") || combined.contains("voice") || combined.contains("camera")) score -= 220
        if (!node.isEditable && rect.width() < 120) score -= 120
        if (node.className?.toString()?.contains("Image", ignoreCase = true) == true) score -= 80

        return score
    }

    private fun scoreActionButton(
        node: AccessibilityNodeInfo,
        preferredKeywords: List<String>,
        screenWidth: Int,
    ): Int {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!isVisibleCandidate(node, rect, screenWidth, resources.displayMetrics.heightPixels)) {
            return Int.MIN_VALUE
        }

        val label = buildElementLabel(node).lowercase()
        if (label.isBlank()) return Int.MIN_VALUE

        var score = 0
        preferredKeywords.forEach { keyword ->
            val normalized = keyword.trim().lowercase()
            if (normalized.isNotBlank() && label.contains(normalized)) {
                score += 110
            }
        }
        if (node.className?.toString()?.contains("Button", ignoreCase = true) == true) score += 25
        if (node.isClickable) score += 20
        if (rect.width() < screenWidth * 0.45f) score += 10
        if (label.contains("mic") || label.contains("voice") || label.contains("camera")) score -= 160
        if (score == 0) return Int.MIN_VALUE
        return score
    }

    private fun elementPriority(element: ScreenElement, screenW: Int, screenH: Int): Int {
        val label = listOf(
            element.text,
            element.hint,
            element.contentDescription,
            element.viewId,
        ).joinToString(" ").lowercase()

        var score = 0
        if (element.isEditable) score += 160
        if (label.contains("search")) score += 140
        if (label.contains("message") || label.contains("reply")) score += 120
        if (label.contains("send")) score += 70
        if (element.width > screenW * 0.45f) score += 70
        if (element.centerY < screenH * 0.25f) score += 15
        if (element.height > 56) score += 10
        // CRITICAL: Voice/audio buttons blocked with extreme penalty to override ALL other bonuses
        if (label.contains("mic") || label.contains("voice") || label.contains("camera") || 
            label.contains("audio") || label.contains("speak") || label.contains("record") ||
            label.contains("listening") || label.contains("microphone")) score -= 300
        if (element.width < 60 && element.height < 60) score -= 20
        return score
    }

    private fun isVisibleCandidate(
        node: AccessibilityNodeInfo,
        rect: Rect,
        screenW: Int,
        screenH: Int,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 && !node.isVisibleToUser) {
            return false
        }
        if (rect.width() < 20 || rect.height() < 20) return false
        if (rect.right < 0 || rect.bottom < 0 || rect.left > screenW || rect.top > screenH) return false
        return true
    }

    private suspend fun dispatchGestureAndWait(gesture: GestureDescription): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val started = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    deferred.complete(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    deferred.complete(false)
                }
            },
            null,
        )
        return if (started) deferred.await() else false
    }

    private fun collectText(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        node ?: return

        val text = node.text?.toString()?.normalizeUiText()
        val desc = node.contentDescription?.toString()?.normalizeUiText()
        val hint = node.hintText?.toString()?.normalizeUiText()

        when {
            !text.isNullOrBlank() -> sb.appendLine(text)
            !desc.isNullOrBlank() -> sb.appendLine(desc)
            !hint.isNullOrBlank() -> sb.appendLine("[hint: $hint]")
        }

        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), sb)
        }
    }

    private fun String.normalizeUiText(): String {
        return replace(Regex("\\s+"), " ").trim()
    }
}
