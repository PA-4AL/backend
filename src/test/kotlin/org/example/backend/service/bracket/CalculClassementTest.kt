package org.example.backend.service.bracket

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Le classement final était recalculé à la volée par le worker à chaque export :
 * invisible dans l'application, et impossible à corriger. Il devient une donnée
 * du tournoi, calculée ici.
 *
 * La règle de tri est **la même que celle du worker** — points, puis différence.
 * Ces tests la fixent : si l'une des deux implémentations dérive, un organisateur
 * verrait un vainqueur dans l'application et un autre dans le fichier exporté.
 */
class CalculClassementTest {

    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()
    private val c = UUID.randomUUID()
    private val d = UUID.randomUUID()

    /** Ordre des seeds : sert de départage final. */
    private val participants = listOf(a, b, c, d)

    @Test
    fun `les victoires passent avant tout`() {
        val matchs = listOf(
            MatchJoue(a, b, 2, 0),
            MatchJoue(c, d, 2, 1),
            MatchJoue(a, c, 2, 1),
        )

        // a : 2 victoires, c : 1, b et d : 0.
        assertEquals(listOf(a, c), CalculClassement.calculer(participants, matchs).take(2))
    }

    @Test
    fun `a egalite de points, la difference de score tranche`() {
        val matchs = listOf(
            MatchJoue(a, b, 5, 0), // a : +5
            MatchJoue(c, d, 2, 1), // c : +1
        )

        val classement = CalculClassement.calculer(participants, matchs)
        assertEquals(a, classement[0])
        assertEquals(c, classement[1], "c a autant de points que a mais une moins bonne différence")
    }

    @Test
    fun `a egalite parfaite, le meilleur seed reste devant`() {
        // Départage par seed et non par nom : stable, numérique, indépendant de la
        // locale — un tri par nom varierait selon l'environnement.
        val matchs = listOf(
            MatchJoue(a, b, 2, 0),
            MatchJoue(c, d, 2, 0),
        )

        val classement = CalculClassement.calculer(participants, matchs)
        assertEquals(listOf(a, c), classement.take(2))
    }

    @Test
    fun `un participant sans match joue figure quand meme au classement`() {
        // Une équipe qualifiée d'office par un bye et éliminée avant d'avoir joué
        // ne doit pas disparaître du classement.
        val classement = CalculClassement.calculer(participants, listOf(MatchJoue(a, b, 1, 0)))

        assertEquals(4, classement.size)
        assertEquals(setOf(a, b, c, d), classement.toSet())
    }

    @Test
    fun `un match impliquant un inconnu est ignore`() {
        // Inscription retirée après coup : mieux vaut ignorer le match que créer
        // une ligne fantôme dans le classement.
        val disparu = UUID.randomUUID()
        val classement = CalculClassement.calculer(participants, listOf(MatchJoue(a, disparu, 2, 0)))

        assertEquals(4, classement.size)
        assertEquals(
            0,
            CalculClassement.lignes(participants, listOf(MatchJoue(a, disparu, 2, 0)))
                .first { it.registrationId == a }.joues,
        )
    }

    @Test
    fun `le detail compte les matchs, les scores et les points`() {
        val lignes = CalculClassement.lignes(
            participants,
            listOf(MatchJoue(a, b, 2, 1), MatchJoue(b, c, 3, 0)),
        )

        val ligneB = lignes.first { it.registrationId == b }
        assertEquals(2, ligneB.joues)
        assertEquals(1, ligneB.victoires)
        assertEquals(1, ligneB.defaites)
        assertEquals(4, ligneB.pour) // 1 puis 3
        assertEquals(2, ligneB.contre) // 2 puis 0
        assertEquals(3, ligneB.points)
        assertEquals(2, ligneB.difference)
    }

    @Test
    fun `sans aucun match, l'ordre des seeds est conserve`() {
        assertEquals(participants, CalculClassement.calculer(participants, emptyList()))
    }
}
