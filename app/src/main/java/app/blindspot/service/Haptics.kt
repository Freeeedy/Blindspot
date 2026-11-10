package app.blindspot.service

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The two alert patterns:
 *  - entering estimated coverage: one strong, long vibration (~0.9 s)
 *  - leaving all estimated coverage: two short, quick vibrations
 *
 * Alarm usage is requested so the pattern is still delivered when touch/notification
 * vibration is reduced by the system.
 */
object Haptics {
    private val ENTER_TIMINGS = longArrayOf(0, 900)
    private val ENTER_AMPS = intArrayOf(0, 255)
    private val EXIT_TIMINGS = longArrayOf(0, 140, 160, 140)
    private val EXIT_AMPS = intArrayOf(0, 255, 0, 255)

    fun enter(ctx: Context) = play(ctx, ENTER_TIMINGS, ENTER_AMPS)
    fun exit(ctx: Context) = play(ctx, EXIT_TIMINGS, EXIT_AMPS)

    private fun vibrator(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            ctx.getSystemService(Vibrator::class.java)
        }

    private fun play(ctx: Context, timings: LongArray, amps: IntArray) {
        val v = vibrator(ctx) ?: return
        if (!v.hasVibrator()) return
        val effect = if (v.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(timings, amps, -1)
        } else {
            VibrationEffect.createWaveform(timings, -1)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }
}
