package com.kaivor.agent

import android.content.Context

/**
 * MuteStore — remembers which apps the user has muted for notification relay.
 *
 * When an app's package is in the mute set, incoming notifications from it
 * are dropped by NotificationRelay and never forwarded to Telegram. Persisted
 * across reboots in SharedPreferences.
 */
class MuteStore(context: Context) {

    companion object {
        private const val PREFS = "kaivor_mutes"
        private const val KEY_MUTED = "muted_packages"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isMuted(pkg: String): Boolean = list().contains(pkg)

    fun mute(pkg: String) {
        val next = list().toMutableSet().apply { add(pkg) }
        prefs.edit().putStringSet(KEY_MUTED, next).apply()
    }

    fun unmute(pkg: String) {
        val next = list().toMutableSet().apply { remove(pkg) }
        prefs.edit().putStringSet(KEY_MUTED, next).apply()
    }

    fun list(): Set<String> = prefs.getStringSet(KEY_MUTED, emptySet()) ?: emptySet()

    fun clear() {
        prefs.edit().remove(KEY_MUTED).apply()
    }
}
