package org.example.backend.service

import org.example.backend.database.enums.TournamentStatus
import org.example.backend.model.GameAccountDto
import org.example.backend.model.ProfileDto
import org.example.backend.model.ProfileStatsDto
import org.example.backend.model.TournamentHistoryDto
import org.example.backend.repository.ProfileRepository
import org.example.backend.repository.RegistrationRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class ProfileService(private val repo: ProfileRepository, private val registrations: RegistrationRepository) {

    fun profile(keycloakId: String, pseudo: String, email: String?): ProfileDto {
        val userId = registrations.upsertUserByKeycloak(keycloakId, pseudo, email)
        val info = repo.userInfo(userId)
        val history = repo.history(userId)

        val matchesPlayed = history.sumOf { it.matchesPlayed }
        val matchesWon = history.sumOf { it.matchesWon }

        return ProfileDto(
            pseudo = info?.pseudo ?: pseudo,
            email = email,
            avatarUrl = info?.avatarUrl,
            gameAccounts = repo.listGameAccounts(userId).map {
                GameAccountDto(it.id.toString(), it.game, it.identifier)
            },
            history = history.map {
                TournamentHistoryDto(
                    tournamentId = it.tournamentId.toString(),
                    name = it.tournamentName,
                    game = it.game,
                    status = it.tournamentStatus.literal,
                    result = when {
                        it.wonFinal -> "champion"
                        it.tournamentStatus == TournamentStatus.finished -> "eliminated"
                        it.tournamentStatus == TournamentStatus.ongoing -> "in_progress"
                        else -> "registered"
                    },
                    matchesWon = it.matchesWon,
                    matchesPlayed = it.matchesPlayed,
                )
            },
            stats = ProfileStatsDto(
                tournamentsPlayed = history.size,
                matchesPlayed = matchesPlayed,
                matchesWon = matchesWon,
                winrate = if (matchesPlayed > 0) (matchesWon * 100) / matchesPlayed else 0,
            ),
        )
    }

    fun updateProfile(
        keycloakId: String,
        pseudo: String,
        email: String?,
        newPseudo: String?,
        avatarUrl: String?,
    ): ProfileDto {
        val userId = registrations.upsertUserByKeycloak(keycloakId, pseudo, email)
        val cleanPseudo = newPseudo?.trim()
        if (cleanPseudo != null && cleanPseudo.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Le pseudo ne peut pas être vide")
        }
        if (avatarUrl != null && avatarUrl.length > 500_000) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Image trop lourde (500 Ko max)")
        }
        repo.updateProfile(userId, cleanPseudo, avatarUrl)
        return profile(keycloakId, pseudo, email)
    }

    fun addGameAccount(
        keycloakId: String,
        pseudo: String,
        email: String?,
        game: String,
        identifier: String,
    ): GameAccountDto {
        if (game.isBlank() || identifier.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Jeu et identifiant sont obligatoires")
        }
        val userId = registrations.upsertUserByKeycloak(keycloakId, pseudo, email)
        val row = repo.addGameAccount(userId, game.trim(), identifier.trim())
        return GameAccountDto(row.id.toString(), row.game, row.identifier)
    }

    fun deleteGameAccount(keycloakId: String, pseudo: String, email: String?, accountId: UUID) {
        val userId = registrations.upsertUserByKeycloak(keycloakId, pseudo, email)
        if (!repo.deleteGameAccount(userId, accountId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Compte de jeu introuvable")
        }
    }
}
