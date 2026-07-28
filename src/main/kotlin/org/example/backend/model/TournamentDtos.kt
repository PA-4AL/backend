package org.example.backend.model

/* DTOs alignés sur le contrat du frontend (frontend/src/api/types.ts). */

data class TournamentSummaryDto(
    val id: String,
    val name: String,
    val code: String,
    val format: String, // single_elim | double_elim | round_robin | swiss
    val participants: Int,
    val maxParticipants: Int,
    val status: String, // draft | registration | check_in | ongoing | finished | cancelled
    val scheduleLabel: String,
)

data class TeamRefDto(val code: String, val name: String, val color: String)

data class MatchRowDto(
    val id: String,
    val teamA: TeamRefDto,
    val teamB: TeamRefDto,
    val scoreA: Int?,
    val scoreB: Int?,
    val status: String, // live | done | scheduled
    val time: String? = null,
)

data class TournamentDetailDto(
    val id: String,
    val name: String,
    val code: String,
    val format: String,
    val participants: Int,
    val maxParticipants: Int,
    val status: String,
    val scheduleLabel: String,
    val description: String,
    val game: String,
    val teamSize: Int,
    val organizer: String,
    val bestOf: Int,
    val checkInWindow: String,
    val region: String,
    val visibility: String, // public | private
    val cashPrize: String,
    val currentPhaseLabel: String,
    val startedLabel: String,
    val matchesPlayed: Int,
    val matchesTotal: Int,
    val remainingTeams: List<TeamRefDto>,
    val currentMatches: List<MatchRowDto>,
)

/** Un jeu du tournoi avec son format de matchs (spec §4.1 : BO par round). */
data class GameSpec(
    val name: String,
    val bestOf: Int = 1, // 1 | 3 | 5
)

data class CreateTournamentRequest(
    val name: String,
    val description: String? = null,
    /** Multi-jeu (spec §6.2) : une phase par jeu, chacune avec son BO. */
    val games: List<GameSpec> = emptyList(),
    val format: String = "single_elim",
    val teamSize: Int = 1,
    val maxParticipants: Int? = null,
    val visibility: String = "public",
    /** Début du tournoi, format ISO local "2026-06-15T18:00" (heure de Paris). */
    val startAt: String? = null,
)

data class GenerateBracketRequest(
    val format: String? = null, // choisi au moment de la génération
)
