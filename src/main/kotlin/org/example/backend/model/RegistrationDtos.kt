package org.example.backend.model

/* DTOs des inscriptions (pages Participants / Validations du frontend). */

data class ParticipantDto(
    val registrationId: String,
    val name: String,
    val status: String, // pending | confirmed | waitlist | checked_in | withdrawn | disqualified
    val seed: Int?,
    val registeredLabel: String,
)

data class PendingRegistrationDto(
    val registrationId: String,
    val participant: String,
    val tournamentId: String,
    val tournamentName: String,
    val status: String, // pending | waitlist
    val registeredLabel: String,
)
