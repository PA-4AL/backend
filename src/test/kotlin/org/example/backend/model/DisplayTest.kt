package org.example.backend.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Libellés de rounds et initiales : logique d'affichage pure (spec §4.2),
 * consommée telle quelle par le frontend.
 */
class DisplayTest {

    @Test
    fun `libelle des rounds selon la distance a la finale`() {
        assertEquals("Finale" to "F", Display.roundLabel(round = 3, totalRounds = 3))
        assertEquals("Demi-finales" to "SF", Display.roundLabel(round = 2, totalRounds = 3))
        assertEquals("Quarts de finale" to "QF", Display.roundLabel(round = 1, totalRounds = 3))
    }

    @Test
    fun `au dela des quarts le libelle est une fraction`() {
        // 16 participants : le round 1 est le 1/8 de finale.
        assertEquals("1/8 de finale" to "R16", Display.roundLabel(round = 1, totalRounds = 4))
        assertEquals("1/16 de finale" to "R32", Display.roundLabel(round = 1, totalRounds = 5))
    }

    @Test
    fun `initiales limitees a deux caracteres`() {
        assertEquals("TS", Display.initials("Team Solo"))
        assertEquals("LP", Display.initials("les_petits.poneys"))
        assertEquals("N", Display.initials("Nova"))
    }

    @Test
    fun `initiales d'un nom vide retombent sur un point d'interrogation`() {
        assertEquals("?", Display.initials(""))
        assertEquals("?", Display.initials("   "))
    }

    @Test
    fun `les couleurs bouclent sur la palette`() {
        assertEquals(Display.colorFor(0), Display.colorFor(Display.palette.size))
        assertEquals(Display.palette.size, Display.palette.distinct().size)
    }
}
