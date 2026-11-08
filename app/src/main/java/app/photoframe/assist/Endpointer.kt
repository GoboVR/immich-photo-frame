package app.photoframe.assist

import kotlin.math.max

/**
 * Local end-of-speech detection on 80 ms frames, so we don't depend solely on Home
 * Assistant's VAD (which can be slow or, in noisy rooms, never fire).
 *
 * 1. Calibrate: the first [calibrationFrames] frames estimate the room's noise floor.
 * 2. Speech starts once energy stays above the threshold for [startFrames] frames.
 * 3. Speech ends after [endSilenceMs] of energy below a lower (hysteresis) threshold.
 * 4. If nobody speaks within [noSpeechTimeoutMs], give up.
 *
 * Energies are RMS normalised to 0..1 (as delivered by [AudioCapture]).
 */
class Endpointer(
    private val frameMs: Int = 80,
    private val calibrationFrames: Int = 4,
    private val startFrames: Int = 2,
    private val endSilenceMs: Int = 1000,
    private val noSpeechTimeoutMs: Int = 7000,
    private val minThreshold: Float = 0.012f,
    private val maxThreshold: Float = 0.08f,
) {
    enum class Result { CONTINUE, END_OF_SPEECH, NO_SPEECH }

    private var frames = 0
    private var floorSum = 0f
    var threshold = minThreshold; private set
    var speechStarted = false; private set
    private var aboveRun = 0
    private var silenceMs = 0
    var speechMs = 0; private set
    var peak = 0f; private set
    val noiseFloor: Float get() = if (frames == 0) 0f else floorSum / minOf(frames, calibrationFrames)

    fun feed(rms: Float): Result {
        frames++
        peak = max(peak, rms)
        if (frames <= calibrationFrames) {
            floorSum += rms
            if (frames == calibrationFrames) {
                // Speech is typically several times louder than a quiet room.
                threshold = (noiseFloor * 2.0f + 0.004f).coerceIn(minThreshold, maxThreshold)
            }
            // Someone talking straight away (before calibration finished) still counts.
            if (rms > maxThreshold) { aboveRun++ }
            return Result.CONTINUE
        }

        if (!speechStarted) {
            aboveRun = if (rms > threshold) aboveRun + 1 else 0
            if (aboveRun >= startFrames) speechStarted = true
            else if (frames * frameMs >= noSpeechTimeoutMs) return Result.NO_SPEECH
            return Result.CONTINUE
        }

        speechMs += frameMs
        // Hysteresis: trailing-off syllables are quieter than onsets.
        if (rms < threshold * 0.7f) {
            silenceMs += frameMs
            if (silenceMs >= endSilenceMs) return Result.END_OF_SPEECH
        } else {
            silenceMs = 0
        }
        return Result.CONTINUE
    }
}
