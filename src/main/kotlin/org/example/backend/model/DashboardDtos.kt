package org.example.backend.model

/* DTOs du tableau de bord (frontend/src/api/types.ts). */

data class DashboardKpisDto(
    val activeTournaments: Int,
    val activeTournamentsDelta: String,
    val liveMatches: Int,
    val participants: Int,
    val participantsDelta: String,
    val pendingValidations: Int,
)

data class ActivityItemDto(
    val id: String,
    val kind: String,   // win | live | registration | dispute | finished
    val html: String,
    val time: String,
)
