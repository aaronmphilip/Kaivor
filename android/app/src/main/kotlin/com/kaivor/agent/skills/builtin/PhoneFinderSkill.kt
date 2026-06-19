package com.kaivor.agent.skills.builtin

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import com.kaivor.agent.skills.*
import kotlinx.coroutines.delay

class PhoneFinderSkill : Skill {

    override val manifest = SkillManifest(
        id = "finder",
        name = "Find My Phone",
        version = "1.0.0",
        description = "Ring this phone at maximum volume for 30 seconds to help you locate it. Say 'ring my phone', 'find my phone', or 'where is my phone'.",
        author = "kaivor-team",
        trusted = true,
        permissions = emptySet(),
        allowedPackages = emptySet(),
        exampleParamsHint = """{"duration": 30}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val ctx = context.runner.getContext()
        val durationSec = ((params["duration"] as? Number)?.toInt() ?: 30).coerceIn(5, 120)

        val audioManager = ctx.getSystemService(AudioManager::class.java)
        val originalAlarmVol = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        val originalRingVol = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        val maxAlarmVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val maxRingVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)

        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarmVol, 0)
        audioManager.setStreamVolume(AudioManager.STREAM_RING, maxRingVol, 0)

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return SkillResult.Failure("No ringtone found on this device.")

        val ringtone = RingtoneManager.getRingtone(ctx, uri)
            ?: return SkillResult.Failure("Could not load ringtone.")

        ringtone.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        return try {
            ringtone.play()
            delay(durationSec * 1000L)
            SkillResult.Success("Rang for $durationSec seconds. Found it?")
        } finally {
            try { if (ringtone.isPlaying) ringtone.stop() } catch (_: Exception) {}
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalAlarmVol, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_RING, originalRingVol, 0)
        }
    }
}
