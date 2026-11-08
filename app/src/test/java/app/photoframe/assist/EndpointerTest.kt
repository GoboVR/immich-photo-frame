package app.photoframe.assist

import app.photoframe.assist.Endpointer.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointerTest {
    /** Feeds frames, returns (index of first non-CONTINUE result, that result). */
    private fun run(e: Endpointer, frames: List<Float>): Pair<Int, Result> {
        frames.forEachIndexed { i, rms ->
            val r = e.feed(rms)
            if (r != Result.CONTINUE) return i to r
        }
        return -1 to Result.CONTINUE
    }

    private fun quiet(n: Int, level: Float = 0.004f) = List(n) { level }
    private fun loud(n: Int, level: Float = 0.06f) = List(n) { level }

    @Test fun endsAboutOneSecondAfterSpeech() {
        val e = Endpointer()
        val frames = quiet(4) + loud(25) + quiet(40) // 2 s of speech then silence
        val (idx, r) = run(e, frames)
        assertEquals(Result.END_OF_SPEECH, r)
        // 1000 ms / 80 ms = 13 silent frames after speech (index 4+25 .. )
        assertEquals(4 + 25 + 12, idx)
        assertTrue(e.speechStarted)
    }

    @Test fun shortPauseMidSentenceDoesNotEnd() {
        val e = Endpointer()
        val frames = quiet(4) + loud(10) + quiet(7) /* 560 ms pause */ + loud(10) + quiet(40)
        val (idx, r) = run(e, frames)
        assertEquals(Result.END_OF_SPEECH, r)
        assertTrue("ended during the pause at $idx", idx > 4 + 10 + 7 + 10)
    }

    @Test fun noisyRoomRaisesThreshold() {
        val e = Endpointer()
        // TV in the background at 0.03: must not count as speech on its own (threshold 0.064)
        val (idx, r) = run(e, quiet(4, 0.03f) + quiet(120, 0.03f)) // 9.9 s of TV noise
        assertEquals(Result.NO_SPEECH, r)
        assertTrue(e.threshold > 0.03f)
        assertTrue(idx > 0)
    }

    @Test fun speechOverNoiseStillDetected() {
        val e = Endpointer()
        val (_, r) = run(e, quiet(4, 0.03f) + loud(20, 0.15f) + quiet(30, 0.03f))
        assertEquals(Result.END_OF_SPEECH, r)
    }

    @Test fun nothingSaidGivesUpAfterSevenSeconds() {
        val e = Endpointer()
        val (idx, r) = run(e, quiet(200))
        assertEquals(Result.NO_SPEECH, r)
        assertEquals(87, idx) // 88th frame (index 87) = 7.04 s, first frame past 7 s
    }

    @Test fun singleClickIsNotSpeech() {
        val e = Endpointer()
        val (_, r) = run(e, quiet(4) + listOf(0.2f) + quiet(100))
        assertEquals(Result.NO_SPEECH, r)
    }
}
