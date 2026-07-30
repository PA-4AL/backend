package org.example.backend.service

import org.example.backend.model.AnnonceDto
import org.example.backend.model.Display
import org.example.backend.repository.AnnonceRow
import org.example.backend.repository.AnnouncementRepository
import org.example.backend.repository.TournamentRepository
import org.example.backend.web.AnnonceWebSocket
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Annonces d'un tournoi : ce qui vient de se passer, dit une fois, à ceux que
 * cela concerne.
 *
 * **Qui reçoit quoi.** La cloche d'un utilisateur agrège les annonces des tournois
 * où il est **engagé** — organisateur ou participant. Un administrateur n'est donc
 * pas abonné à tout : il en recevrait des centaines et n'en lirait aucune. S'il
 * veut suivre un tournoi, il l'organise ou il ouvre sa page.
 *
 * **Le message est rédigé ici**, en texte simple, et stocké tel quel : une annonce
 * décrit un fait passé qui ne changera plus, le reconstruire à chaque lecture
 * n'apporterait rien. Aucun balisage — le fil d'activité a déjà produit une faille
 * XSS en concaténant du HTML avec des noms d'utilisateurs.
 */
@Service
class AnnonceService(
    private val repo: AnnouncementRepository,
    private val tournaments: TournamentRepository,
    private val diffusion: AnnonceWebSocket,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val DEBUT_MATCH = "match_start"
        const val FIN_MATCH = "match_end"
        const val TOUR_SUIVANT = "round_advance"
        const val ARBRE_GENERE = "bracket_generated"
        const val TOURNOI_TERMINE = "tournament_finished"
    }

    /**
     * Enregistre une annonce et la pousse aux clients connectés.
     *
     * L'échec de diffusion **n'annule pas** l'enregistrement : une annonce est
     * d'abord une trace, la notification en direct n'en est qu'un confort. Perdre
     * l'historique parce qu'une WebSocket s'est fermée serait absurde.
     */
    fun publier(tournamentId: UUID, kind: String, message: String) {
        val id = runCatching { repo.insert(tournamentId, kind, message) }
            .onFailure { log.error("Annonce non enregistrée pour le tournoi {}", tournamentId, it) }
            .getOrNull() ?: return

        runCatching {
            diffusion.diffuser(
                tournamentId,
                mapper.writeValueAsString(
                    mapOf(
                        "id" to id.toString(),
                        "tournamentId" to tournamentId.toString(),
                        "kind" to kind,
                        "message" to message,
                    ),
                ),
            )
        }.onFailure { log.debug("Diffusion en direct impossible", it) }
    }

    // ------------------------------------------------------------------ //
    // Rédaction des messages — un endroit unique, pour rester cohérent
    // ------------------------------------------------------------------ //

    fun arbreGenere(tournamentId: UUID, nbMatchs: Int) =
        publier(tournamentId, ARBRE_GENERE, "L'arbre est généré : $nbMatchs match(s) programmé(s).")

    fun debutDeMatch(tournamentId: UUID, equipeA: String, equipeB: String) =
        publier(tournamentId, DEBUT_MATCH, "Début du match : $equipeA contre $equipeB.")

    fun finDeMatch(tournamentId: UUID, vainqueur: String, perdant: String, scoreA: Int, scoreB: Int) = publier(
        tournamentId,
        FIN_MATCH,
        "Fin du match : $vainqueur bat $perdant ${maxOf(scoreA, scoreB)}–${minOf(scoreA, scoreB)}.",
    )

    fun tourSuivant(tournamentId: UUID, libelle: String) =
        publier(tournamentId, TOUR_SUIVANT, "Passage au tour suivant : $libelle.")

    fun tournoiTermine(tournamentId: UUID, champion: String?) = publier(
        tournamentId,
        TOURNOI_TERMINE,
        champion?.let { "Tournoi terminé : $it l'emporte." } ?: "Tournoi terminé.",
    )

    // ------------------------------------------------------------------ //
    // Lecture
    // ------------------------------------------------------------------ //

    /** Annonces d'un tournoi — mêmes données que le bracket, donc publiques. */
    fun duTournoi(tournamentId: UUID): List<AnnonceDto> = repo.listByTournament(tournamentId).map(::toDto)

    /**
     * Cloche d'un utilisateur : les annonces des tournois où il est engagé, et le
     * nombre arrivé depuis sa dernière consultation.
     */
    fun pourUtilisateur(userId: UUID): Pair<List<AnnonceDto>, Int> {
        val concernes = tournaments.idsOrganisesPar(userId) + tournaments.idsAvecParticipationDe(userId)
        val annonces = repo.listByTournaments(concernes).map(::toDto)
        val nonLues = repo.countDepuis(concernes, repo.findSeenAt(userId))
        return annonces to nonLues
    }

    /** Marque tout comme lu à l'instant. */
    fun marquerLues(userId: UUID) = repo.markSeen(userId, OffsetDateTime.now())

    private fun toDto(row: AnnonceRow) = AnnonceDto(
        id = row.id.toString(),
        tournamentId = row.tournamentId.toString(),
        tournamentName = row.tournamentName,
        kind = row.kind,
        message = row.message,
        time = Display.relativeTime(row.createdAt),
    )
}
