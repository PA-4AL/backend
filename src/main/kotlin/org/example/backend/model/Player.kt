package org.example.backend.model

data class Player(
    val id: Long? = null,
    val tournamentId: Long,
    val name: String,
    val score: Int = 0
)
