package org.example.backend.service

import org.example.backend.database.enums.PhaseType
import org.example.backend.database.enums.TournamentVisibility
import org.example.backend.database.tables.records.TournamentsRecord
import org.example.backend.model.CreateTournamentRequest
import org.example.backend.model.Display
import org.example.backend.model.TeamRefDto
import org.example.backend.model.TournamentDetailDto
import org.example.backend.model.TournamentSummaryDto
import org.example.backend.repository.TournamentRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

@Service
class TournamentService(private val repository: TournamentRepository, private val bracketService: BracketService) {

    private val zone = ZoneId.of("Europe/Paris")
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFmt = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.FRENCH)
    private val dayFmt = DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH)

    fun list(): List<TournamentSummaryDto> = repository.findAll().map { (t, participants) -> t.toSummary(participants) }

    fun get(id: UUID): TournamentDetailDto? {
        val t = repository.findById(id) ?: return null
        val phases = repository.findPhases(id)
        val phase = phases.firstOrNull()
        val participants = repository.countParticipants(id)
        val (played, total) = repository.countMatches(id)
        val active = repository.findActiveParticipants(id)
        val (currentRoundLabel, currentMatches) = bracketService.currentRoundMatches(id)

        val remainingTeams = active.mapIndexed { i, p ->
            TeamRefDto(
                code = Display.initials(p.displayName),
                name = p.displayName,
                color = Display.colorFor(i),
            )
        }

        return TournamentDetailDto(
            id = t.id.toString(),
            name = t.name!!,
            code = code(t),
            format = (phase?.type ?: PhaseType.single_elim).literal,
            participants = participants,
            maxParticipants = t.maxParticipants ?: participants,
            status = t.status!!.literal,
            scheduleLabel = scheduleLabel(t),
            description = t.description ?: "",
            game = phases.joinToString(" · ") { "${it.game} (BO${it.defaultBo})" }.ifEmpty { "—" },
            teamSize = t.teamSize ?: 1,
            organizer = repository.findOwnerPseudo(id) ?: "—",
            bestOf = phase?.defaultBo ?: 1,
            checkInWindow = checkInWindow(t),
            region = "—",
            visibility = t.visibility!!.literal,
            cashPrize = "—",
            currentPhaseLabel = currentRoundLabel ?: phaseLabel(phase?.type),
            startedLabel = t.startAt?.let { timeFmt.format(it.atZoneSameInstant(zone)) } ?: "—",
            matchesPlayed = played,
            matchesTotal = total,
            remainingTeams = remainingTeams,
            currentMatches = currentMatches,
        )
    }

    @Transactional
    fun create(req: CreateTournamentRequest, keycloakId: String, pseudo: String, email: String?): TournamentSummaryDto {
        val games = req.games
            .map { it.name.trim() to it.bestOf.coerceIn(1, 5) }
            .filter { it.first.isNotEmpty() }
        if (games.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Au moins un jeu est requis")
        }
        val format = PhaseType.entries.firstOrNull { it.literal == req.format } ?: PhaseType.single_elim
        val visibility = TournamentVisibility.entries.firstOrNull { it.literal == req.visibility }
            ?: TournamentVisibility.public
        val startAt = req.startAt?.let {
            try {
                java.time.LocalDateTime.parse(it).atZone(zone).toOffsetDateTime()
            } catch (_: Exception) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Date de début invalide")
            }
        }
        val t = repository.create(
            name = req.name,
            description = req.description,
            games = games,
            format = format,
            teamSize = req.teamSize,
            maxParticipants = req.maxParticipants,
            visibility = visibility,
            startAt = startAt,
            ownerKeycloakId = keycloakId,
            ownerPseudo = pseudo,
            ownerEmail = email,
        )
        return t.toSummary(0)
    }

    private fun TournamentsRecord.toSummary(participants: Int): TournamentSummaryDto {
        val phase = repository.findFirstPhase(this.id!!)
        return TournamentSummaryDto(
            id = id.toString(),
            name = name!!,
            code = code(this),
            format = (phase?.type ?: PhaseType.single_elim).literal,
            participants = participants,
            maxParticipants = maxParticipants ?: participants,
            status = status!!.literal,
            scheduleLabel = scheduleLabel(this),
        )
    }

    private fun code(t: TournamentsRecord): String = "#" + t.id.toString().substringBefore('-').take(6).uppercase()

    private fun scheduleLabel(t: TournamentsRecord): String {
        val start = t.startAt?.atZoneSameInstant(zone) ?: return "à planifier"
        return when (t.status!!.literal) {
            "ongoing" -> "démarré ${timeFmt.format(start)}"
            "finished", "cancelled" -> dayFmt.format(start)
            else -> dateFmt.format(start)
        }
    }

    private fun checkInWindow(t: TournamentsRecord): String {
        val minutes = t.checkInWindowMinutes ?: return "—"
        val start = t.startAt ?: return "—"
        val open: OffsetDateTime = start.minusMinutes(minutes.toLong())
        return "${timeFmt.format(open.atZoneSameInstant(zone))} — ${timeFmt.format(start.atZoneSameInstant(zone))}"
    }

    private fun phaseLabel(type: PhaseType?): String = when (type) {
        PhaseType.double_elim -> "Élimination double"
        PhaseType.round_robin -> "Poules"
        PhaseType.swiss -> "Système suisse"
        else -> "Élimination simple"
    }
}
