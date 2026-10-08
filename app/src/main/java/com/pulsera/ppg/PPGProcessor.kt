package com.pulsera.ppg

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class PPGResult(
    val rawSignal: List<Float>,
    val filteredSignal: List<Float>,
    val heartRateBpm: Double?,
    val hrvRmssdMs: Double?,
    val respirationRateBrpm: Double?,
    val signalQualityOk: Boolean,
    val qualityMessage: String
)

class Biquad(private val fs: Double) {
    private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0
    private var a1 = 0.0; private var a2 = 0.0
    private var x1 = 0.0; private var x2 = 0.0
    private var y1 = 0.0; private var y2 = 0.0

    fun configureBandpass(centerFreqHz: Double, q: Double) {
        val w0 = 2.0 * PI * centerFreqHz / fs
        val alpha = sin(w0) / (2.0 * q)
        val cosw0 = cos(w0)
        val b0n = alpha
        val b1n = 0.0
        val b2n = -alpha
        val a0n = 1.0 + alpha
        val a1n = -2.0 * cosw0
        val a2n = 1.0 - alpha
        b0 = b0n / a0n; b1 = b1n / a0n; b2 = b2n / a0n
        a1 = a1n / a0n; a2 = a2n / a0n
    }

    fun configureLowpass(cutoffHz: Double, q: Double) {
        val w0 = 2.0 * PI * cutoffHz / fs
        val alpha = sin(w0) / (2.0 * q)
        val cosw0 = cos(w0)
        val b1n = 1.0 - cosw0
        val b0n = b1n / 2.0
        val b2n = b0n
        val a0n = 1.0 + alpha
        val a1n = -2.0 * cosw0
        val a2n = 1.0 - alpha
        b0 = b0n / a0n; b1 = b1n / a0n; b2 = b2n / a0n
        a1 = a1n / a0n; a2 = a2n / a0n
    }

    fun process(input: Double): Double {
        val out = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = input
        y2 = y1; y1 = out
        return out
    }

    fun filter(signal: List<Double>): List<Double> {
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
        return signal.map { process(it) }
    }
}

object PPGProcessor {

    fun process(rawSamples: List<Float>, fps: Double): PPGResult {
        if (rawSamples.size < fps * 5) {
            return PPGResult(rawSamples, emptyList(), null, null, null, false,
                "Capture too short - hold still for at least 15-20 seconds.")
        }

        val raw = rawSamples.map { it.toDouble() }
        val detrended = movingAverageSubtract(raw, windowSize = (fps * 1.5).toInt().coerceAtLeast(3))

        val bp1 = Biquad(fps).apply { configureBandpass(centerFreqHz = 1.5, q = 0.7) }
        val bp2 = Biquad(fps).apply { configureBandpass(centerFreqHz = 1.5, q = 0.7) }
        val stage1 = bp1.filter(detrended)
        val filtered = bp2.filter(stage1)

        val std = stdDev(filtered)
        val quality = std > 0.5
        if (!quality) {
            return PPGResult(
                rawSamples, filtered.map { it.toFloat() },
                null, null, null, false,
                "Weak signal detected. Reposition your finger to fully cover the camera and flash, then retry."
            )
        }

        val warmupSamples = (fps * 3.0).toInt().coerceAtMost(filtered.size / 3)
        val steadyState = filtered.drop(warmupSamples)

        val peakIndices = detectPeaks(steadyState, fps, minIntervalSec = 0.35)
        val refinedPeaks = interpolatePeakPositions(steadyState, peakIndices)
        val rawIbiMs = refinedPeaks.zipWithNext { a, b -> (b - a) / fps * 1000.0 }
        val ibiMs = rejectLocalOutliers(rawIbiMs)

        val hr = if (ibiMs.isNotEmpty()) 60000.0 / ibiMs.average() else null
        val hrv = if (ibiMs.size >= 3) rmssd(ibiMs) else null

        val envelope = extractEnvelope(steadyState, fps)
        val breathPeaks = detectPeaks(envelope, fps, minIntervalSec = 1.5)
        val breathIntervalsSec = breathPeaks.zipWithNext { a, b -> (b - a) / fps }
        val rr = if (breathIntervalsSec.isNotEmpty()) 60.0 / breathIntervalsSec.average() else null

        val hrOk = hr != null && hr in 35.0..180.0
        val hrvPlausible = hrv == null || hrv <= 120.0
        val rrPlausible = rr == null || rr in 8.0..35.0
        val lowConfidence = hrOk && (!hrvPlausible || !rrPlausible)

        val msg = when {
            !hrOk -> "Signal unstable - hold finger still and retry for a clearer reading."
            lowConfidence -> "Reading may be affected by motion or contact pressure - consider retrying for a more reliable result."
            else -> "Signal quality good. ${peakIndices.size} beats detected."
        }

        return PPGResult(
            rawSignal = rawSamples,
            filteredSignal = filtered.map { it.toFloat() },
            heartRateBpm = hr,
            hrvRmssdMs = hrv,
            respirationRateBrpm = rr,
            signalQualityOk = hrOk && !lowConfidence,
            qualityMessage = msg
        )
    }

    private fun movingAverageSubtract(signal: List<Double>, windowSize: Int): List<Double> {
        if (windowSize < 2) return signal
        val result = DoubleArray(signal.size)
        var sum = 0.0
        val window = ArrayDeque<Double>()
        for (i in signal.indices) {
            window.addLast(signal[i])
            sum += signal[i]
            if (window.size > windowSize) sum -= window.removeFirst()
            val avg = sum / window.size
            result[i] = signal[i] - avg
        }
        return result.toList()
    }

    private fun stdDev(signal: List<Double>): Double {
        if (signal.isEmpty()) return 0.0
        val mean = signal.average()
        val variance = signal.sumOf { (it - mean) * (it - mean) } / signal.size
        return sqrt(variance)
    }

    private fun interpolatePeakPositions(signal: List<Double>, peakIndices: List<Int>): List<Double> {
        return peakIndices.map { i ->
            if (i <= 0 || i >= signal.size - 1) {
                i.toDouble()
            } else {
                val yLeft = signal[i - 1]
                val yCenter = signal[i]
                val yRight = signal[i + 1]
                val denom = (yLeft - 2.0 * yCenter + yRight)
                val offset = if (denom != 0.0) 0.5 * (yLeft - yRight) / denom else 0.0
                i + offset.coerceIn(-0.5, 0.5)
            }
        }
    }

    private fun detectPeaks(signal: List<Double>, fps: Double, minIntervalSec: Double): List<Int> {
        if (signal.size < 5) return emptyList()
        val threshold = stdDev(signal) * 0.4
        val minGap = (minIntervalSec * fps).toInt().coerceAtLeast(1)
        val peaks = mutableListOf<Int>()
        var i = 1
        while (i < signal.size - 1) {
            val isLocalMax = signal[i] > signal[i - 1] && signal[i] >= signal[i + 1]
            if (isLocalMax && signal[i] > threshold) {
                if (peaks.isEmpty() || (i - peaks.last()) >= minGap) {
                    peaks.add(i)
                } else if (signal[i] > signal[peaks.last()]) {
                    peaks[peaks.size - 1] = i
                }
            }
            i++
        }
        return peaks
    }

    private fun rejectLocalOutliers(intervalsMs: List<Double>, windowRadius: Int = 3): List<Double> {
        if (intervalsMs.size < 5) return intervalsMs
        val result = mutableListOf<Double>()
        for (i in intervalsMs.indices) {
            val lo = (i - windowRadius).coerceAtLeast(0)
            val hi = (i + windowRadius).coerceAtMost(intervalsMs.size - 1)
            val neighbourhood = (lo..hi).filter { it != i }.map { intervalsMs[it] }
            if (neighbourhood.isEmpty()) {
                result.add(intervalsMs[i])
                continue
            }
            val localMedian = neighbourhood.sorted().let { it[it.size / 2] }
            if (intervalsMs[i] in (localMedian * 0.8)..(localMedian * 1.2)) {
                result.add(intervalsMs[i])
            }
        }
        return if (result.size >= 3) result else intervalsMs
    }

    private fun rmssd(intervalsMs: List<Double>): Double {
        val diffs = intervalsMs.zipWithNext { a, b -> (b - a) * (b - a) }
        return sqrt(diffs.average())
    }

    private fun extractEnvelope(signal: List<Double>, fps: Double): List<Double> {
        val rectified = signal.map { abs(it) }
        val lp = Biquad(fps).apply { configureLowpass(cutoffHz = 0.6, q = 0.7) }
        return lp.filter(rectified)
    }
}