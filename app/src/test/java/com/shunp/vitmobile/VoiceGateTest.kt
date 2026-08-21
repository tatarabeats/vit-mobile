package com.shunp.vitmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceGateTest {
    @Test
    fun thresholdIsMaxOfFloorAndP95Times022() {
        val speech = List(20) { 200 } + List(80) { 8000 }
        val p95 = VoiceGate.percentile95(speech)
        assertEquals(8000, p95)
        assertEquals(maxOf(VoiceGate.AMP_FLOOR, (8000 * 0.22).toInt()), VoiceGate.voicedThreshold(speech))
    }

    @Test
    fun thresholdIsNotTheOldGlobal2200() {
        val quietSpeech = List(10) { 150 } + List(40) { 1800 }
        val th = VoiceGate.voicedThreshold(quietSpeech)
        assertTrue(th < 2200)
        assertEquals(maxOf(VoiceGate.AMP_FLOOR, (VoiceGate.percentile95(quietSpeech) * 0.22).toInt()), th)
        assertTrue(VoiceGate.hasVoice(quietSpeech, 5000))
    }

    @Test
    fun shortBurstBelow350msIsSkipped() {
        val amps = List(3) { 9000 } + List(20) { 200 }
        assertFalse(VoiceGate.hasVoice(amps, 2300))
    }

    @Test
    fun longRecordingWithLittleVoiceIsSkipped() {
        val amps = List(5) { 9000 } + List(45) { 300 }
        assertFalse(VoiceGate.hasVoice(amps, 5000))
    }

    @Test
    fun longRecordingWithEnoughVoicePasses() {
        val amps = List(12) { 9000 } + List(28) { 300 }
        assertTrue(VoiceGate.hasVoice(amps, 4000))
    }

    @Test
    fun digitalSilenceIsSkipped() {
        val amps = List(20) { 50 }
        assertFalse(VoiceGate.hasVoice(amps, 2000))
    }

    @Test
    fun roomNoiseBelowSpeechPeakDoesNotCountAsVoice() {
        val speechPeak = 10000
        val room = 1800
        val amps = List(15) { speechPeak } + List(25) { room }
        val th = VoiceGate.voicedThreshold(amps)
        assertTrue(th > room)
        assertEquals(15 * VoiceGate.FRAME_MS, VoiceGate.voicedMs(amps, th))
        assertTrue(VoiceGate.hasVoice(amps, 4000))
    }
}
