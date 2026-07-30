package org.example.backend.model

/* DTOs des équipes (spec §4.3 : équipes persistantes, roster, capitaine). */

data class TeamMemberDto(
    val userId: String,
    val pseudo: String,
    val role: String, // captain | member | substitute
)

data class TeamDto(
    val id: String,
    val name: String,
    val tag: String?,
    val members: List<TeamMemberDto>,
    /**
     * L'utilisateur qui consulte est-il capitaine de cette équipe ?
     *
     * Calculé par le serveur, qui connaît l'identifiant interne de l'appelant. Le
     * frontend le déduisait du **pseudo** (`m.pseudo === user.pseudo`), ce qui se
     * trompait dès qu'un homonyme existait, et cessait de fonctionner après un
     * changement de pseudo. Le pseudo n'est pas une identité.
     */
    val viewerIsCaptain: Boolean = false,
)

data class CreateTeamRequest(val name: String, val tag: String? = null)

data class AddMemberRequest(val pseudo: String, val role: String = "member")
