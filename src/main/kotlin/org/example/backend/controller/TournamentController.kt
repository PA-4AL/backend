package org.example.backend.controller

import org.example.backend.model.Match
import org.example.backend.model.Player
import org.example.backend.model.Tournament
import org.example.backend.service.TournamentService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.net.URI

@RestController
@RequestMapping("/api/tournaments")
class TournamentController(private val tournamentService: TournamentService) {

    @PostMapping
    fun createTournament(@RequestBody tournament: Tournament): ResponseEntity<Tournament> {
        val saved = tournamentService.createTournament(tournament)
        return ResponseEntity.created(URI.create("/api/tournaments/${saved.id}")).body(saved)
    }

    @GetMapping
    fun getAllTournaments(): List<Tournament> = tournamentService.getAllTournaments()

    @GetMapping("/{id}")
    fun getTournament(@PathVariable id: Long): ResponseEntity<Tournament> {
        return tournamentService.getTournament(id)?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()
    }

    @PostMapping("/{tournamentId}/players")
    fun addPlayer(@PathVariable tournamentId: Long, @RequestBody player: Player): ResponseEntity<Player> {
        val saved = tournamentService.addPlayer(tournamentId, player)
        return ResponseEntity.ok(saved)
    }

    @PostMapping("/{tournamentId}/matches")
    fun createMatch(@PathVariable tournamentId: Long, @RequestBody match: Match): ResponseEntity<Match> {
        val saved = tournamentService.createMatch(tournamentId, match)
        return ResponseEntity.ok(saved)
    }
}
