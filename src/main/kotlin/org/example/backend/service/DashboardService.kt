package org.example.backend.service

import org.example.backend.database.enums.TournamentStatus
import org.example.backend.model.ActivityItemDto
import org.example.backend.model.DashboardKpisDto
import org.example.backend.model.Display
import org.example.backend.repository.DashboardRepository
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

/** Événement du fil d'activité, avant mise en forme finale. */
private data class Event(
    val id: String,
    val kind: String,
    val sujet: String,
    val action: String,
    val complement: String?,
    val at: OffsetDateTime,
)

@Service
class DashboardService(private val repo: DashboardRepository) {

    fun kpis(): DashboardKpisDto {
        val now = OffsetDateTime.now()

        return DashboardKpisDto(
            activeTournaments = repo.countActiveTournaments(),
            activeTournamentsDelta = "+${repo.countTournamentsCreatedSince(now.minusDays(7))} cette semaine",
            liveMatches = repo.countLiveMatches(),
            participants = repo.countActiveRegistrations(),
            participantsDelta = "+${repo.countRegistrationsCreatedSince(now.minusDays(30))} ce mois",
            pendingValidations = repo.countPendingRegistrations(),
        )
    }

    /** Fil d'activité réel : dernières inscriptions et derniers tournois créés. */
    fun activity(): List<ActivityItemDto> {
        // Aucun balisage ici : le serveur transmet des données, le frontend décide
        // de leur présentation. C'est ce qui rend l'injection impossible plutôt
        // que simplement difficile.
        val registrations = repo.recentRegistrations(5).map { row ->
            Event(
                id = "reg-${row.id}",
                kind = "registration",
                sujet = row.participantName ?: "Un participant",
                action = "s'est inscrit sur",
                complement = row.tournamentName,
                at = row.createdAt,
            )
        }

        val tournaments = repo.recentTournaments(5).map { row ->
            val finished = row.status == TournamentStatus.finished
            Event(
                id = "trn-${row.id}",
                kind = if (finished) "finished" else "live",
                sujet = row.name,
                action = if (finished) "est terminé." else "a été créé.",
                complement = null,
                at = row.createdAt,
            )
        }

        return (registrations + tournaments)
            .sortedByDescending { it.at }
            .take(6)
            .map {
                ActivityItemDto(
                    id = it.id,
                    kind = it.kind,
                    sujet = it.sujet,
                    action = it.action,
                    complement = it.complement,
                    time = Display.relativeTime(it.at),
                )
            }
    }
}
