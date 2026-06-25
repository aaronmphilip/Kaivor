package com.kaivor.agent

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.NotificationManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

/**
 * iOS 27 Siri-style persistent notch (Clicky-inspired).
 *
 * Idle: narrow liquid-glass pill with animated waveform — always visible while agent runs.
 * Active: expands with task label + pause/stop controls.
 * Expanded: Dynamic Island stack of primary, queued, and background activities.
 */
object NotchOverlay {

    private enum class PillState { IDLE, ACTIVE, EXPANDED }

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var root: View? = null
    private var lp: WindowManager.LayoutParams? = null

    private var slideAnim: ValueAnimator? = null
    private var glowAnim: ObjectAnimator? = null
    private var widthAnim: ValueAnimator? = null

    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartParamX = 0
    private var dragStartParamY = 0
    private var dragging = false

    private var state = PillState.IDLE
    private var paused = false
    private var attached = false

    private var onStop: (() -> Unit)? = null
    private var onPauseToggle: (() -> Unit)? = null

    private val hubListener: () -> Unit = { main.post { renderFromHub() } }

    fun attach(
        context: Context,
        onStop: () -> Unit = {},
        onPauseToggle: () -> Unit = {},
    ) {
        if (!isEnabled(context)) return
        this.onStop = onStop
        this.onPauseToggle = onPauseToggle
        NotchActivityHub.onChanged(hubListener)

        if (!hasPermission(context)) {
            updateServiceNotification(context, "Kaivor listening")
            return
        }

        main.post {
            if (root != null) {
                renderFromHub()
                return@post
            }
            attached = true
            state = PillState.IDLE
            paused = false

            val appCtx = context.applicationContext
            val manager = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm = manager
            val targetY = getNotchTargetY(appCtx)

            val view = LayoutInflater.from(appCtx).inflate(R.layout.overlay_notch, null)
            root = view

            val btnPause = view.findViewById<ImageButton>(R.id.btnNotchPause)
            val btnStop = view.findViewById<ImageButton>(R.id.btnNotchStop)
            val glow = view.findViewById<View>(R.id.notchGlow)
            val waveform = view.findViewById<SiriWaveformView>(R.id.siriWaveform)

            protectButtonTouches(btnPause, btnStop)

            btnPause.setOnClickListener {
                onPauseToggle()
            }
            btnStop.setOnClickListener {
                updateText("Stopping...")
                onStop()
            }

            val toggleExpand = View.OnClickListener {
                if (state == PillState.EXPANDED) setState(PillState.ACTIVE)
                else setState(PillState.EXPANDED)
            }
            view.findViewById<SiriWaveformView>(R.id.siriWaveform)?.setOnClickListener(toggleExpand)
            view.findViewById<TextView>(R.id.tvNotchTask)?.setOnClickListener(toggleExpand)
            view.findViewById<TextView>(R.id.tvNotchLabel)?.setOnClickListener(toggleExpand)
            view.findViewById<TextView>(R.id.tvActivityCount)?.setOnClickListener(toggleExpand)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = -200
            }
            lp = params

            setupDrag(waveform, manager, params)
            applyIdleLayout(view, animate = false)
            waveform.setActive(true)
            runCatching { manager.addView(view, params) }

            slideAnim?.cancel()
            slideAnim = ValueAnimator.ofInt(-200, targetY).apply {
                duration = 520
                interpolator = OvershootInterpolator(0.55f)
                addUpdateListener { anim ->
                    params.y = anim.animatedValue as Int
                    runCatching { manager.updateViewLayout(view, params) }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        startGlow(glow)
                    }
                })
                start()
            }
            renderFromHub()
        }
    }

    fun detach() {
        NotchActivityHub.offChanged(hubListener)
        main.post { dismissImmediate() }
    }

    fun transitionToIdle(context: Context? = null) {
        context?.let { updateServiceNotification(it, "Listening for Telegram commands...") }
        main.post {
            if (root == null) return@post
            paused = false
            setState(PillState.IDLE)
            renderFromHub()
        }
    }

    /** @deprecated Use [beginPrimaryTask] after [attach]. Kept for gradual migration. */
    fun show(
        context: Context,
        taskText: String,
        onStop: () -> Unit,
        onPauseToggle: () -> Unit,
    ) {
        this.onStop = onStop
        this.onPauseToggle = onPauseToggle
        if (root == null) attach(context, onStop, onPauseToggle)
        beginPrimaryTask(context, taskText)
    }

    fun beginPrimaryTask(context: Context, taskText: String) {
        NotchActivityHub.startPrimary("phone", taskText.take(64))
        updateServiceNotification(context, taskText.take(80))
        main.post {
            if (root == null) return@post
            paused = false
            setState(PillState.ACTIVE)
            root?.findViewById<TextView>(R.id.tvNotchTask)?.text = taskText.take(52)
            renderFromHub()
        }
    }

    fun updateText(text: String, context: Context? = null) {
        context?.let { updateServiceNotification(it, text.take(80)) }
        val clean = text.trim().ifBlank { "Working..." }
        NotchActivityHub.updatePrimary(clean, clean)
        main.post {
            root?.findViewById<TextView>(R.id.tvNotchTask)?.text = clean.take(52)
            root?.findViewById<TextView>(R.id.tvNotchStage)?.text = clean.take(120)
        }
    }

    fun updateMeta(stage: String, skill: String? = null) {
        val subtitle = if (!skill.isNullOrBlank()) "Skill · ${skill.take(40)}" else stage
        NotchActivityHub.updatePrimary(stage.take(64), subtitle)
        main.post {
            root?.findViewById<TextView>(R.id.tvNotchStage)?.text = stage.take(120)
            val skillView = root?.findViewById<TextView>(R.id.tvNotchSkill)
            if (!skill.isNullOrBlank()) {
                skillView?.text = "Skill · ${skill.take(40)}"
                skillView?.visibility = View.VISIBLE
            }
        }
    }

    fun setQueue(tasks: List<String>) {
        NotchActivityHub.syncQueue(tasks)
    }

    fun addQueued(text: String) {
        val existing = NotchActivityHub.snapshot()
            .filter { it.kind == NotchActivityKind.QUEUED }
            .map { it.title }
        NotchActivityHub.syncQueue((existing + text.take(60)).takeLast(5))
    }

    fun setPaused(isPaused: Boolean) {
        paused = isPaused
        NotchActivityHub.setPrimaryPaused(isPaused)
        main.post {
            val btnPause = root?.findViewById<ImageButton>(R.id.btnNotchPause)
            val label = root?.findViewById<TextView>(R.id.tvNotchLabel)
            val waveform = root?.findViewById<SiriWaveformView>(R.id.siriWaveform)
            btnPause?.setImageResource(if (paused) R.drawable.ic_notch_play else R.drawable.ic_notch_pause)
            label?.text = if (paused) "PAUSED" else "KAIVOR"
            waveform?.setActive(!paused)
            if (paused) {
                root?.findViewById<TextView>(R.id.tvNotchTask)?.text = "Tap play to resume"
                glowAnim?.cancel()
            } else {
                root?.findViewById<View>(R.id.notchGlow)?.let { startGlow(it) }
            }
        }
    }

    /** @deprecated Use [transitionToIdle]. Overlay stays visible while agent runs. */
    fun hide(context: Context? = null) = transitionToIdle(context)

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("kaivor", Context.MODE_PRIVATE)
            .getBoolean("notch_overlay_enabled", true)

    fun hasPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

    private fun setState(newState: PillState) {
        if (state == newState && root != null) return
        state = newState
        val view = root ?: return
        when (newState) {
            PillState.IDLE -> applyIdleLayout(view, animate = true)
            PillState.ACTIVE -> applyActiveLayout(view, animate = true)
            PillState.EXPANDED -> applyExpandedLayout(view)
        }
    }

    private fun applyIdleLayout(view: View, animate: Boolean) {
        val textBlock = view.findViewById<LinearLayout>(R.id.notchTextBlock)
        val controls = view.findViewById<LinearLayout>(R.id.notchControls)
        val expanded = view.findViewById<LinearLayout>(R.id.notchExpanded)
        val count = view.findViewById<TextView>(R.id.tvActivityCount)
        val waveform = view.findViewById<SiriWaveformView>(R.id.siriWaveform)
        val pill = view.findViewById<LinearLayout>(R.id.notchPill)

        textBlock?.visibility = View.GONE
        controls?.visibility = View.GONE
        expanded?.visibility = View.GONE
        expanded?.alpha = 0f
        waveform?.setActive(true)
        morphPill(pill, dp(view, 126), animate)

        val extras = NotchActivityHub.snapshot().count {
            it.kind != NotchActivityKind.PRIMARY
        }
        if (extras > 0) {
            count?.text = extras.toString()
            count?.visibility = View.VISIBLE
        } else {
            count?.visibility = View.GONE
        }
    }

    private fun applyActiveLayout(view: View, animate: Boolean) {
        val textBlock = view.findViewById<LinearLayout>(R.id.notchTextBlock)
        val controls = view.findViewById<LinearLayout>(R.id.notchControls)
        val expanded = view.findViewById<LinearLayout>(R.id.notchExpanded)
        val count = view.findViewById<TextView>(R.id.tvActivityCount)
        val waveform = view.findViewById<SiriWaveformView>(R.id.siriWaveform)
        val pill = view.findViewById<LinearLayout>(R.id.notchPill)

        textBlock?.visibility = View.VISIBLE
        controls?.visibility = View.VISIBLE
        expanded?.visibility = View.GONE
        expanded?.alpha = 0f
        waveform?.setActive(!paused)
        morphPill(pill, dp(view, 300), animate)

        val extras = NotchActivityHub.snapshot().count {
            it.kind != NotchActivityKind.PRIMARY
        }
        if (extras > 0) {
            count?.text = extras.toString()
            count?.visibility = View.VISIBLE
        } else {
            count?.visibility = View.GONE
        }
    }

    private fun applyExpandedLayout(view: View) {
        applyActiveLayout(view, animate = false)
        val expanded = view.findViewById<LinearLayout>(R.id.notchExpanded)
        val pill = view.findViewById<LinearLayout>(R.id.notchPill)
        expanded?.visibility = View.VISIBLE
        expanded?.alpha = 0f
        expanded?.animate()?.alpha(1f)?.setDuration(240)?.setInterpolator(DecelerateInterpolator())?.start()
        morphPill(pill, dp(view, 320), animate = true)
        renderActivityList()
    }

    private fun renderFromHub() {
        val view = root ?: return
        val primary = NotchActivityHub.primary()

        if (primary != null) {
            if (state == PillState.IDLE) setState(PillState.ACTIVE)
            view.findViewById<TextView>(R.id.tvNotchTask)?.text = primary.title.take(52)
            view.findViewById<TextView>(R.id.tvNotchStage)?.text = primary.title.take(120)
            val skillView = view.findViewById<TextView>(R.id.tvNotchSkill)
            if (primary.subtitle.isNotBlank()) {
                skillView?.text = primary.subtitle
                skillView?.visibility = View.VISIBLE
            }
            paused = primary.state == NotchActivityState.PAUSED
            view.findViewById<ImageButton>(R.id.btnNotchPause)?.setImageResource(
                if (paused) R.drawable.ic_notch_play else R.drawable.ic_notch_pause,
            )
            view.findViewById<SiriWaveformView>(R.id.siriWaveform)?.setActive(!paused)
        } else if (state != PillState.EXPANDED) {
            setState(PillState.IDLE)
        }

        val extras = NotchActivityHub.snapshot().count { it.kind != NotchActivityKind.PRIMARY }
        val count = view.findViewById<TextView>(R.id.tvActivityCount)
        if (extras > 0) {
            count?.text = extras.toString()
            count?.visibility = View.VISIBLE
        } else {
            count?.visibility = View.GONE
        }

        if (state == PillState.EXPANDED) renderActivityList()
    }

    private fun renderActivityList() {
        val container = root?.findViewById<LinearLayout>(R.id.notchActivityList) ?: return
        val ctx = root?.context ?: return
        val inflater = LayoutInflater.from(ctx)
        container.removeAllViews()

        val items = NotchActivityHub.snapshot()
        if (items.isEmpty()) return

        items.forEach { activity ->
            val row = inflater.inflate(R.layout.notch_activity_row, container, false)
            row.findViewById<TextView>(R.id.activityTitle).text = activity.title
            row.findViewById<TextView>(R.id.activitySubtitle).text = activity.subtitle
            val badge = row.findViewById<TextView>(R.id.activityBadge)
            val dot = row.findViewById<View>(R.id.activityDot)
            when (activity.kind) {
                NotchActivityKind.PRIMARY -> {
                    badge.text = "Now"
                    badge.setTextColor(0xFF0A84FF.toInt())
                }
                NotchActivityKind.QUEUED -> {
                    badge.text = "Queued"
                    badge.setTextColor(0xFF98989D.toInt())
                }
                NotchActivityKind.BACKGROUND -> {
                    badge.text = "Live"
                    badge.setTextColor(0xFF30D158.toInt())
                }
            }
            dot.setBackgroundResource(
                when (activity.state) {
                    NotchActivityState.PAUSED -> R.drawable.notch_dot_paused
                    NotchActivityState.IDLE -> R.drawable.notch_dot_paused
                    else -> R.drawable.notch_dot_active
                },
            )
            container.addView(row)
        }
    }

    private fun morphPill(pill: LinearLayout?, targetPx: Int, animate: Boolean) {
        if (pill == null) return
        val start = pill.layoutParams?.width?.takeIf { it > 0 } ?: pill.width
        widthAnim?.cancel()
        if (!animate || start <= 0) {
            pill.minimumWidth = targetPx
            pill.requestLayout()
            return
        }
        widthAnim = ValueAnimator.ofInt(start.coerceAtLeast(dp(pill, 126)), targetPx).apply {
            duration = 320
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                pill.minimumWidth = it.animatedValue as Int
                pill.requestLayout()
            }
            start()
        }
    }

    private fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density).toInt()

    private fun getNotchTargetY(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (id > 0) context.resources.getDimensionPixelSize(id) else 24
        return statusBarHeight + 8
    }

    private fun updateServiceNotification(context: Context, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val notif = NotificationCompat.Builder(context, AgentForegroundService.CHANNEL_ID)
            .setContentTitle("Kaivor Agent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        nm.notify(AgentForegroundService.NOTIFICATION_ID, notif)
    }

    private fun startGlow(glow: View) {
        glowAnim?.cancel()
        glowAnim = ObjectAnimator.ofFloat(glow, "alpha", 0.4f, 0.82f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    @Suppress("ClickableViewAccessibility")
    private fun protectButtonTouches(vararg buttons: ImageButton) {
        buttons.forEach { button ->
            button.isClickable = true
            button.isFocusable = true
            button.setOnTouchListener { v, event ->
                v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
    }

    @Suppress("ClickableViewAccessibility")
    private fun setupDrag(handle: View?, manager: WindowManager, params: WindowManager.LayoutParams) {
        handle ?: return
        handle.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    dragStartParamX = params.x
                    dragStartParamY = params.y
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - dragStartRawX).toInt()
                    val dy = (event.rawY - dragStartRawY).toInt()
                    if (!dragging && (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10)) {
                        dragging = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        val rootView = root ?: v
                        params.x = dragStartParamX + dx
                        params.y = dragStartParamY + dy
                        runCatching { manager.updateViewLayout(rootView, params) }
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasDrag = dragging
                    dragging = false
                    if (!wasDrag) {
                        v.performClick()
                    }
                    wasDrag
                }
                else -> false
            }
        }
    }

    private fun dismissImmediate() {
        glowAnim?.cancel(); glowAnim = null
        widthAnim?.cancel(); widthAnim = null
        slideAnim?.cancel(); slideAnim = null
        root?.let { v -> runCatching { wm?.removeView(v) } }
        root = null
        wm = null
        lp = null
        attached = false
        state = PillState.IDLE
        paused = false
        onStop = null
        onPauseToggle = null
    }
}