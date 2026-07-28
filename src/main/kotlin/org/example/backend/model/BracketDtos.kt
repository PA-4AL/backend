package org.example.backend.model

import java.time.Duration
import java.time.OffsetDateTime

/* DTOs du bracket, alignés sur frontend/src/api/types.ts (BracketData). */

data class SlotDto(
    val tbd: Boolean? = null,
    val name: String,
    val seed: Int? = null,
    val code: String? = null,
    val color: String? = null,
    val score: Int? = null,
    val win: Boolean? = null,
)

data class BracketMatchDto(
    val id: String, // code d'affichage : QF1, SF2, F1…
    val matchId: String, // UUID réel (saisie de score)
    val status: String, // done | live | scheduled | pending
    val time: String? = null,
    val a: SlotDto,
    val b: SlotDto,
)

data class BracketRoundDto(val label: String, val matches: List<BracketMatchDto>)

data class BracketDto(val rounds: List<BracketRoundDto>, val champion: String? = null)

data class ScoreRequest(val scoreA: Int, val scoreB: Int)

/** Helpers d'affichage partagés (mêmes conventions que le frontend). */
object Display {
    val palette = listOf(
        "#1437D9",
        "#FF5C28",
        "#00A854",
        "#7c3aed",
        "#0891b2",
        "#db2777",
        "#ca8a04",
        "#16a34a",
    )

    fun colorFor(index: Int): String = palette[index % palette.size]

    fun initials(name: String): String = name.split(Regex("[\\s._-]+"))
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .take(2)
        .joinToString("")
        .ifEmpty { "?" }

    /** Libellé + code court d'un round selon sa distance à la finale. */
    fun roundLabel(round: Int, totalRounds: Int): Pair<String, String> = when (totalRounds - round) {
        0 -> "Finale" to "F"
        1 -> "Demi-finales" to "SF"
        2 -> "Quarts de finale" to "QF"
        else -> {
            val participants = 1 shl (totalRounds - round + 1)
            "1/${participants / 2} de finale" to "R$participants"
        }
    }

    /**
     * Ancienneté en clair : « À l'instant », « Il y a 12 min », « Il y a 3 h »…
     * `now` est paramétrable pour rendre la fonction testable.
     */
    fun relativeTime(at: OffsetDateTime, now: OffsetDateTime = OffsetDateTime.now()): String {
        val d = Duration.between(at, now)
        return when {
            d.toMinutes() < 1 -> "À l'instant"
            d.toMinutes() < 60 -> "Il y a ${d.toMinutes()} min"
            d.toHours() < 24 -> "Il y a ${d.toHours()} h"
            else -> "Il y a ${d.toDays()} j"
        }
    }
}
