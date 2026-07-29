package org.example.backend.model

/* DTOs des inscriptions (pages Participants / Validations du frontend). */

data class ParticipantDto(
    val registrationId: String,
    val name: String,
    val status: String, // pending | confirmed | waitlist | checked_in | withdrawn | disqualified
    val seed: Int?,
    val registeredLabel: String,
    /**
     * Classement final dans le tournoi, 1 = vainqueur. `null` tant que le tournoi
     * n'est pas terminé — figé par `BracketService` à la saisie du dernier score.
     */
    val finalRank: Int? = null,
)

data class PendingRegistrationDto(
    val registrationId: String,
    val participant: String,
    val tournamentId: String,
    val tournamentName: String,
    val status: String, // pending | waitlist
    val registeredLabel: String,
)
