package org.example.backend.repository

import org.example.backend.database.enums.TeamMemberRole
import org.example.backend.database.tables.references.TEAMS
import org.example.backend.database.tables.references.TEAM_MEMBERS
import org.example.backend.database.tables.references.USERS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.util.UUID

data class TeamRow(val id: UUID, val name: String, val tag: String?)
data class MemberRow(val userId: UUID, val pseudo: String, val role: TeamMemberRole)

@Repository
class TeamRepository(private val dsl: DSLContext) {

    fun create(name: String, tag: String?, creatorId: UUID): TeamRow {
        val id = dsl.insertInto(TEAMS)
            .set(TEAMS.NAME, name)
            .set(TEAMS.TAG, tag)
            .set(TEAMS.CREATED_BY, creatorId)
            .returning(TEAMS.ID)
            .fetchOne()!!
            .get(TEAMS.ID)!!
        dsl.insertInto(TEAM_MEMBERS)
            .set(TEAM_MEMBERS.TEAM_ID, id)
            .set(TEAM_MEMBERS.USER_ID, creatorId)
            .set(TEAM_MEMBERS.ROLE, TeamMemberRole.captain)
            .execute()
        return TeamRow(id, name, tag)
    }

    fun listMine(userId: UUID): List<TeamRow> = dsl.select(TEAMS.ID, TEAMS.NAME, TEAMS.TAG)
        .from(TEAMS)
        .join(TEAM_MEMBERS).on(TEAM_MEMBERS.TEAM_ID.eq(TEAMS.ID))
        .where(TEAM_MEMBERS.USER_ID.eq(userId))
        .orderBy(TEAMS.CREATED_AT.desc())
        .fetch { TeamRow(it.get(TEAMS.ID)!!, it.get(TEAMS.NAME)!!, it.get(TEAMS.TAG)) }

    fun find(teamId: UUID): TeamRow? = dsl.select(TEAMS.ID, TEAMS.NAME, TEAMS.TAG)
        .from(TEAMS)
        .where(TEAMS.ID.eq(teamId))
        .fetchOne { TeamRow(it.get(TEAMS.ID)!!, it.get(TEAMS.NAME)!!, it.get(TEAMS.TAG)) }

    fun members(teamId: UUID): List<MemberRow> = dsl.select(TEAM_MEMBERS.USER_ID, USERS.PSEUDO, TEAM_MEMBERS.ROLE)
        .from(TEAM_MEMBERS)
        .join(USERS).on(USERS.ID.eq(TEAM_MEMBERS.USER_ID))
        .where(TEAM_MEMBERS.TEAM_ID.eq(teamId))
        .orderBy(TEAM_MEMBERS.ROLE.asc(), USERS.PSEUDO.asc())
        .fetch {
            MemberRow(it.get(TEAM_MEMBERS.USER_ID)!!, it.get(USERS.PSEUDO)!!, it.get(TEAM_MEMBERS.ROLE)!!)
        }

    fun isCaptain(teamId: UUID, userId: UUID): Boolean = dsl.fetchExists(
        TEAM_MEMBERS,
        TEAM_MEMBERS.TEAM_ID.eq(teamId)
            .and(TEAM_MEMBERS.USER_ID.eq(userId))
            .and(TEAM_MEMBERS.ROLE.eq(TeamMemberRole.captain)),
    )

    fun findUserByPseudo(pseudo: String): UUID? = dsl.select(USERS.ID)
        .from(USERS)
        .where(USERS.PSEUDO.equalIgnoreCase(pseudo))
        .limit(1)
        .fetchOne(USERS.ID)

    fun isMember(teamId: UUID, userId: UUID): Boolean = dsl.fetchExists(
        TEAM_MEMBERS,
        TEAM_MEMBERS.TEAM_ID.eq(teamId).and(TEAM_MEMBERS.USER_ID.eq(userId)),
    )

    fun addMember(teamId: UUID, userId: UUID, role: TeamMemberRole) {
        dsl.insertInto(TEAM_MEMBERS)
            .set(TEAM_MEMBERS.TEAM_ID, teamId)
            .set(TEAM_MEMBERS.USER_ID, userId)
            .set(TEAM_MEMBERS.ROLE, role)
            .execute()
    }

    fun removeMember(teamId: UUID, userId: UUID): Boolean = dsl.deleteFrom(TEAM_MEMBERS)
        .where(TEAM_MEMBERS.TEAM_ID.eq(teamId).and(TEAM_MEMBERS.USER_ID.eq(userId)))
        .execute() == 1
}
