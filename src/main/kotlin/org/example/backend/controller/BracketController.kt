package org.example.backend.controller

import org.example.backend.model.BracketDto
import org.example.backend.model.GenerateBracketRequest
import org.example.backend.model.ScoreRequest
import org.example.backend.service.BracketService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class BracketController(private val service: BracketService) {

    /** Consultation publique du bracket (spec : Visiteur). */
    @GetMapping("/api/tournaments/{id}/bracket")
    fun get(@PathVariable id: UUID): BracketDto = service.getBracket(id)

    /** Génération / re-génération de l'arbre — authentifié, format au choix. */
    @PostMapping("/api/tournaments/{id}/bracket/generate")
    fun generate(@PathVariable id: UUID, @RequestBody(required = false) req: GenerateBracketRequest?): BracketDto =
        service.generate(id, req?.format)

    /** Saisie du score d'un match, avec propagation du vainqueur. */
    @PostMapping("/api/matches/{matchId}/score")
    fun reportScore(@PathVariable matchId: UUID, @RequestBody score: ScoreRequest): BracketDto =
        service.reportScore(matchId, score.scoreA, score.scoreB)
}
