package com.bharatdroid.agent

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
import android.widget.TextView
import androidx.core.app.NotificationCompat

/**
 * NotchOverlay — premium black floating pill with spring animations.
 *
 * States:
 *   idle    → nothing visible
 *   show    → slides in from top, text fades in, dot pulses
 *   hide    → text fades, pill slides out to top
 *
 * Usage:
 *   NotchOverlay.show(context, "Booking Uber...") { cancelFn() }
 *   NotchOverlay.updateText("Picking your ride…")
 *   NotchOverlay.hide()
 */
object NotchOverlay {

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var root: View? = null
    private var lp: WindowManager.LayoutParams? = null

    private var slideAnim: ValueAnimator? = null
    private var pulseAnim: ObjectAnimator? = null

    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartParamX = 0
    private var dragStartParamY = 0
    private var dragging = false

    // ── Public ────────────────────────────────────────────────────────────────

    fun show(context: Context, taskText: String, onCancel: () -> Unit) {
        if (!isEnabled(context)) return
        if (!hasPermission(context)) {
            // Fallback: update the foreground service notification to show the running task
            updateServiceNotification(context, "⚡ $taskText")
            return
        }
        main.post {
            dismissImmediate()                           // clear any stale overlay
            val appCtx = context.applicationContext
            val manager = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm = manager

            val view = LayoutInflater.from(appCtx).inflate(R.layout.overlay_notch, null)
            root = view

            val tvTask   = view.findViewById<TextView>(R.id.tvNotchTask)
            val btnStop  = view.findViewById<ImageButton>(R.id.btnNotchCancel)
            val dot      = view.findViewById<View>(R.id.notchDot)

            tvTask.text  = taskText.take(46)
            tvTask.alpha = 0f
            btnStop.alpha = 0f
            btnStop.setOnClickListener {
                updateText("Stopping…")
                onCancel()
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
                y = -200     // start off-screen above status bar
            }
            lp = params

            setupDrag(view, manager, params)
            runCatching { manager.addView(view, params) }

            // ── SLIDE IN ─────────────────────────────────────────────────────
            slideAnim?.cancel()
            slideAnim = ValueAnimator.ofInt(-200, 72).apply {
                duration = 420
                interpolator = OvershootInterpolator(0.8f)
                addUpdateListener { anim ->
                    params.y = anim.animatedValue as Int
                    runCatching { manager.updateViewLayout(view, params) }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        // Fade in text + cancel after pill lands
                        tvTask.animate().alpha(1f).setDuration(200).start()
                        btnStop.animate().alpha(1f).setDuration(200).start()
                        startPulse(dot)
                    }
                })
                start()
            }
        }
    }

    fun updateText(text: String, context: Context? = null) {
        context?.let { updateServiceNotification(it, text.take(80)) }
        main.post {
            root?.findViewById<TextView>(R.id.tvNotchTask)?.text = text.take(46)
        }
    }

    fun hide(context: Context? = null) {
        // Restore the foreground notification to idle text when task finishes
        context?.let { updateServiceNotification(it, "Listening for your Telegram commands…") }
        main.post {
            val view = root ?: return@post
            val manager = wm ?: return@post
            val params = lp ?: return@post

            pulseAnim?.cancel()

            // Fade text out first
            view.findViewById<TextView>(R.id.tvNotchTask)
                ?.animate()?.alpha(0f)?.setDuration(100)?.start()
            view.findViewById<ImageButton>(R.id.btnNotchCancel)
                ?.animate()?.alpha(0f)?.setDuration(100)?.start()

            // ── SLIDE OUT ────────────────────────────────────────────────────
            slideAnim?.cancel()
            slideAnim = ValueAnimator.ofInt(params.y, -300).apply {
                duration = 280
                startDelay = 80          // let text fade first
                interpolator = AccelerateInterpolator(1.4f)
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
        context.getSharedPreferences("bharatdroid", Context.MODE_PRIVATE)
            .getBoolean("notch_overlay_enabled", true)

    fun hasPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

    private fun updateServiceNotification(context: Context, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val notif = NotificationCompat.Builder(context, AgentForegroundService.CHANNEL_ID)
            .setContentTitle("BharatDroid Agent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        nm.notify(AgentForegroundService.NOTIFICATION_ID, notif)
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun startPulse(dot: View) {
        pulseAnim?.cancel()
        pulseAnim = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.2f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode  = ValueAnimator.REVERSE
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    @Suppress("ClickableViewAccessibility")
    private fun setupDrag(view: View, manager: WindowManager, params: WindowManager.LayoutParams) {
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawX  = event.rawX
                    dragStartRawY  = event.rawY
                    dragStartParamX = params.x
                    dragStartParamY = params.y
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - dragStartRawX).toInt()
                    val dy = (event.rawY - dragStartRawY).toInt()
                    if (!dragging && (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = dragStartParamX + dx
                        params.y = dragStartParamY + dy
                        runCatching { manager.updateViewLayout(view, params) }
                    }
                    dragging
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val was = dragging; dragging = false; was
                }
                else -> false
            }
        }
    }

    private fun dismissImmediate() {
        pulseAnim?.cancel(); pulseAnim = null
        slideAnim?.cancel(); slideAnim = null
        root?.let { v -> runCatching { wm?.removeView(v) } }
        root = null; wm = null; lp = null
    }
}
