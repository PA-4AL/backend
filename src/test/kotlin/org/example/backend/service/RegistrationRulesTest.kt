package org.example.backend.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.example.backend.database.enums.RegistrationStatus
import org.example.backend.database.tables.records.TournamentsRecord
import org.example.backend.error.ErreurMetier
import org.example.backend.repository.RegistrationRepository
import org.example.backend.repository.TeamRepository
import org.example.backend.repository.TournamentRepository
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Deux règles qui existaient sur le papier sans exister dans le code.
 *
 * **La fenêtre de check-in** n'était qu'un libellé d'affichage : rien n'empêchait
 * un check-in trois jours avant le départ ni après le coup d'envoi. Une fenêtre
 * qui n'ouvre ni ne ferme rien n'est pas une règle.
 *
 * **La liste d'attente ne fonctionnait que dans un sens** : on y basculait quand
 * le tournoi était complet, mais personne n'en sortait jamais quand une place se
 * libérait. Une place rendue était une place perdue.
 */
class RegistrationRulesTest {

    private val repo = mockk<RegistrationRepository>(relaxed = true)
    private val tournaments = mockk<TournamentRepository>(relaxed = true)
    private val teams = mockk<TeamRepository>(relaxed = true)
    private val droits = mockk<Droits>(relaxed = true)
    private val service = RegistrationService(repo, tournaments, teams, droits)

    private val tournoi = UUID.randomUUID()
    private val inscription = UUID.randomUUID()
    private val appelant = UUID.randomUUID()

    private fun tournoiAvecDepart(dansMinutes: Long, fenetre: Int? = 60) = TournamentsRecord(
        id = tournoi,
        name = "PA Major",
        startAt = OffsetDateTime.now().plusMinutes(dansMinutes),
        checkInWindowMinutes = fenetre,
    )

    // ------------------------------------------------------------------ //
    // Fenêtre de check-in
    // ------------------------------------------------------------------ //

    @Test
    fun `le check-in passe dans la fenetre`() {
        // Départ dans 30 min, fenêtre de 60 min : on est dedans.
        every { repo.findTournamentAndStatus(inscription) } returns (tournoi to RegistrationStatus.confirmed)
        every { tournaments.findById(tournoi) } returns tournoiAvecDepart(30)

        service.checkIn(inscription, appelant, estAdmin = true)

        verify { repo.updateStatus(inscription, RegistrationStatus.checked_in) }
    }

    @Test
    fun `le check-in est refuse avant l'ouverture`() {
        // Départ dans 5 heures, fenêtre de 60 min : trop tôt.
        every { repo.findTournamentAndStatus(inscription) } returns (tournoi to RegistrationStatus.confirmed)
        every { tournaments.findById(tournoi) } returns tournoiAvecDepart(300)

        val erreur = assertFailsWith<ErreurMetier.Conflit> {
            service.checkIn(inscription, appelant, estAdmin = true)
        }
        assertEquals("Le check-in ouvre 60 minutes avant le départ", erreur.message)
        verify(exactly = 0) { repo.updateStatus(any(), RegistrationStatus.checked_in) }
    }

    @Test
    fun `le check-in est clos apres le coup d'envoi`() {
        every { repo.findTournamentAndStatus(inscription) } returns (tournoi to RegistrationStatus.confirmed)
        every { tournaments.findById(tournoi) } returns tournoiAvecDepart(-10)

        val erreur = assertFailsWith<ErreurMetier.Conflit> {
            service.checkIn(inscription, appelant, estAdmin = true)
        }
        assertEquals("Le check-in est clos : le tournoi a commencé", erreur.message)
    }

    @Test
    fun `sans date de depart, le check-in ne peut pas s'ouvrir`() {
        // Refuser plutôt que d'inventer une date : une fenêtre calculée sur une
        // date absente autoriserait n'importe quand.
        every { repo.findTournamentAndStatus(inscription) } returns (tournoi to RegistrationStatus.confirmed)
        every { tournaments.findById(tournoi) } returns TournamentsRecord(id = tournoi, name = "Sans date")

        assertFailsWith<ErreurMetier.Conflit> { service.checkIn(inscription, appelant, estAdmin = true) }
    }

    @Test
    fun `seule une inscription confirmee peut faire son check-in`() {
        every { repo.findTournamentAndStatus(inscription) } returns (tournoi to RegistrationStatus.waitlist)
        every { tournaments.findById(tournoi) } returns tournoiAvecDepart(30)

        val erreur = assertFailsWith<ErreurMetier.Conflit> {
            service.checkIn(inscription, appelant, estAdmin = true)
        }
        assertEquals("Seule une inscription confirmée peut faire son check-in", erreur.message)
    }

    @Test
    fun `sans fenetre declaree, une heure s'applique`() {
        // Assez pour laisser arriver les équipes, assez court pour que la présence
        // signalée veuille encore dire quelque chose au coup d'envoi.
        every { repo.findTournamentAndStatus(inscription) } returns (tournoi to RegistrationStatus.confirmed)
        every { tournaments.findById(tournoi) } returns tournoiAvecDepart(90, fenetre = null)

        val erreur = assertFailsWith<ErreurMetier.Conflit> {
            service.checkIn(inscription, appelant, estAdmin = true)
        }
        assertEquals("Le check-in ouvre 60 minutes avant le départ", erreur.message)
    }

    // ------------------------------------------------------------------ //
    // Repêchage de la liste d'attente
    // ------------------------------------------------------------------ //

    @Test
    fun `le desistement d'un confirme repeche le premier en attente`() {
        val enAttente = UUID.randomUUID()
        every { repo.findTournamentAndStatus(inscription) } returns (tournoi to RegistrationStatus.confirmed)
        every { repo.findStatus(inscription) } returns RegistrationStatus.confirmed
        every { repo.findPremierEnAttente(tournoi) } returns enAttente

        service.reject(inscription, appelant, estAdmin = true)

        verify { repo.updateStatus(inscription, RegistrationStatus.withdrawn) }
        verify { repo.updateStatus(enAttente, RegistrationStatus.confirmed) }
    }

    @Test
    fun `le refus d'une inscription en attente ne repeche personne`() {
        // Elle n'occupait aucune place : rien n'est libéré. Repêcher ici ferait
        // grossir le tournoi au-delà de son plafond.
        every { repo.findTournamentAndStatus(inscription) } returns (tournoi to RegistrationStatus.waitlist)
        every { repo.findStatus(inscription) } returns RegistrationStatus.waitlist

        service.reject(inscription, appelant, estAdmin = true)

        verify(exactly = 0) { repo.findPremierEnAttente(any()) }
    }

    @Test
    fun `un desistement sans liste d'attente ne change rien d'autre`() {
        every { repo.findTournamentAndStatus(inscription) } returns (tournoi to RegistrationStatus.confirmed)
        every { repo.findStatus(inscription) } returns RegistrationStatus.confirmed
        every { repo.findPremierEnAttente(tournoi) } returns null

        service.reject(inscription, appelant, estAdmin = true)

        verify { repo.updateStatus(inscription, RegistrationStatus.withdrawn) }
        verify(exactly = 1) { repo.updateStatus(any(), any()) }
    }

    @Test
    fun `le depart d'un participant ayant fait son check-in libere aussi une place`() {
        // `checked_in` occupe une place autant que `confirmed` : l'oublier ferait
        // perdre définitivement la place d'un joueur qui se désiste au dernier moment.
        val enAttente = UUID.randomUUID()
        every { repo.findTournamentAndStatus(inscription) } returns (tournoi to RegistrationStatus.checked_in)
        every { repo.findStatus(inscription) } returns RegistrationStatus.confirmed
        every { repo.findPremierEnAttente(tournoi) } returns enAttente

        service.reject(inscription, appelant, estAdmin = true)

        verify { repo.updateStatus(enAttente, RegistrationStatus.confirmed) }
    }

    @Test
    fun `une inscription inconnue est signalee`() {
        every { repo.findTournamentAndStatus(inscription) } returns null

        assertFailsWith<ErreurMetier.Introuvable> { service.reject(inscription, appelant, estAdmin = true) }
    }
}
