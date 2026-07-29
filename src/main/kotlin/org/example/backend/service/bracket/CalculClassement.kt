package org.example.backend.service.bracket

import java.util.UUID

/** Bilan d'une inscription sur l'ensemble de ses matchs joués. */
data class LigneClassement(
    val registrationId: UUID,
    val joues: Int,
    val victoires: Int,
    val defaites: Int,
    val pour: Int,
    val contre: Int,
) {
    /** 3 points par victoire — pas de match nul en élimination directe. */
    val points: Int get() = victoires * 3
    val difference: Int get() = pour - contre
}

/** Un match joué, réduit à ce qui compte pour un classement. */
data class MatchJoue(val participantA: UUID, val participantB: UUID, val scoreA: Int, val scoreB: Int)

/**
 * Classement final d'un tournoi — **fonction pure**, sans base ni Spring.
 *
 * La règle de tri est **volontairement identique à celle du worker**
 * (`worker/src/tasks/export_excel.rs`, feuille « Classement ») : points, puis
 * différence de score. Deux classements calculés par deux services différents
 * qui ne s'accordent pas seraient pires que pas de classement du tout — un
 * organisateur verrait un vainqueur dans l'application et un autre dans le
 * fichier exporté.
 *
 * Le départage final se fait sur le **seed**, et non sur le nom : il est stable,
 * numérique, et ne dépend pas de la locale. En cas d'égalité parfaite, le mieux
 * classé à l'entrée reste devant.
 */
object CalculClassement {

    /**
     * @param participants toutes les inscriptions actives, dans l'ordre des seeds
     * @param matchs uniquement les matchs **joués** (deux participants, un score)
     * @return les inscriptions ordonnées, du 1er au dernier
     */
    fun calculer(participants: List<UUID>, matchs: List<MatchJoue>): List<UUID> =
        lignes(participants, matchs).map { it.registrationId }

    /** Le détail, utile pour l'affichage et pour comprendre un classement contesté. */
    fun lignes(participants: List<UUID>, matchs: List<MatchJoue>): List<LigneClassement> {
        val rangDeSeed = participants.withIndex().associate { (i, id) -> id to i }
        val bilans = participants.associateWith {
            LigneClassement(it, joues = 0, victoires = 0, defaites = 0, pour = 0, contre = 0)
        }.toMutableMap()

        matchs.forEach { m ->
            // Un match impliquant un participant inconnu (désinscrit depuis) est
            // ignoré plutôt que de créer une ligne fantôme dans le classement.
            val a = bilans[m.participantA] ?: return@forEach
            val b = bilans[m.participantB] ?: return@forEach
            val aGagne = m.scoreA > m.scoreB
            bilans[m.participantA] = a.copy(
                joues = a.joues + 1,
                victoires = a.victoires + if (aGagne) 1 else 0,
                defaites = a.defaites + if (aGagne) 0 else 1,
                pour = a.pour + m.scoreA,
                contre = a.contre + m.scoreB,
            )
            bilans[m.participantB] = b.copy(
                joues = b.joues + 1,
                victoires = b.victoires + if (aGagne) 0 else 1,
                defaites = b.defaites + if (aGagne) 1 else 0,
                pour = b.pour + m.scoreB,
                contre = b.contre + m.scoreA,
            )
        }

        return bilans.values.sortedWith(
            compareByDescending<LigneClassement> { it.points }
                .thenByDescending { it.difference }
                .thenBy { rangDeSeed[it.registrationId] ?: Int.MAX_VALUE },
        )
    }
}
