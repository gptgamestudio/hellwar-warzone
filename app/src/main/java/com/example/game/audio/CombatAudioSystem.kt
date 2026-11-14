package com.example.game.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.sin

/**
 * Real-time Procedural Audio Synthesizer for Helicopter War combat sound effects.
 * Synthesizes high-fidelity military weapon sounds using PCM AudioTrack with zero network dependency.
 */
class CombatAudioSystem {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val sampleRate = 22050
    var isMuted = false

    fun playMachineGun() {
        if (isMuted) return
        scope.launch {
            val durationMs = 60
            val numSamples = (sampleRate * durationMs) / 1000
            val buffer = ShortArray(numSamples)
            for (i in 0 until numSamples) {
                // Square wave + white noise punch for 30mm Vulcan rattle
                val t = i.toDouble() / sampleRate
                val freq = 140.0 - (t * 400.0)
                val noise = (Math.random() * 2.0 - 1.0) * 0.4
                val tone = if (sin(2.0 * Math.PI * freq * t) > 0) 0.6 else -0.6
                val envelope = 1.0 - (i.toDouble() / numSamples)
                buffer[i] = ((tone + noise) * envelope * 24000.0).toInt().toShort()
            }
            playPcmBuffer(buffer)
        }
    }

    fun playRocketLaunch() {
        if (isMuted) return
        scope.launch {
            val durationMs = 180
            val numSamples = (sampleRate * durationMs) / 1000
            val buffer = ShortArray(numSamples)
            for (i in 0 until numSamples) {
                val t = i.toDouble() / sampleRate
                val noise = (Math.random() * 2.0 - 1.0) * 0.7
                val whoosh = sin(2.0 * Math.PI * (220.0 + t * 600.0) * t) * 0.4
                val envelope = sin(Math.PI * (i.toDouble() / numSamples))
                buffer[i] = ((noise + whoosh) * envelope * 28000.0).toInt().toShort()
            }
            playPcmBuffer(buffer)
        }
    }

    fun playMissileLaunch() {
        if (isMuted) return
        scope.launch {
            val durationMs = 260
            val numSamples = (sampleRate * durationMs) / 1000
            val buffer = ShortArray(numSamples)
            for (i in 0 until numSamples) {
                val t = i.toDouble() / sampleRate
                val noise = (Math.random() * 2.0 - 1.0) * 0.8
                val sub = sin(2.0 * Math.PI * 90.0 * t) * 0.6
                val envelope = (1.0 - (i.toDouble() / numSamples)) * (if (i < 400) (i.toDouble() / 400) else 1.0)
                buffer[i] = ((noise + sub) * envelope * 30000.0).toInt().toShort()
            }
            playPcmBuffer(buffer)
        }
    }

    fun playExplosion() {
        if (isMuted) return
        scope.launch {
            val durationMs = 320
            val numSamples = (sampleRate * durationMs) / 1000
            val buffer = ShortArray(numSamples)
            for (i in 0 until numSamples) {
                val noise = (Math.random() * 2.0 - 1.0)
                val decay = Math.exp(-i.toDouble() / (sampleRate * 0.08))
                buffer[i] = (noise * decay * 31000.0).toInt().toShort()
            }
            playPcmBuffer(buffer)
        }
    }

    fun playTargetLockBeep() {
        if (isMuted) return
        scope.launch {
            val durationMs = 80
            val numSamples = (sampleRate * durationMs) / 1000
            val buffer = ShortArray(numSamples)
            for (i in 0 until numSamples) {
                val t = i.toDouble() / sampleRate
                val tone = sin(2.0 * Math.PI * 1800.0 * t)
                buffer[i] = (tone * 20000.0).toInt().toShort()
            }
            playPcmBuffer(buffer)
        }
    }

    fun playBossWarning() {
        if (isMuted) return
        scope.launch {
            val durationMs = 600
            val numSamples = (sampleRate * durationMs) / 1000
            val buffer = ShortArray(numSamples)
            for (i in 0 until numSamples) {
                val t = i.toDouble() / sampleRate
                // Dual alternating siren frequencies (440Hz and 660Hz)
                val freq = if ((t * 4).toInt() % 2 == 0) 440.0 else 660.0
                val tone = sin(2.0 * Math.PI * freq * t)
                val envelope = (1.0 - (i.toDouble() / numSamples)).coerceIn(0.0, 1.0)
                buffer[i] = (tone * envelope * 26000.0).toInt().toShort()
            }
            playPcmBuffer(buffer)
        }
    }

    fun playDragonRoar() {
        if (isMuted) return
        scope.launch {
            val durationMs = 500
            val numSamples = (sampleRate * durationMs) / 1000
            val buffer = ShortArray(numSamples)
            for (i in 0 until numSamples) {
                val t = i.toDouble() / sampleRate
                val freq = 110.0 - (t * 60.0)
                val noise = (Math.random() * 2.0 - 1.0) * 0.7
                val tone = sin(2.0 * Math.PI * freq * t) * 0.6
                val envelope = sin(Math.PI * (i.toDouble() / numSamples))
                buffer[i] = ((tone + noise) * envelope * 29000.0).toInt().toShort()
            }
            playPcmBuffer(buffer)
        }
    }

    fun playPlasmaCharge() {
        if (isMuted) return
        scope.launch {
            val durationMs = 280
            val numSamples = (sampleRate * durationMs) / 1000
            val buffer = ShortArray(numSamples)
            for (i in 0 until numSamples) {
                val t = i.toDouble() / sampleRate
                val freq = 300.0 + (t * 1200.0) // Rising sci-fi frequency
                val tone = sin(2.0 * Math.PI * freq * t)
                val envelope = (i.toDouble() / numSamples).coerceIn(0.0, 1.0)
                buffer[i] = (tone * envelope * 25000.0).toInt().toShort()
            }
            playPcmBuffer(buffer)
        }
    }

    fun playHeavyCannon() {
        if (isMuted) return
        scope.launch {
            val durationMs = 400
            val numSamples = (sampleRate * durationMs) / 1000
            val buffer = ShortArray(numSamples)
            for (i in 0 until numSamples) {
                val t = i.toDouble() / sampleRate
                val bass = sin(2.0 * Math.PI * 65.0 * t) * 0.7
                val noise = (Math.random() * 2.0 - 1.0) * 0.5
                val decay = Math.exp(-i.toDouble() / (sampleRate * 0.09))
                buffer[i] = ((bass + noise) * decay * 32000.0).toInt().toShort()
            }
            playPcmBuffer(buffer)
        }
    }

    private fun playPcmBuffer(buffer: ShortArray) {
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(buffer.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(buffer, 0, buffer.size)
            track.play()
            track.setNotificationMarkerPosition(buffer.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onPeriodicNotification(t: AudioTrack?) {}
                override fun onMarkerReached(t: AudioTrack?) {
                    try {
                        track.stop()
                        track.release()
                    } catch (_: Exception) {}
                }
            })
        } catch (_: Exception) {}
    }
}
