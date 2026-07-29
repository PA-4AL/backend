package org.example.backend.service.bracket

import org.example.backend.database.enums.BracketType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Invariants des trois formats d'arbre. Le générateur étant une fonction pure,
 * ces tests n'ont besoin ni de base, ni de Spring, ni de mock.
 */
class GenerateurBracketTest {

    /** Tout match désigné comme cible doit exister : pas de lien pendant. */
    private fun verifierChainage(matchs: List<MatchPlanifie>) {
        val cles = matchs.map { it.cle }.toSet()
        matchs.forEach { m ->
            m.vainqueurVers?.let {
                assertTrue(it in cles, "${m.cle} envoie son vainqueur vers $it, qui n'existe pas")
            }
            m.perdantVers?.let {
                assertTrue(it in cles, "${m.cle} envoie son perdant vers $it, qui n'existe pas")
            }
        }
        assertEquals(cles.size, matchs.size, "des clés de match sont dupliquées")
    }

    // ----------------------------------------------------------------- //
    // Élimination simple
    // ----------------------------------------------------------------- //

    @Test
    fun `elimination simple — n-1 matchs pour n places`() {
        // Chaque match élimine une personne, il faut éliminer tout le monde sauf un.
        listOf(2, 4, 8, 16, 32).forEach { taille ->
            assertEquals(taille - 1, GenerateurBracket.eliminationSimple(taille).size, "taille $taille")
        }
    }

    @Test
    fun `elimination simple — un seul match sans suite, la finale`() {
        val matchs = GenerateurBracket.eliminationSimple(8)
        verifierChainage(matchs)
        assertEquals(1, matchs.count { it.vainqueurVers == null })
        assertEquals(3, matchs.maxOf { it.round })
        // Personne ne descend : perdre élimine.
        assertTrue(matchs.all { it.perdantVers == null })
    }

    @Test
    fun `elimination simple — les seeds forts sont separes`() {
        val premier = GenerateurBracket.eliminationSimple(8).filter { it.round == 1 }
        // Placement standard : 1 contre 8, et le 2 à l'opposé du tableau.
        assertEquals(1, premier.first().seedA)
        assertEquals(8, premier.first().seedB)
        assertTrue(premier.any { it.seedA == 2 || it.seedB == 2 })
        // Les seeds 1 et 2 ne peuvent pas se rencontrer avant la finale.
        val matchDuUn = premier.first { it.seedA == 1 || it.seedB == 1 }
        val matchDuDeux = premier.first { it.seedA == 2 || it.seedB == 2 }
        assertTrue(matchDuUn.cle != matchDuDeux.cle)
    }

    @Test
    fun `elimination simple — un nombre non puissance de deux produit des byes`() {
        // 5 participants → tableau de 8, donc 3 places vides au premier tour.
        val matchs = GenerateurBracket.eliminationSimple(5)
        assertEquals(7, matchs.size)
        val seedsPlaces = matchs.filter { it.round == 1 }.flatMap { listOfNotNull(it.seedA, it.seedB) }
        assertEquals(8, seedsPlaces.size)
        assertEquals(8, seedsPlaces.distinct().size, "un seed ne peut pas être placé deux fois")
    }

    // ----------------------------------------------------------------- //
    // Élimination double
    // ----------------------------------------------------------------- //

    @Test
    fun `elimination double — 2n-2 matchs`() {
        // n-1 pour désigner le vainqueur du tableau des vainqueurs, n-1 de plus
        // pour éliminer une seconde fois chaque participant.
        listOf(4, 8, 16).forEach { taille ->
            assertEquals(
                2 * taille - 2,
                GenerateurBracket.eliminationDouble(taille).size,
                "taille $taille",
            )
        }
    }

    @Test
    fun `elimination double — chainage complet et coherent`() {
        val matchs = GenerateurBracket.eliminationDouble(8)
        verifierChainage(matchs)

        // Un seul match sans suite : la grande finale.
        val sansSuite = matchs.filter { it.vainqueurVers == null }
        assertEquals(1, sansSuite.size)
        assertEquals(BracketType.grand_final, sansSuite.single().bracket)

        // Tous les matchs du tableau des vainqueurs font descendre leur perdant.
        val w = matchs.filter { it.bracket == BracketType.winner }
        assertTrue(w.all { it.perdantVers != null }, "un perdant du tableau W serait éliminé à tort")

        // Dans le tableau des perdants, perdre élimine.
        assertTrue(matchs.filter { it.bracket == BracketType.loser }.all { it.perdantVers == null })
    }

    @Test
    fun `elimination double — la finale du tableau des vainqueurs mene a la grande finale`() {
        val matchs = GenerateurBracket.eliminationDouble(8)
        val finaleW = matchs.filter { it.bracket == BracketType.winner }.maxBy { it.round }
        assertEquals("GF-1-1", finaleW.vainqueurVers)
        // Son perdant n'est pas éliminé : il descend dans le tableau des perdants.
        assertNotNull(finaleW.perdantVers)

        val finaleL = matchs.filter { it.bracket == BracketType.loser }.maxBy { it.round }
        assertEquals("GF-1-1", finaleL.vainqueurVers)
    }

    @Test
    fun `elimination double — chaque tour du tableau des vainqueurs a une cible distincte`() {
        val matchs = GenerateurBracket.eliminationDouble(16)
        // Deux matchs du même tour W ne doivent pas envoyer leurs perdants dans le
        // même match L, sinon des participants seraient écrasés.
        matchs.filter { it.bracket == BracketType.winner }
            .groupBy { it.round }
            .forEach { (tour, duTour) ->
                val cibles = duTour.mapNotNull { it.perdantVers }
                if (tour == 1) {
                    // Au 1er tour, les perdants s'affrontent : deux par match L.
                    assertEquals(duTour.size / 2, cibles.distinct().size, "tour $tour")
                } else {
                    assertEquals(duTour.size, cibles.distinct().size, "tour $tour")
                }
            }
    }

    @Test
    fun `elimination double — refusee en dessous de 4 places`() {
        assertFailsWith<IllegalArgumentException> { GenerateurBracket.eliminationDouble(2) }
    }

    // ----------------------------------------------------------------- //
    // Round robin
    // ----------------------------------------------------------------- //

    @Test
    fun `round robin — toutes les rencontres, une seule fois`() {
        listOf(4, 6, 8).forEach { n ->
            val matchs = GenerateurBracket.roundRobin(n)
            assertEquals(n * (n - 1) / 2, matchs.size, "n = $n")
            val paires = matchs.map { setOf(it.seedA, it.seedB) }
            assertEquals(paires.size, paires.distinct().size, "une rencontre est en double (n = $n)")
        }
    }

    @Test
    fun `round robin — un participant ne joue qu'une fois par journee`() {
        val matchs = GenerateurBracket.roundRobin(8)
        matchs.groupBy { it.round }.forEach { (journee, duJour) ->
            val participants = duJour.flatMap { listOfNotNull(it.seedA, it.seedB) }
            assertEquals(
                participants.size,
                participants.distinct().size,
                "un participant joue deux fois lors de la journée $journee",
            )
        }
    }

    @Test
    fun `round robin — nombre impair, chacun se repose une journee`() {
        val n = 5
        val matchs = GenerateurBracket.roundRobin(n)
        assertEquals(n * (n - 1) / 2, matchs.size)
        assertEquals(n, matchs.maxOf { it.round }, "il faut n journées quand n est impair")
        // Chaque participant joue n-1 matchs.
        (1..n).forEach { seed ->
            val joues = matchs.count { it.seedA == seed || it.seedB == seed }
            assertEquals(n - 1, joues, "le participant $seed joue $joues matchs")
        }
    }

    @Test
    fun `round robin — aucun chainage, ce n'est pas un arbre`() {
        val matchs = GenerateurBracket.roundRobin(6)
        assertTrue(matchs.all { it.vainqueurVers == null && it.perdantVers == null })
        assertTrue(matchs.all { it.bracket == BracketType.group })
        // Tous les participants sont connus dès la génération.
        assertTrue(matchs.all { it.seedA != null && it.seedB != null })
        assertNull(matchs.firstOrNull { it.seedA == it.seedB })
    }
}
