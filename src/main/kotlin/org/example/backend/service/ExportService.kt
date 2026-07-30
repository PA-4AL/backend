package org.example.backend.service

import org.example.backend.database.enums.MatchStatus
import org.example.backend.error.ErreurMetier
import org.example.backend.model.JobDto
import org.example.backend.model.TournamentFileType
import org.example.backend.repository.BracketRepository
import org.example.backend.repository.RegistrationRepository
import org.example.backend.repository.TournamentRepository
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Export d'un tournoi en `.xlsx`, délégué au worker Rust.
 *
 * L'assemblage des données vit ici et non dans [JobService] : celui-ci ne connaît
 * que la file de traitements, pas le modèle des tournois. Il reçoit un payload
 * déjà constitué, exactement comme pour l'import.
 *
 * Le contrat est celui de `worker/src/tasks/export_excel.rs` — clés en
 * **snake_case**, `teams[{name, players}]` et
 * `matches[{round, team_a, team_b, score_a, score_b, status}]`.
 *
 * Limite connue : le fichier revient encodé en base64 dans le message Pub/Sub,
 * plafonné à 10 Mo. Suffisant pour un tournoi, pas pour un export massif.
 */
@Service
class ExportService(
    private val tournaments: TournamentRepository,
    private val bracket: BracketRepository,
    private val inscriptions: RegistrationRepository,
    private val jobs: JobService,
    private val droits: Droits,
) {

    /**
     * Soumet l'export et rend le job aussitôt : le traitement est asynchrone,
     * l'appelant suit son avancement par `GET /jobs/{id}` puis récupère le
     * fichier dans `result.file_base64`.
     */
    fun soumettre(tournamentId: UUID, createdBy: UUID, estAdmin: Boolean): JobDto {
        val tournoi = tournaments.findById(tournamentId)
            ?: throw ErreurMetier.Introuvable("Tournoi introuvable")
        // Un export contient les pseudos de tous les joueurs : ce n'est pas une
        // donnée publique, même si le bracket l'est.
        droits.exigerOrganisateur(tournamentId, createdBy, estAdmin)
        val phase = tournaments.findFirstPhase(tournamentId)
            ?: throw ErreurMetier.Conflit("Le tournoi n'a aucune phase")

        val participants = tournaments.findActiveParticipants(tournamentId)
        if (participants.isEmpty()) {
            throw ErreurMetier.Conflit("Aucun participant confirmé à exporter")
        }

        val noms = bracket.findRegistrationInfo(tournamentId)
        val scores = bracket.findScores(phase.id!!)

        // Un match dont un slot est encore vide (bye, ou vainqueur inconnu) n'a
        // rien à dire dans un tableur : on l'omet plutôt que d'écrire « TBD ».
        val matchs = bracket.findPhaseMatches(phase.id!!).mapNotNull { m ->
            val a = m.participant1Id?.let { noms[it]?.displayName } ?: return@mapNotNull null
            val b = m.participant2Id?.let { noms[it]?.displayName } ?: return@mapNotNull null
            val score = scores[m.id]
            mapOf(
                "round" to (m.round ?: 1),
                "team_a" to a,
                "team_b" to b,
                "score_a" to score?.first,
                "score_b" to score?.second,
                "status" to statutPourLeWorker(m.status),
            )
        }

        // Les joueurs de chaque inscription : membres de l'équipe, ou le joueur
        // seul en tournoi solo. Sans eux, le classeur ne contenait qu'une colonne
        // « Équipe », ce qui n'a pas grand intérêt pour un organisateur.
        val joueurs = tournaments.findParticipantPlayers(tournamentId)
        val classements = inscriptions.findFinalRanks(tournamentId)

        // Les mieux classés d'abord : un tableur se lit du haut vers le bas. Les
        // non classés (tournoi non terminé) suivent, dans l'ordre des seeds.
        val ordonnes = participants.sortedBy { classements[it.registrationId] ?: Int.MAX_VALUE }

        return jobs.submitTournamentExport(
            tournamentType = typeDeFichier(tournoi.teamSize).literal,
            tournamentName = tournoi.name,
            teams = ordonnes.map { p ->
                mapOf(
                    "name" to p.displayName,
                    // `classement` est ignoré par le worker aujourd'hui, mais fait
                    // partie du contrat de données : il évite de recalculer côté
                    // fichier ce que l'application a déjà arrêté.
                    "classement" to classements[p.registrationId],
                    "players" to (joueurs[p.registrationId] ?: emptyList()).map { j ->
                        // Le rang en jeu vient du fichier importé et est désormais
                        // persisté ; vide s'il n'a jamais été renseigné.
                        mapOf("username" to j.pseudo, "rank" to (j.rang ?: ""))
                    },
                )
            },
            matches = matchs,
            createdBy = createdBy,
        )
    }

    /**
     * Le worker ne connaît que deux gabarits de fichier. La taille d'équipe est
     * ce qui les distingue : 11 joueurs relèvent du modèle football, tout le
     * reste du modèle esport.
     */
    private fun typeDeFichier(teamSize: Int?): TournamentFileType =
        if (teamSize == 11) TournamentFileType.FOOTBALL_11V11 else TournamentFileType.ESPORT_5V5

    /**
     * Le worker n'attend que `pending` | `in_progress` | `finished`. Les statuts
     * `disputed` et `forfeited` n'ont pas d'équivalent : les rendre `finished`
     * serait mentir sur un score, ils restent donc en attente.
     */
    private fun statutPourLeWorker(statut: MatchStatus?): String = when (statut) {
        MatchStatus.finished -> "finished"
        MatchStatus.ongoing -> "in_progress"
        else -> "pending"
    }
}
