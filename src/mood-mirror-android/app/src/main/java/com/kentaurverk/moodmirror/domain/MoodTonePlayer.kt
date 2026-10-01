package com.kentaurverk.moodmirror.domain

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

class MoodTonePlayer {
    fun play(label: ExpressionLabel) {
        val frequency = when (label) {
            ExpressionLabel.HAPPY -> 659.0
            ExpressionLabel.SURPRISED -> 784.0
            ExpressionLabel.SAD -> 294.0
            ExpressionLabel.ANGRY -> 196.0
            ExpressionLabel.DISGUSTED -> 247.0
            ExpressionLabel.FEARFUL -> 370.0
            ExpressionLabel.NEUTRAL -> 440.0
        }
        val sampleRate = 44_100
        val sampleCount = (sampleRate * 0.16).toInt()
        val samples = ShortArray(sampleCount)
        for (i in samples.indices) {
            val envelope = (1.0 - i.toDouble() / sampleCount).coerceAtLeast(0.0)
            samples[i] = (sin(2.0 * PI * frequency * i / sampleRate) * envelope * Short.MAX_VALUE * 0.18).toInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(samples, 0, samples.size)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(track: AudioTrack) {
                track.stop()
                track.release()
            }

            override fun onPeriodicNotification(track: AudioTrack) = Unit
        })
        track.setNotificationMarkerPosition(samples.size)
        track.play()
    }
}
