package org.example.backend.model

/* DTOs des équipes (spec §4.3 : équipes persistantes, roster, capitaine). */

data class TeamMemberDto(
    val userId: String,
    val pseudo: String,
    val role: String, // captain | member | substitute
)

data class TeamDto(val id: String, val name: String, val tag: String?, val members: List<TeamMemberDto>)

data class CreateTeamRequest(val name: String, val tag: String? = null)

data class AddMemberRequest(val pseudo: String, val role: String = "member")
