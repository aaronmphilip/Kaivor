package com.kaivor.agent

import android.widget.Button

object UiColors {
    const val BG = 0xFF000000.toInt()
    const val SURFACE = 0xFF1C1C1E.toInt()
    const val SURFACE_ELEVATED = 0xFF2C2C2E.toInt()
    const val SURFACE_INACTIVE = 0xFF3A3A3C.toInt()
    const val LABEL_PRIMARY = 0xFFFFFFFF.toInt()
    const val LABEL_SECONDARY = 0xFF98989D.toInt()
    const val LABEL_TERTIARY = 0xFF636366.toInt()
    const val ACCENT_BLUE = 0xFF0A84FF.toInt()
    const val ACCENT_GREEN = 0xFF30D158.toInt()
    const val ACCENT_RED = 0xFFFF453A.toInt()
    const val ACCENT_ORANGE = 0xFFFF9F0A.toInt()
    const val ACTIVE_FILL = 0xFFFFFFFF.toInt()
    const val ACTIVE_TEXT = 0xFF000000.toInt()
    const val INACTIVE_FILL = 0xFF3A3A3C.toInt()
    const val INACTIVE_TEXT = 0xFF98989D.toInt()
    const val OFF_FILL = 0xFF48484A.toInt()

    fun setActive(btn: Button) {
        btn.setBackgroundColor(ACTIVE_FILL)
        btn.setTextColor(ACTIVE_TEXT)
    }

    fun setInactive(btn: Button) {
        btn.setBackgroundColor(INACTIVE_FILL)
        btn.setTextColor(INACTIVE_TEXT)
    }

    fun setOff(btn: Button) {
        btn.setBackgroundColor(OFF_FILL)
        btn.setTextColor(LABEL_PRIMARY)
    }

    fun resetButtons(vararg buttons: Button?) {
        buttons.filterNotNull().forEach { setInactive(it) }
    }
}