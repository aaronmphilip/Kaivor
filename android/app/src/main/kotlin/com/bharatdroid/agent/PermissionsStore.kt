package com.bharatdroid.agent

import android.content.Context

/**
 * PermissionsStore — controls what the agent asks the user to confirm before doing.
 *
 * Three modes:
 *   ASK    — ask before every sensitive action (old "Ask Permission" behaviour)
 *   SMART  — ask only for payment/booking/ordering/purchase/messaging (DEFAULT)
 *   AUTO   — never ask, just do everything (old "Just Do It" behaviour)
 *
 * Per-category toggles (only active in SMART mode):
 *   payment   — GPay, PhonePe, Paytm sends
 *   booking   — Ola, Uber cab booking
 *   ordering  — Swiggy, Zomato, Blinkit, Zepto food/grocery orders
 *   purchase  — Amazon, Flipkart add-to-cart
 *   messaging — WhatsApp, Instagram DM sends
 *
 * Usage via Telegram:
 *   /permissions           — show status
 *   /permissions smart     — switch to SMART mode (recommended)
 *   /permissions ask       — switch to ASK mode (ask for everything)
 *   /permissions auto      — switch to AUTO mode (never ask)
 *   /permissions payment off — disable payment confirmations
 *   /permissions booking on  — enable booking confirmations
 */
class PermissionsStore(context: Context) {

    private val prefs = context.getSharedPreferences("bharatdroid_permissions", Context.MODE_PRIVATE)

    enum class Mode { ASK, SMART, AUTO }

    companion object {
        private const val KEY_MODE = "mode"
        private const val KEY_PAYMENT = "confirm_payment"
        private const val KEY_BOOKING = "confirm_booking"
        private const val KEY_ORDERING = "confirm_ordering"
        private const val KEY_PURCHASE = "confirm_purchase"
        private const val KEY_MESSAGING = "confirm_messaging"
    }

    var mode: Mode
        get() = try { Mode.valueOf(prefs.getString(KEY_MODE, Mode.SMART.name) ?: Mode.SMART.name) } catch (_: Exception) { Mode.SMART }
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    var confirmPayment: Boolean
        get() = prefs.getBoolean(KEY_PAYMENT, true)
        set(v) = prefs.edit().putBoolean(KEY_PAYMENT, v).apply()

    var confirmBooking: Boolean
        get() = prefs.getBoolean(KEY_BOOKING, true)
        set(v) = prefs.edit().putBoolean(KEY_BOOKING, v).apply()

    var confirmOrdering: Boolean
        get() = prefs.getBoolean(KEY_ORDERING, true)
        set(v) = prefs.edit().putBoolean(KEY_ORDERING, v).apply()

    var confirmPurchase: Boolean
        get() = prefs.getBoolean(KEY_PURCHASE, true)
        set(v) = prefs.edit().putBoolean(KEY_PURCHASE, v).apply()

    var confirmMessaging: Boolean
        get() = prefs.getBoolean(KEY_MESSAGING, true)
        set(v) = prefs.edit().putBoolean(KEY_MESSAGING, v).apply()

    /**
     * Should the agent ask before doing this category of action?
     * Categories: "payment", "booking", "ordering", "purchase", "messaging", "community"
     */
    fun shouldAsk(category: String): Boolean {
        return when (mode) {
            Mode.AUTO -> false
            Mode.ASK -> true
            Mode.SMART -> when (category.lowercase()) {
                "payment" -> confirmPayment
                "booking" -> confirmBooking
                "ordering" -> confirmOrdering
                "purchase" -> confirmPurchase
                "messaging" -> confirmMessaging
                "community" -> true // always ask for unverified community skills
                else -> false
            }
        }
    }

    /** Backwards compat — does global askPermission mean ask? */
    val askPermission: Boolean get() = mode != Mode.AUTO

    fun buildStatusMessage(): String = buildString {
        val modeEmoji = when (mode) {
            Mode.ASK -> "🔴"
            Mode.SMART -> "🟢"
            Mode.AUTO -> "⚡"
        }
        val modeLabel = when (mode) {
            Mode.ASK -> "Ask Everything"
            Mode.SMART -> "Smart (Recommended)"
            Mode.AUTO -> "Auto — Never Ask"
        }
        appendLine("$modeEmoji *Permission Mode: $modeLabel*")
        appendLine()
        if (mode == Mode.SMART) {
            appendLine("Confirmation required for:")
            appendLine("  ${if (confirmPayment) "✅" else "❌"} Payment (GPay, PhonePe, Paytm) — `/permissions payment ${if (confirmPayment) "off" else "on"}`")
            appendLine("  ${if (confirmBooking) "✅" else "❌"} Cab Booking (Ola, Uber) — `/permissions booking ${if (confirmBooking) "off" else "on"}`")
            appendLine("  ${if (confirmOrdering) "✅" else "❌"} Food/Grocery Orders (Swiggy, Zomato…) — `/permissions ordering ${if (confirmOrdering) "off" else "on"}`")
            appendLine("  ${if (confirmPurchase) "✅" else "❌"} Purchases (Amazon, Flipkart) — `/permissions purchase ${if (confirmPurchase) "off" else "on"}`")
            appendLine("  ${if (confirmMessaging) "✅" else "❌"} Messaging (WhatsApp, Instagram) — `/permissions messaging ${if (confirmMessaging) "off" else "on"}`")
            appendLine()
        }
        appendLine("Switch mode:")
        appendLine("`/permissions smart` — ask only for sensitive actions *(recommended)*")
        appendLine("`/permissions ask`   — ask before *everything*")
        appendLine("`/permissions auto`  — *never* ask, just do it")
    }.trim()

    fun handleCommand(args: String): String {
        val trimmed = args.trim().lowercase()

        if (trimmed.isBlank() || trimmed == "status" || trimmed == "show") {
            return buildStatusMessage()
        }

        when (trimmed) {
            "smart" -> { mode = Mode.SMART; return "🟢 *Smart mode* — I'll ask only before payments, bookings, orders, and purchases." }
            "ask", "on", "yes" -> { mode = Mode.ASK; return "🔴 *Ask Everything mode* — I'll ask before every sensitive action." }
            "auto", "off", "no", "just do it" -> { mode = Mode.AUTO; return "⚡ *Auto mode* — No confirmations. I'll execute everything immediately." }
        }

        // Toggle per-category: "payment on", "booking off", etc.
        val parts = trimmed.split(" ")
        if (parts.size == 2) {
            val category = parts[0]
            val onOff = parts[1] == "on"
            val changed = when (category) {
                "payment" -> { confirmPayment = onOff; true }
                "booking" -> { confirmBooking = onOff; true }
                "ordering", "order" -> { confirmOrdering = onOff; true }
                "purchase", "purchases" -> { confirmPurchase = onOff; true }
                "messaging", "message" -> { confirmMessaging = onOff; true }
                else -> false
            }
            if (changed) {
                val icon = if (onOff) "✅" else "❌"
                return "$icon *${category.replaceFirstChar { it.uppercase() }}* confirmation: ${if (onOff) "ON" else "OFF"}\n\n${buildStatusMessage()}"
            }
        }

        return "Unknown permissions command.\n\n${buildStatusMessage()}"
    }
}
