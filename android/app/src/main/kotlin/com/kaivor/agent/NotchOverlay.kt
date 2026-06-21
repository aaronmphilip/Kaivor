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
 * Premium Dynamic Island-style task pill (Clicky-inspired: glanceable, expandable).
 *
 * Collapsed: Siri glow + current task + pause/stop controls.
 * Expanded: stage, skill, and queued upcoming tasks.
 */
object NotchOverlay {

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var root: View? = null
    private var lp: WindowManager.LayoutParams? = null

    private var slideAnim: ValueAnimator? = null
    private var glowAnim: ObjectAnimator? = null
    private var pulseAnim: ObjectAnimator? = null

    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartParamX = 0
    private var dragStartParamY = 0
    private var dragging = false
    private var expanded = false
    private var paused = false

    private var onStop: (() -> Unit)? = null
    private var onPauseToggle: (() -> Unit)? = null

    private var currentStage = ""
    private var currentSkill = ""
    private val queuedTasks = mutableListOf<String>()

    fun show(
        context: Context,
        taskText: String,
        onStop: () -> Unit,
        onPauseToggle: () -> Unit,
    ) {
        if (!isEnabled(context)) return
        this.onStop = onStop
        this.onPauseToggle = onPauseToggle
        paused = false
        expanded = false
        currentStage = "Starting..."
        currentSkill = ""
        queuedTasks.clear()

        if (!hasPermission(context)) {
            updateServiceNotification(context, "Running: $taskText")
            return
        }

        main.post {
            dismissImmediate()
            val appCtx = context.applicationContext
            val manager = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm = manager
            val targetY = getNotchTargetY(appCtx)

            val view = LayoutInflater.from(appCtx).inflate(R.layout.overlay_notch, null)
            root = view

            val tvTask = view.findViewById<TextView>(R.id.tvNotchTask)
            val tvStage = view.findViewById<TextView>(R.id.tvNotchStage)
            val tvSkill = view.findViewById<TextView>(R.id.tvNotchSkill)
            val btnPause = view.findViewById<ImageButton>(R.id.btnNotchPause)
            val btnStop = view.findViewById<ImageButton>(R.id.btnNotchStop)
            val btnExpand = view.findViewById<ImageButton>(R.id.btnNotchExpand)
            val expandedPanel = view.findViewById<LinearLayout>(R.id.notchExpanded)
            val glow = view.findViewById<View>(R.id.notchGlow)
            val dot = view.findViewById<View>(R.id.notchDot)

            tvTask.text = taskText.take(52)
            tvTask.alpha = 0f
            tvStage.text = currentStage
            tvSkill.text = ""

            btnPause.alpha = 0f
            btnStop.alpha = 0f
            btnExpand.alpha = 0f
            expandedPanel.visibility = View.GONE
            expandedPanel.alpha = 0f

            btnPause.setOnClickListener {
                onPauseToggle?.invoke()
                setPaused(!paused)
            }
            btnStop.setOnClickListener {
                updateText("Stopping...")
                onStop?.invoke()
            }
            btnExpand.setOnClickListener { toggleExpanded(view) }

            view.findViewById<LinearLayout>(R.id.notchCollapsed)?.setOnClickListener {
                toggleExpanded(view)
            }

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

            setupDrag(view, manager, params)
            runCatching { manager.addView(view, params) }

            slideAnim?.cancel()
            slideAnim = ValueAnimator.ofInt(-200, targetY).apply {
                duration = 480
                interpolator = OvershootInterpolator(0.65f)
                addUpdateListener { anim ->
                    params.y = anim.animatedValue as Int
                    runCatching { manager.updateViewLayout(view, params) }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        tvTask.animate().alpha(1f).setDuration(220).start()
                        btnPause.animate().alpha(1f).setDuration(220).start()
                        btnStop.animate().alpha(1f).setDuration(220).start()
                        btnExpand.animate().alpha(1f).setDuration(220).start()
                        startGlow(glow)
                        startDotPulse(dot)
                    }
                })
                start()
            }
        }
    }

    fun updateText(text: String, context: Context? = null) {
        context?.let { updateServiceNotification(it, text.take(80)) }
        main.post {
            root?.findViewById<TextView>(R.id.tvNotchTask)?.text = text.take(52)
            currentStage = text.take(120)
            root?.findViewById<TextView>(R.id.tvNotchStage)?.text = currentStage
        }
    }

    fun updateMeta(stage: String, skill: String? = null) {
        currentStage = stage
        if (!skill.isNullOrBlank()) currentSkill = skill
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
        queuedTasks.clear()
        queuedTasks.addAll(tasks.take(4))
        main.post { renderQueue() }
    }

    fun addQueued(text: String) {
        val trimmed = text.take(60)
        if (trimmed.isBlank() || queuedTasks.contains(trimmed)) return
        queuedTasks.add(trimmed)
        if (queuedTasks.size > 4) queuedTasks.removeAt(0)
        main.post { renderQueue() }
    }

    fun setPaused(isPaused: Boolean) {
        paused = isPaused
        main.post {
            val btnPause = root?.findViewById<ImageButton>(R.id.btnNotchPause)
            val dot = root?.findViewById<View>(R.id.notchDot)
            val label = root?.findViewById<TextView>(R.id.tvNotchLabel)
            btnPause?.setImageResource(if (paused) R.drawable.ic_notch_play else R.drawable.ic_notch_pause)
            dot?.setBackgroundResource(if (paused) R.drawable.notch_dot_paused else R.drawable.notch_dot_active)
            label?.text = if (paused) "PAUSED" else "KAIVOR"
            if (paused) {
                root?.findViewById<TextView>(R.id.tvNotchTask)?.text = "Tap play to resume"
                glowAnim?.cancel()
            } else {
                glowAnim?.start()
            }
        }
    }

    fun hide(context: Context? = null) {
        context?.let { updateServiceNotification(it, "Listening for Telegram commands...") }
        main.post {
            val view = root ?: return@post
            val manager = wm ?: return@post
            val params = lp ?: return@post

            glowAnim?.cancel()
            pulseAnim?.cancel()

            view.findViewById<TextView>(R.id.tvNotchTask)?.animate()?.alpha(0f)?.setDuration(100)?.start()
            view.findViewById<ImageButton>(R.id.btnNotchPause)?.animate()?.alpha(0f)?.setDuration(100)?.start()
            view.findViewById<ImageButton>(R.id.btnNotchStop)?.animate()?.alpha(0f)?.setDuration(100)?.start()
            view.findViewById<LinearLayout>(R.id.notchExpanded)?.animate()?.alpha(0f)?.setDuration(100)?.start()

            slideAnim?.cancel()
            slideAnim = ValueAnimator.ofInt(params.y, -320).apply {
                duration = 300
                startDelay = 60
                interpolator = AccelerateInterpolator(1.5f)
                addUpdateListener { anim ->
                    params.y = anim.animatedValue as Int
                    runCatching { manager.updateViewLayout(view, params) }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        dismissImmediate()
                    }
                })
                start()
            }
        }
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("kaivor", Context.MODE_PRIVATE)
            .getBoolean("notch_overlay_enabled", true)

    fun hasPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

    private fun toggleExpanded(view: View) {
        expanded = !expanded
        val panel = view.findViewById<LinearLayout>(R.id.notchExpanded)
        val chevron = view.findViewById<ImageButton>(R.id.btnNotchExpand)
        if (expanded) {
            renderQueue()
            panel.visibility = View.VISIBLE
            panel.alpha = 0f
            panel.animate().alpha(1f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
            chevron.rotation = 180f
        } else {
            panel.animate().alpha(0f).setDuration(180).withEndAction {
                panel.visibility = View.GONE
            }.start()
            chevron.rotation = 0f
        }
    }

    private fun renderQueue() {
        val container = root?.findViewById<LinearLayout>(R.id.notchQueueList) ?: return
        val title = root?.findViewById<TextView>(R.id.tvNotchQueueTitle) ?: return
        container.removeAllViews()
        if (queuedTasks.isEmpty()) {
            title.visibility = View.GONE
            return
        }
        title.visibility = View.VISIBLE
        val ctx = root?.context ?: return
        queuedTasks.forEachIndexed { index, task ->
            val row = TextView(ctx).apply {
                text = "${index + 1}. ${task.take(56)}"
                setTextColor(0xFF98989D.toInt())
                textSize = 11f
                setPadding(0, 4, 0, 4)
            }
            container.addView(row)
        }
    }

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
        glowAnim = ObjectAnimator.ofFloat(glow, "alpha", 0.45f, 0.85f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun startDotPulse(dot: View) {
        pulseAnim?.cancel()
        pulseAnim = ObjectAnimator.ofFloat(dot, "scaleX", 1f, 1.18f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                dot.scaleY = dot.scaleX
            }
            start()
        }
    }

    @Suppress("ClickableViewAccessibility")
    private fun setupDrag(view: View, manager: WindowManager, params: WindowManager.LayoutParams) {
        view.setOnTouchListener { v, event ->
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
                    if (!dragging && (kotlin.math.abs(dx) > 14 || kotlin.math.abs(dy) > 14)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = dragStartParamX + dx
                        params.y = dragStartParamY + dy
                        runCatching { manager.updateViewLayout(v, params) }
                    }
                    dragging
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val was = dragging
                    dragging = false
                    was
                }
                else -> false
            }
        }
    }

    private fun dismissImmediate() {
        glowAnim?.cancel(); glowAnim = null
        pulseAnim?.cancel(); pulseAnim = null
        slideAnim?.cancel(); slideAnim = null
        root?.let { v -> runCatching { wm?.removeView(v) } }
        root = null
        wm = null
        lp = null
        onStop = null
        onPauseToggle = null
        expanded = false
        paused = false
    }
}