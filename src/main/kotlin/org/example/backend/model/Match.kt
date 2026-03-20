package org.example.backend.model

data class Match(
    val id: Long? = null,
    val tournamentId: Long,
    val player1Id: Long,
    val player2Id: Long,
    val winnerId: Long? = null,
    val scorePlayer1: Int = 0,
    val scorePlayer2: Int = 0
)
