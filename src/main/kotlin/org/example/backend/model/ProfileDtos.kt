package org.example.backend.model

/* DTOs du profil utilisateur (spec §4.5 : profil, historique, stats). */

data class GameAccountDto(
    val id: String,
    val game: String,
    val identifier: String,
)

data class TournamentHistoryDto(
    val tournamentId: String,
    val name: String,
    val game: String,
    val status: String,          // statut du tournoi
    val result: String,          // champion | in_progress | eliminated | registered
    val matchesWon: Int,
    val matchesPlayed: Int,
)

data class ProfileStatsDto(
    val tournamentsPlayed: Int,
    val matchesPlayed: Int,
    val matchesWon: Int,
    val winrate: Int,            // en %
)

data class ProfileDto(
    val pseudo: String,
    val email: String?,
    val avatarUrl: String?,
    val gameAccounts: List<GameAccountDto>,
    val history: List<TournamentHistoryDto>,
    val stats: ProfileStatsDto,
)

data class AddGameAccountRequest(
    val game: String,
    val identifier: String,
)

data class UpdateProfileRequest(
    val pseudo: String? = null,
    /** Data-URL (image redimensionnée côté client) ou "" pour retirer la photo. */
    val avatarUrl: String? = null,
)
