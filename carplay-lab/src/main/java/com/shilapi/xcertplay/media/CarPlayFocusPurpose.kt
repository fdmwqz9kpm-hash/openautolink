package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioManager
import com.shilapi.xcertplay.airplay.AudioFormat

/** Ordinary Android logical usages, not privileged native GM routing or mode changes. */
internal enum class CarPlayFocusPurpose(val usage: Int, val content: Int, val gain: Int) {
    MEDIA(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC, AudioManager.AUDIOFOCUS_GAIN),
    CALL(AudioAttributes.USAGE_VOICE_COMMUNICATION, AudioAttributes.CONTENT_TYPE_SPEECH, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT),
    RING(AudioAttributes.USAGE_NOTIFICATION_RINGTONE, AudioAttributes.CONTENT_TYPE_SONIFICATION, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT),
    ASSISTANT(AudioAttributes.USAGE_ASSISTANT, AudioAttributes.CONTENT_TYPE_SPEECH, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);

    fun attributes(): AudioAttributes = AudioAttributes.Builder().setUsage(usage).setContentType(content).build()

    companion object {
        fun of(format: AudioFormat): CarPlayFocusPurpose? = when {
            // GM/reference alternate guidance does not request or retain primary focus.
            format.payloadType == 101 -> null
            format.audioType == "telephony" -> CALL
            format.audioType == "alert" -> RING
            format.audioType == "speechrecognition" -> ASSISTANT
            else -> MEDIA
        }
    }
}
