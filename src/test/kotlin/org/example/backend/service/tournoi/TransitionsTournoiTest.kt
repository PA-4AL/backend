package org.example.backend.service.tournoi

import org.example.backend.database.enums.TournamentStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cycle de vie d'un tournoi.
 *
 * Avant cette table, un tournoi naissait en `draft` et n'en sortait que tout seul,
 * à la saisie du premier score : `registration` et `check_in` étaient
 * **inatteignables** pour un tournoi créé dans l'application. Ces tests fixent le
 * chemin nominal et, surtout, ce qui doit rester interdit.
 */
class TransitionsTournoiTest {

    @Test
    fun `le chemin nominal est ouvert de bout en bout`() {
        val chemin = listOf(
            TournamentStatus.draft,
            TournamentStatus.registration,
            TournamentStatus.check_in,
            TournamentStatus.ongoing,
            TournamentStatus.finished,
        )
        chemin.zipWithNext().forEach { (de, vers) ->
            assertTrue(
                TransitionsTournoi.estAutorisee(de, vers),
                "${de.literal} → ${vers.literal} devrait être permis",
            )
        }
    }

    @Test
    fun `un tournoi termine ne se rouvre pas`() {
        // C'est un résultat, pas une étape. Le rouvrir invaliderait un classement
        // déjà figé et publié.
        assertTrue(TransitionsTournoi.estTerminal(TournamentStatus.finished))
        assertEquals(emptySet(), TransitionsTournoi.depuis(TournamentStatus.finished))
    }

    @Test
    fun `un tournoi annule ne se retracte pas`() {
        assertTrue(TransitionsTournoi.estTerminal(TournamentStatus.cancelled))
    }

    @Test
    fun `on ne saute pas le demarrage pour aller a la fin`() {
        // Clore un tournoi qui n'a jamais commencé n'a pas de sens : il n'a produit
        // aucun résultat à figer.
        assertFalse(TransitionsTournoi.estAutorisee(TournamentStatus.draft, TournamentStatus.finished))
        assertFalse(TransitionsTournoi.estAutorisee(TournamentStatus.registration, TournamentStatus.finished))
    }

    @Test
    fun `deux retours en arriere sont permis, et seulement ceux-la`() {
        // Rouvrir les inscriptions quand trop peu d'équipes se présentent, et
        // refermer un tournoi ouvert par erreur : deux situations banales.
        assertTrue(TransitionsTournoi.estAutorisee(TournamentStatus.check_in, TournamentStatus.registration))
        assertTrue(TransitionsTournoi.estAutorisee(TournamentStatus.registration, TournamentStatus.draft))
        // Mais on ne revient pas d'un tournoi commencé.
        assertFalse(TransitionsTournoi.estAutorisee(TournamentStatus.ongoing, TournamentStatus.registration))
        assertFalse(TransitionsTournoi.estAutorisee(TournamentStatus.ongoing, TournamentStatus.draft))
    }

    @Test
    fun `l'annulation est possible depuis tout etat non terminal`() {
        listOf(
            TournamentStatus.draft,
            TournamentStatus.registration,
            TournamentStatus.check_in,
            TournamentStatus.ongoing,
        ).forEach { statut ->
            assertTrue(
                TransitionsTournoi.estAutorisee(statut, TournamentStatus.cancelled),
                "annuler depuis ${statut.literal} devrait être permis",
            )
        }
    }

    @Test
    fun `une transition vers soi-meme est refusee`() {
        // Sans ce refus, un double clic produirait une annonce « le tournoi passe en
        // inscriptions » alors que rien n'a changé.
        TournamentStatus.entries.forEach { statut ->
            assertFalse(
                TransitionsTournoi.estAutorisee(statut, statut),
                "${statut.literal} → lui-même devrait être refusé",
            )
        }
    }

    @Test
    fun `les inscriptions restent ouvertes en brouillon`() {
        // L'organisateur ajoute ses participants avant d'annoncer le tournoi ; le
        // refuser l'obligerait à publier un tournoi vide.
        assertTrue(TransitionsTournoi.accepteDesInscriptions(TournamentStatus.draft))
        assertTrue(TransitionsTournoi.accepteDesInscriptions(TournamentStatus.registration))
        assertTrue(TransitionsTournoi.accepteDesInscriptions(TournamentStatus.check_in))
        assertFalse(TransitionsTournoi.accepteDesInscriptions(TournamentStatus.ongoing))
        assertFalse(TransitionsTournoi.accepteDesInscriptions(TournamentStatus.finished))
    }

    @Test
    fun `chaque statut a un libelle d'action a l'imperatif`() {
        // Le libellé vient du serveur pour que l'interface n'ait pas à traduire une
        // énumération de base de données, et que les deux ne divergent pas.
        TournamentStatus.entries.forEach { statut ->
            val libelle = TransitionsTournoi.libelleAction(statut)
            assertTrue(libelle.isNotBlank(), "libellé manquant pour ${statut.literal}")
        }
        assertEquals("Démarrer le tournoi", TransitionsTournoi.libelleAction(TournamentStatus.ongoing))
    }
}
