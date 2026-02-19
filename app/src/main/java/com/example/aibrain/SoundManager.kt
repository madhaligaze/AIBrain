package com.example.aibrain

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.util.Log

/**
 * Менеджер звуковых эффектов с поддержкой 3D позиционирования.
 */
class SoundManager(private val context: Context) {

    private val soundPool: SoundPool
    private val sounds = mutableMapOf<SoundType, Int>()
    private val mediaPlayers = mutableMapOf<SoundType, MediaPlayer>()

    init {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(10)
            .setAudioAttributes(audioAttributes)
            .build()

        loadSounds()
    }

    /**
     * Загрузить все звуковые эффекты.
     */
    private fun loadSounds() {
        try {
            sounds[SoundType.COLLAPSE] = soundPool.load(context, R.raw.metal_crash, 1)
            sounds[SoundType.PLACE] = soundPool.load(context, R.raw.metal_place, 1)
            sounds[SoundType.REMOVE] = soundPool.load(context, R.raw.metal_remove, 1)
            sounds[SoundType.STRESS] = soundPool.load(context, R.raw.structure_stress, 1)
            sounds[SoundType.WHOOSH] = soundPool.load(context, R.raw.whoosh_build, 1)
            sounds[SoundType.DUST_IMPACT] = soundPool.load(context, R.raw.dust_impact, 1)

            Log.d("SoundManager", "✅ Все звуки загружены")
        } catch (e: Exception) {
            Log.e("SoundManager", "❌ Ошибка загрузки звуков: ${e.message}")
        }
    }

    /**
     * Воспроизвести звук с настраиваемой громкостью и высотой.
     */
    fun play(
        type: SoundType,
        volume: Float = 1.0f,
        pitch: Float = 1.0f,
        loop: Boolean = false
    ): Int {
        val soundId = sounds[type] ?: return -1
        val loopMode = if (loop) -1 else 0

        return soundPool.play(
            soundId,
            volume,
            volume,
            1,
            loopMode,
            pitch
        )
    }

    /**
     * Воспроизвести звук с 3D позиционированием.
     */
    fun play3D(
        type: SoundType,
        distance: Float,
        maxDistance: Float = 20.0f,
        pitch: Float = 1.0f
    ) {
        val volume = (1.0f - (distance / maxDistance).coerceIn(0f, 1f)).coerceAtLeast(0.1f)
        play(type, volume, pitch)
    }

    /**
     * Воспроизвести длинный звук (например, скрип).
     */
    fun playLongSound(type: SoundType, loop: Boolean = false) {
        try {
            val resourceId = when (type) {
                SoundType.STRESS -> R.raw.structure_stress
                else -> return
            }

            mediaPlayers[type]?.release()

            val player = MediaPlayer.create(context, resourceId).apply {
                isLooping = loop
                setVolume(0.5f, 0.5f)
                start()
            }

            mediaPlayers[type] = player
        } catch (e: Exception) {
            Log.e("SoundManager", "Ошибка воспроизведения длинного звука: ${e.message}")
        }
    }

    fun stopLongSound(type: SoundType) {
        mediaPlayers[type]?.apply {
            if (isPlaying) {
                stop()
            }
            release()
        }
        mediaPlayers.remove(type)
    }

    fun stopAll() {
        soundPool.autoPause()
        mediaPlayers.values.forEach { it.release() }
        mediaPlayers.clear()
    }

    fun release() {
        soundPool.release()
        mediaPlayers.values.forEach { it.release() }
        mediaPlayers.clear()
    }
}

enum class SoundType {
    COLLAPSE,
    PLACE,
    REMOVE,
    STRESS,
    WHOOSH,
    DUST_IMPACT
}
