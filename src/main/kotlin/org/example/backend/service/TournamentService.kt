package org.example.backend.service

import org.example.backend.model.Match
import org.example.backend.model.Player
import org.example.backend.model.Tournament
import org.example.backend.repository.TournamentRepository
import org.springframework.stereotype.Service

@Service
class TournamentService(private val repository: TournamentRepository) {

    fun createTournament(tournament: Tournament): Tournament {
        return repository.saveTournament(tournament)
    }

    fun getAllTournaments(): List<Tournament> = repository.findAllTournaments()

    fun getTournament(id: Long): Tournament? = repository.findById(id)

    fun addPlayer(tournamentId: Long, player: Player): Player {
        return repository.savePlayer(player.copy(tournamentId = tournamentId))
    }

    fun createMatch(tournamentId: Long, match: Match): Match {
        return repository.saveMatch(match.copy(tournamentId = tournamentId))
    }
}