package org.example.backend.model

data class Tournament(
    val id: Long? = null,
    val name: String,
    val startDate: String,
    val endDate: String,
    val maxPlayers: Int,
    val status: TournamentStatus = TournamentStatus.PENDING
)

enum class TournamentStatus { PENDING, ONGOING, FINISHED }

