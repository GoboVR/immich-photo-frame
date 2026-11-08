package app.photoframe.immich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DurationTest {
    @Test fun parsesImmichDurations() {
        assertEquals(12.345, parseDuration("0:00:12.345000")!!, 1e-6)
        assertEquals(3723.5, parseDuration("1:02:03.500000")!!, 1e-6)
        assertEquals(0.0, parseDuration("0:00:00.00000")!!, 1e-6)
    }

    @Test fun rejectsGarbage() {
        assertNull(parseDuration(""))
        assertNull(parseDuration("12.3"))
        assertNull(parseDuration("a:b:c"))
    }
}
