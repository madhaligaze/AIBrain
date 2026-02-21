package com.example.aibrain

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.util.Log

class SoundManager(private val context: Context) {

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(10)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val sounds = mutableMapOf<SoundType, Int>()
    private val mediaPlayers = mutableMapOf<SoundType, MediaPlayer>()
    private val loadedSoundIds = mutableSetOf<Int>()

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loadedSoundIds.add(sampleId)
        }
        loadSounds()
    }

    private fun loadSounds() {
        try {
            loadIfValid(SoundType.COLLAPSE, R.raw.metal_crash)
            loadIfValid(SoundType.PLACE, R.raw.metal_place)
            loadIfValid(SoundType.REMOVE, R.raw.metal_remove)
            loadIfValid(SoundType.STRESS, R.raw.structure_stress)
            loadIfValid(SoundType.WHOOSH, R.raw.whoosh_build)
            loadIfValid(SoundType.DUST_IMPACT, R.raw.dust_impact)

            if (sounds.isNotEmpty()) {
                Log.d("SoundManager", "✅ Запрошена загрузка ${sounds.size} звуков")
            } else {
                Log.w("SoundManager", "⚠️ Нет валидных звуков для загрузки")
            }
        } catch (e: Exception) {
            Log.e("SoundManager", "❌ Ошибка загрузки звуков: ${e.message}")
        }
    }

    private fun loadIfValid(type: SoundType, resId: Int, minBytes: Long = 1024L) {
        val afd = runCatching { context.resources.openRawResourceFd(resId) }.getOrNull()
        if (afd == null) {
            Log.w("SoundManager", "⚠️ raw resource missing for $type")
            return
        }
        val len = afd.length
        afd.close()

        if (len in 1 until minBytes) {
            Log.w("SoundManager", "⚠️ raw resource too small ($len bytes) for $type, skipping")
            return
        }

        val sampleId = soundPool.load(context, resId, 1)
        if (sampleId == 0) {
            Log.w("SoundManager", "⚠️ SoundPool rejected sample for $type")
            return
        }
        sounds[type] = sampleId
    }

    fun play(type: SoundType, volume: Float = 1.0f, pitch: Float = 1.0f, loop: Boolean = false): Int {
        val soundId = sounds[type] ?: return -1
        if (!loadedSoundIds.contains(soundId)) return -1
        val loopMode = if (loop) -1 else 0

        return soundPool.play(soundId, volume, volume, 1, loopMode, pitch)
    }

    fun play3D(type: SoundType, distance: Float, maxDistance: Float = 20.0f, pitch: Float = 1.0f) {
        val volume = (1.0f - (distance / maxDistance).coerceIn(0f, 1f)).coerceAtLeast(0.1f)
        play(type, volume, pitch)
    }

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
            if (isPlaying) stop()
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
