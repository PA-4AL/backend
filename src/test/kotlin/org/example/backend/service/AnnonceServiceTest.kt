package org.example.backend.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.example.backend.repository.AnnouncementRepository
import org.example.backend.repository.TournamentRepository
import org.example.backend.web.AnnonceWebSocket
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Annonces : rédaction des messages, destinataires, et robustesse.
 *
 * Le point le plus important est le dernier : **une annonce ne doit jamais faire
 * échouer l'action qui l'a provoquée**. Un score enregistré ne peut pas être
 * annulé parce qu'une WebSocket s'est fermée.
 */
class AnnonceServiceTest {

    private val repo = mockk<AnnouncementRepository>(relaxed = true)
    private val tournaments = mockk<TournamentRepository>(relaxed = true)
    private val diffusion = mockk<AnnonceWebSocket>(relaxed = true)
    private val service = AnnonceService(repo, tournaments, diffusion, ObjectMapper())

    private val tournoi = UUID.randomUUID()
    private val lecteur = UUID.randomUUID()

    private fun messagePublie(): String {
        val message = slot<String>()
        verify { repo.insert(tournoi, any(), capture(message)) }
        return message.captured
    }

    @Test
    fun `le message de fin de match nomme le vainqueur et le score dans l'ordre`() {
        // Le score est réordonné : « bat 2–1 » et non « bat 1–2 », qui se lirait
        // comme une défaite.
        service.finDeMatch(tournoi, "Nova", "Iron Vanguard", scoreA = 1, scoreB = 2)

        assertEquals("Fin du match : Nova bat Iron Vanguard 2–1.", messagePublie())
    }

    @Test
    fun `le message de debut de match nomme les deux equipes`() {
        service.debutDeMatch(tournoi, "Nova", "Iron Vanguard")

        assertEquals("Début du match : Nova contre Iron Vanguard.", messagePublie())
    }

    @Test
    fun `la fin de tournoi sans champion reste annoncable`() {
        // Round robin non départagé, ou données incomplètes : mieux vaut une annonce
        // sobre qu'aucune annonce.
        service.tournoiTermine(tournoi, null)

        assertEquals("Tournoi terminé.", messagePublie())
    }

    @Test
    fun `aucun message ne contient de balisage`() {
        // Le fil d'activité a déjà produit une XSS en concaténant du HTML avec des
        // noms d'utilisateurs. Un nom hostile doit ressortir tel quel, en texte.
        service.debutDeMatch(tournoi, "<img src=x onerror=alert(1)>", "Nova")

        val message = messagePublie()
        assertTrue(message.contains("<img"), "le nom doit être conservé intact")
        // Rien n'a été ajouté autour : pas de <b>, pas de <span>.
        assertTrue(
            message.startsWith("Début du match : ") && message.endsWith("."),
            "structure inattendue : $message",
        )
    }

    @Test
    fun `l'annonce est diffusee apres avoir ete enregistree`() {
        val id = UUID.randomUUID()
        every { repo.insert(any(), any(), any()) } returns id

        service.publier(tournoi, AnnonceService.FIN_MATCH, "Fin du match : A bat B 2–0.")

        val json = slot<String>()
        verify { diffusion.diffuser(tournoi, capture(json)) }
        assertTrue(json.captured.contains(id.toString()))
        assertTrue(json.captured.contains("match_end"))
    }

    @Test
    fun `une diffusion en echec ne fait pas echouer la publication`() {
        // Une annonce est d'abord une trace ; la notification en direct n'est qu'un
        // confort. Perdre l'historique parce qu'un client s'est déconnecté serait
        // absurde — et ferait échouer la saisie de score qui l'a déclenchée.
        every { repo.insert(any(), any(), any()) } returns UUID.randomUUID()
        every { diffusion.diffuser(any(), any()) } throws IllegalStateException("session fermée")

        service.publier(tournoi, AnnonceService.FIN_MATCH, "Fin du match.")

        verify { repo.insert(tournoi, AnnonceService.FIN_MATCH, "Fin du match.") }
    }

    @Test
    fun `un echec d'enregistrement n'empeche rien non plus`() {
        every { repo.insert(any(), any(), any()) } throws IllegalStateException("base indisponible")

        service.publier(tournoi, AnnonceService.FIN_MATCH, "Fin du match.")

        // Aucune diffusion d'une annonce qui n'existe pas.
        verify(exactly = 0) { diffusion.diffuser(any(), any()) }
    }

    @Test
    fun `la cloche agrege les tournois organises et joues`() {
        val organise = UUID.randomUUID()
        val joue = UUID.randomUUID()
        every { tournaments.idsOrganisesPar(lecteur) } returns setOf(organise)
        every { tournaments.idsAvecParticipationDe(lecteur) } returns setOf(joue)
        every { repo.listByTournaments(any(), any()) } returns emptyList()
        every { repo.findSeenAt(lecteur) } returns null
        every { repo.countDepuis(any(), any()) } returns 3

        val (_, nonLues) = service.pourUtilisateur(lecteur)

        val ensemble = slot<Collection<UUID>>()
        verify { repo.listByTournaments(capture(ensemble), any()) }
        assertEquals(setOf(organise, joue), ensemble.captured.toSet())
        assertEquals(3, nonLues)
    }

    @Test
    fun `jamais consulte compte tout comme non lu`() {
        // Un zéro à la première visite laisserait croire qu'il n'y a rien à lire.
        every { tournaments.idsOrganisesPar(lecteur) } returns setOf(tournoi)
        every { tournaments.idsAvecParticipationDe(lecteur) } returns emptySet()
        every { repo.findSeenAt(lecteur) } returns null
        every { repo.listByTournaments(any(), any()) } returns emptyList()
        every { repo.countDepuis(any(), null) } returns 7

        val (_, nonLues) = service.pourUtilisateur(lecteur)

        assertEquals(7, nonLues)
    }

    @Test
    fun `marquer lues enregistre une date`() {
        service.marquerLues(lecteur)

        val quand = slot<OffsetDateTime>()
        verify { repo.markSeen(lecteur, capture(quand)) }
        assertTrue(quand.captured.isBefore(OffsetDateTime.now().plusSeconds(5)))
    }
}
