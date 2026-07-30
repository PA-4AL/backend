package org.example.backend.service

import org.example.backend.database.enums.TournamentStatus
import org.example.backend.repository.DashboardRepository
import org.example.backend.repository.RecentRegistrationRow
import org.example.backend.repository.RecentTournamentRow
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Tests unitaires du tableau de bord — **sans base de données**.
 *
 * C'est l'intérêt de l'extraction du `DashboardRepository` : la mise en forme
 * (libellés de delta, fusion du fil d'activité) se teste en mémoire.
 *
 * Le faux repository hérite de la vraie classe : le plugin `kotlin-spring` ouvre les
 * classes `@Repository`, donc aucune bibliothèque de mock n'est nécessaire. Le
 * `DSLContext` du constructeur parent n'est jamais sollicité puisque toutes les
 * méthodes sont redéfinies.
 */
class DashboardServiceTests {

    private val t0: OffsetDateTime = OffsetDateTime.parse("2026-07-25T12:00:00+02:00")

    private class FakeDashboardRepository(
        private val activeTournaments: Int = 0,
        private val tournamentsSince: Int = 0,
        private val liveMatches: Int = 0,
        private val activeRegistrations: Int = 0,
        private val registrationsSince: Int = 0,
        private val pendingRegistrations: Int = 0,
        private val registrations: List<RecentRegistrationRow> = emptyList(),
        private val tournaments: List<RecentTournamentRow> = emptyList(),
    ) : DashboardRepository(DSL.using(SQLDialect.POSTGRES)) {
        override fun countActiveTournaments() = activeTournaments
        override fun countTournamentsCreatedSince(since: OffsetDateTime) = tournamentsSince
        override fun countLiveMatches() = liveMatches
        override fun countActiveRegistrations() = activeRegistrations
        override fun countRegistrationsCreatedSince(since: OffsetDateTime) = registrationsSince
        override fun countPendingRegistrations() = pendingRegistrations
        override fun recentRegistrations(limit: Int) = registrations.take(limit)
        override fun recentTournaments(limit: Int) = tournaments.take(limit)
    }

    private fun registration(name: String?, tournament: String, at: OffsetDateTime) =
        RecentRegistrationRow(UUID.randomUUID(), name, tournament, at)

    private fun tournament(name: String, status: TournamentStatus, at: OffsetDateTime) =
        RecentTournamentRow(UUID.randomUUID(), name, status, at)

    /* ------------------------------------------------------------ KPIs */

    @Test
    fun `chaque compteur atterrit dans le bon champ`() {
        // Six valeurs distinctes : une inversion de deux champs ferait échouer le test.
        val service = DashboardService(
            FakeDashboardRepository(
                activeTournaments = 11,
                tournamentsSince = 22,
                liveMatches = 33,
                activeRegistrations = 44,
                registrationsSince = 55,
                pendingRegistrations = 66,
            ),
        )

        val kpis = service.kpis()

        assertEquals(11, kpis.activeTournaments)
        assertEquals("+22 cette semaine", kpis.activeTournamentsDelta)
        assertEquals(33, kpis.liveMatches)
        assertEquals(44, kpis.participants)
        assertEquals("+55 ce mois", kpis.participantsDelta)
        assertEquals(66, kpis.pendingValidations)
    }

    /* -------------------------------------------------- Fil d'activité */

    @Test
    fun `le fil fusionne les deux sources, trie par date et tronque a six`() {
        val service = DashboardService(
            FakeDashboardRepository(
                registrations = (1..5).map {
                    registration("Joueur $it", "Tournoi", t0.minusMinutes(it.toLong()))
                },
                tournaments = (1..5).map {
                    tournament("Tournoi $it", TournamentStatus.ongoing, t0.minusHours(it.toLong()))
                },
            ),
        )

        val activity = service.activity()

        assertEquals(6, activity.size)
        // Les 5 inscriptions (minutes) sont plus récentes que tous les tournois (heures)
        assertEquals(5, activity.count { it.kind == "registration" })
        assertEquals(1, activity.count { it.kind == "live" })
    }

    @Test
    fun `une inscription sans equipe ni joueur affiche un repli`() {
        val service = DashboardService(
            FakeDashboardRepository(
                registrations = listOf(registration(null, "Rookie Cup", t0)),
            ),
        )

        val item = service.activity().single()

        assertEquals("Un participant", item.sujet)
        assertEquals("Rookie Cup", item.complement)
    }

    @Test
    fun `un tournoi termine et un tournoi en cours ne produisent pas le meme evenement`() {
        val service = DashboardService(
            FakeDashboardRepository(
                tournaments = listOf(
                    tournament("Pro League", TournamentStatus.finished, t0),
                    tournament("Rookie Cup", TournamentStatus.registration, t0.minusMinutes(1)),
                ),
            ),
        )

        val (fini, encours) = service.activity()

        assertEquals("finished", fini.kind)
        assertEquals("Pro League", fini.sujet)
        assertEquals("est terminé.", fini.action)
        assertEquals("live", encours.kind)
        assertEquals("Rookie Cup", encours.sujet)
        assertEquals("a été créé.", encours.action)
    }

    @Test
    fun `aucun balisage ne sort du service`() {
        // Régression : le fil d'activité renvoyait du HTML construit par
        // concaténation, rendu tel quel par le frontend. Un tournoi nommé
        // « <img src=x onerror=…> » exécutait donc du code chez tout organisateur.
        // Le correctif n'est pas d'échapper mais de ne plus produire de balisage.
        val nomHostile = "<img src=x onerror=alert(1)>"
        val service = DashboardService(
            FakeDashboardRepository(
                registrations = listOf(registration(nomHostile, nomHostile, t0)),
                tournaments = listOf(tournament(nomHostile, TournamentStatus.finished, t0)),
            ),
        )

        service.activity().forEach { item ->
            // La donnée est transmise intacte — c'est au frontend de l'afficher
            // comme du texte — mais aucun champ ne contient de balise ajoutée par
            // le serveur.
            assertFalse(item.action.contains("<"), "action balisée : ${item.action}")
        }
        // Et le nom hostile ressort bien tel quel, sans être tronqué ni réécrit :
        // il sera affiché comme du texte, jamais interprété.
        assertEquals(nomHostile, service.activity().first().sujet)
    }
}
