package org.example.backend.model

import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.test.assertEquals

/**
 * Helpers d'affichage partagés — testables sans contexte Spring ni base.
 * `relativeTime` prend un `now` explicite, les paliers sont donc déterministes.
 */
class DisplayTests {

    private val now: OffsetDateTime = OffsetDateTime.parse("2026-07-25T12:00:00+02:00")

    @Test
    fun `les quatre paliers d anciennete`() {
        assertEquals("À l'instant", Display.relativeTime(now.minusSeconds(30), now))
        assertEquals("Il y a 12 min", Display.relativeTime(now.minusMinutes(12), now))
        assertEquals("Il y a 3 h", Display.relativeTime(now.minusHours(3), now))
        assertEquals("Il y a 2 j", Display.relativeTime(now.minusDays(2), now))
    }

    @Test
    fun `les bornes basculent au bon moment`() {
        assertEquals("À l'instant", Display.relativeTime(now.minusSeconds(59), now))
        assertEquals("Il y a 1 min", Display.relativeTime(now.minusMinutes(1), now))
        assertEquals("Il y a 59 min", Display.relativeTime(now.minusMinutes(59), now))
        assertEquals("Il y a 1 h", Display.relativeTime(now.minusHours(1), now))
        assertEquals("Il y a 23 h", Display.relativeTime(now.minusHours(23), now))
        assertEquals("Il y a 1 j", Display.relativeTime(now.minusHours(24), now))
    }

    @Test
    fun `les initiales prennent au plus deux lettres`() {
        assertEquals("TN", Display.initials("Team Nebula"))
        assertEquals("AP", Display.initials("apex_pred"))
        assertEquals("V", Display.initials("Vortex"))
        assertEquals("?", Display.initials(""))
    }

    @Test
    fun `les libelles de round dependent de la distance a la finale`() {
        assertEquals("Finale" to "F", Display.roundLabel(3, 3))
        assertEquals("Demi-finales" to "SF", Display.roundLabel(2, 3))
        assertEquals("Quarts de finale" to "QF", Display.roundLabel(1, 3))
        assertEquals("1/8 de finale" to "R16", Display.roundLabel(1, 4))
    }
}
