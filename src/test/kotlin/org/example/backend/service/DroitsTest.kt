package org.example.backend.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.example.backend.error.ErreurMetier
import org.example.backend.repository.TournamentRepository
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Autorisation **par objet**.
 *
 * Le rôle porté par le jeton dit qu'on est organisateur *de quelque chose*, pas de
 * ce tournoi-ci. En production, seules 2 routes sur 29 étaient protégées : tout
 * compte authentifié pouvait régénérer l'arbre d'un tournoi qui n'était pas le
 * sien, y saisir des scores ou en déplacer les équipes.
 */
class DroitsTest {

    private val tournaments = mockk<TournamentRepository>()
    private val droits = Droits(tournaments)

    private val tournoi = UUID.randomUUID()
    private val appelant = UUID.randomUUID()

    @Test
    fun `un organisateur du tournoi passe`() {
        every { tournaments.estOrganisateur(tournoi, appelant) } returns true

        droits.exigerOrganisateur(tournoi, appelant, estAdmin = false)
    }

    @Test
    fun `un tiers authentifie est refuse`() {
        every { tournaments.estOrganisateur(tournoi, appelant) } returns false

        val erreur = assertFailsWith<ErreurMetier.NonAutorise> {
            droits.exigerOrganisateur(tournoi, appelant, estAdmin = false)
        }
        assertEquals("Vous n'êtes pas organisateur de ce tournoi", erreur.message)
    }

    @Test
    fun `un administrateur passe sans consulter la base`() {
        // Modération globale (spec §4.5) : l'admin doit pouvoir intervenir sur un
        // tournoi abandonné. Aucune lecture n'est faite, d'où un mock non stubé :
        // si le code interrogeait la base, le test échouerait.
        droits.exigerOrganisateur(tournoi, appelant, estAdmin = true)

        verify(exactly = 0) { tournaments.estOrganisateur(any(), any()) }
    }

    @Test
    fun `une phase orpheline est un tournoi introuvable`() {
        // Le contrôle des matchs remonte au tournoi par la phase. Sans tournoi,
        // il ne faut ni autoriser par défaut, ni prétendre à un refus de droits :
        // la ressource n'existe pas.
        assertFailsWith<ErreurMetier.Introuvable> {
            droits.exigerOrganisateurDePhase(null, appelant, estAdmin = false)
        }
    }

    @Test
    fun `le controle de phase applique la meme regle que celui du tournoi`() {
        every { tournaments.estOrganisateur(tournoi, appelant) } returns false

        assertFailsWith<ErreurMetier.NonAutorise> {
            droits.exigerOrganisateurDePhase(tournoi, appelant, estAdmin = false)
        }
    }
}
