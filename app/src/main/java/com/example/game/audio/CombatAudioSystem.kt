package com.example.game.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sin

/**
 * Low-overhead Procedural Audio Synthesizer for Helicopter War combat sound effects.
 * Synthesizes 9 compact WAV SFX once into app cache and plays via bounded SoundPool (max 8 streams)
 * with per-effect cooldown throttling so rapid combat bursts never exhaust native AudioTrack limits.
 */
class CombatAudioSystem(context: Context? = null) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val sampleRate = 22050
    private val isReady = AtomicBoolean(false)
    private val isReleased = AtomicBoolean(false)

    @Volatile
    var isMuted = false

    private var soundPool: SoundPool? = null
    private val soundIds = IntArray(9) { 0 }
    private val lastPlayedMs = Array(9) { AtomicLong(0L) }

    init {
        if (context != null) {
            initializeSoundPool(context.applicationContext)
        }
    }

    private fun initializeSoundPool(appContext: Context) {
        val pool = try {
            SoundPool.Builder()
                .setMaxStreams(8)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
        } catch (_: Exception) {
            null
        } ?: return

        soundPool = pool

        scope.launch {
            if (isReleased.get()) return@launch
            try {
                val sfxDir = File(appContext.cacheDir, "war_sfx").apply { mkdirs() }
                val specs = arrayOf(
                    SFX_MG to { generateMachineGun() },
                    SFX_ROCKET to { generateRocketLaunch() },
                    SFX_MISSILE to { generateMissileLaunch() },
                    SFX_EXPLOSION to { generateExplosion() },
                    SFX_LOCK to { generateTargetLockBeep() },
                    SFX_BOSS_WARN to { generateBossWarning() },
                    SFX_DRAGON to { generateDragonRoar() },
                    SFX_PLASMA to { generatePlasmaCharge() },
                    SFX_CANNON to { generateHeavyCannon() }
                )
                for ((idx, generator) in specs) {
                    if (isReleased.get()) return@launch
                    val wavFile = File(sfxDir, "sfx_$idx.wav")
                    if (!wavFile.exists() || wavFile.length() < 44L) {
                        writeWavFile(wavFile, generator())
                    }
                    soundIds[idx] = pool.load(wavFile.absolutePath, 1)
                }
                isReady.set(true)
            } catch (_: Exception) {
                // Keep gameplay silent rather than crashing if storage/audio fails
            }
        }
    }

    fun playMachineGun() = playSound(SFX_MG, volume = 0.55f, minIntervalMs = 85L)
    fun playRocketLaunch() = playSound(SFX_ROCKET, volume = 0.80f, minIntervalMs = 150L)
    fun playMissileLaunch() = playSound(SFX_MISSILE, volume = 0.85f, minIntervalMs = 180L)
    fun playExplosion() = playSound(SFX_EXPLOSION, volume = 0.85f, minIntervalMs = 75L)
    fun playTargetLockBeep() = playSound(SFX_LOCK, volume = 0.50f, minIntervalMs = 200L)
    fun playBossWarning() = playSound(SFX_BOSS_WARN, volume = 0.90f, minIntervalMs = 500L)
    fun playDragonRoar() = playSound(SFX_DRAGON, volume = 0.85f, minIntervalMs = 400L)
    fun playPlasmaCharge() = playSound(SFX_PLASMA, volume = 0.75f, minIntervalMs = 240L)
    fun playHeavyCannon() = playSound(SFX_CANNON, volume = 0.85f, minIntervalMs = 220L)

    private fun playSound(index: Int, volume: Float, minIntervalMs: Long) {
        if (isMuted || isReleased.get() || !isReady.get()) return
        val pool = soundPool ?: return
        val sid = soundIds[index]
        if (sid == 0) return

        val now = android.os.SystemClock.elapsedRealtime()
        val prev = lastPlayedMs[index].get()
        if (now - prev < minIntervalMs) return
        if (!lastPlayedMs[index].compareAndSet(prev, now)) return

        try {
            pool.play(sid, volume, volume, 1, 0, 1.0f)
        } catch (_: Exception) {}
    }

    fun release() {
        if (isReleased.compareAndSet(false, true)) {
            isReady.set(false)
            try {
                soundPool?.release()
            } catch (_: Exception) {}
            soundPool = null
        }
    }

    private fun generateMachineGun(): ShortArray {
        val numSamples = (sampleRate * 60) / 1000
        return ShortArray(numSamples) { i ->
            val t = i.toDouble() / sampleRate
            val freq = 140.0 - (t * 400.0)
            val noise = (Math.random() * 2.0 - 1.0) * 0.4
            val tone = if (sin(2.0 * Math.PI * freq * t) > 0) 0.6 else -0.6
            val envelope = 1.0 - (i.toDouble() / numSamples)
            ((tone + noise) * envelope * 24000.0).toInt().toShort()
        }
    }

    private fun generateRocketLaunch(): ShortArray {
        val numSamples = (sampleRate * 180) / 1000
        return ShortArray(numSamples) { i ->
            val t = i.toDouble() / sampleRate
            val noise = (Math.random() * 2.0 - 1.0) * 0.7
            val whoosh = sin(2.0 * Math.PI * (220.0 + t * 600.0) * t) * 0.4
            val envelope = sin(Math.PI * (i.toDouble() / numSamples))
            ((noise + whoosh) * envelope * 28000.0).toInt().toShort()
        }
    }

    private fun generateMissileLaunch(): ShortArray {
        val numSamples = (sampleRate * 260) / 1000
        return ShortArray(numSamples) { i ->
            val t = i.toDouble() / sampleRate
            val noise = (Math.random() * 2.0 - 1.0) * 0.8
            val sub = sin(2.0 * Math.PI * 90.0 * t) * 0.6
            val envelope = (1.0 - (i.toDouble() / numSamples)) * (if (i < 400) (i.toDouble() / 400) else 1.0)
            ((noise + sub) * envelope * 30000.0).toInt().toShort()
        }
    }

    private fun generateExplosion(): ShortArray {
        val numSamples = (sampleRate * 320) / 1000
        return ShortArray(numSamples) { i ->
            val noise = (Math.random() * 2.0 - 1.0)
            val decay = Math.exp(-i.toDouble() / (sampleRate * 0.08))
            (noise * decay * 31000.0).toInt().toShort()
        }
    }

    private fun generateTargetLockBeep(): ShortArray {
        val numSamples = (sampleRate * 80) / 1000
        return ShortArray(numSamples) { i ->
            val t = i.toDouble() / sampleRate
            val tone = sin(2.0 * Math.PI * 1800.0 * t)
            (tone * 20000.0).toInt().toShort()
        }
    }

    private fun generateBossWarning(): ShortArray {
        val numSamples = (sampleRate * 600) / 1000
        return ShortArray(numSamples) { i ->
            val t = i.toDouble() / sampleRate
            val freq = if ((t * 4).toInt() % 2 == 0) 440.0 else 660.0
            val tone = sin(2.0 * Math.PI * freq * t)
            val envelope = (1.0 - (i.toDouble() / numSamples)).coerceIn(0.0, 1.0)
            (tone * envelope * 26000.0).toInt().toShort()
        }
    }

    private fun generateDragonRoar(): ShortArray {
        val numSamples = (sampleRate * 500) / 1000
        return ShortArray(numSamples) { i ->
            val t = i.toDouble() / sampleRate
            val freq = 110.0 - (t * 60.0)
            val noise = (Math.random() * 2.0 - 1.0) * 0.7
            val tone = sin(2.0 * Math.PI * freq * t) * 0.6
            val envelope = sin(Math.PI * (i.toDouble() / numSamples))
            ((tone + noise) * envelope * 29000.0).toInt().toShort()
        }
    }

    private fun generatePlasmaCharge(): ShortArray {
        val numSamples = (sampleRate * 280) / 1000
        return ShortArray(numSamples) { i ->
            val t = i.toDouble() / sampleRate
            val freq = 300.0 + (t * 1200.0)
            val tone = sin(2.0 * Math.PI * freq * t)
            val envelope = (i.toDouble() / numSamples).coerceIn(0.0, 1.0)
            (tone * envelope * 25000.0).toInt().toShort()
        }
    }

    private fun generateHeavyCannon(): ShortArray {
        val numSamples = (sampleRate * 400) / 1000
        return ShortArray(numSamples) { i ->
            val t = i.toDouble() / sampleRate
            val bass = sin(2.0 * Math.PI * 65.0 * t) * 0.7
            val noise = (Math.random() * 2.0 - 1.0) * 0.5
            val decay = Math.exp(-i.toDouble() / (sampleRate * 0.09))
            ((bass + noise) * decay * 32000.0).toInt().toShort()
        }
    }

    private fun writeWavFile(file: File, samples: ShortArray) {
        val dataSize = samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM chunk size
            putShort(1) // PCM format
            putShort(1) // Mono
            putInt(sampleRate)
            putInt(sampleRate * 2) // Byte rate
            putShort(2) // Block align
            putShort(16) // Bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()

        val pcmBytes = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            pcmBytes.putShort(s)
        }

        FileOutputStream(file).use { out ->
            out.write(header)
            out.write(pcmBytes.array())
        }
    }

    companion object {
        private const val SFX_MG = 0
        private const val SFX_ROCKET = 1
        private const val SFX_MISSILE = 2
        private const val SFX_EXPLOSION = 3
        private const val SFX_LOCK = 4
        private const val SFX_BOSS_WARN = 5
        private const val SFX_DRAGON = 6
        private const val SFX_PLASMA = 7
        private const val SFX_CANNON = 8
    }
}
