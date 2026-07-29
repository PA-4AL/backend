package org.example.backend.service.bracket

import org.example.backend.database.enums.BracketType

/**
 * Un match tel que **planifié**, avant toute écriture en base.
 *
 * Les liens entre matchs sont exprimés par des clés locales (`WB-2-1`) plutôt que
 * par des identifiants : le générateur ne connaît pas la base, c'est le service
 * qui traduit les clés en UUID après insertion.
 *
 * @param vainqueurVers clé du match où va le vainqueur (`null` = fin de parcours)
 * @param perdantVers clé du match où va le perdant (élimination double uniquement)
 * @param seedA position de seed placée d'emblée dans le slot 1 (premier tour)
 */
data class MatchPlanifie(
    val cle: String,
    val round: Int,
    val position: Int,
    val bracket: BracketType,
    val vainqueurVers: String? = null,
    val perdantVers: String? = null,
    val seedA: Int? = null,
    val seedB: Int? = null,
)

/**
 * Calcul de la structure d'un arbre de tournoi — **fonctions pures**.
 *
 * Aucune dépendance : ni base, ni Spring, ni HTTP. C'est ce qui permet de tester
 * les invariants de chaque format (nombre de matchs, cohérence du chaînage,
 * placement des seeds) sans la moindre infrastructure.
 *
 * Formats couverts : élimination simple, élimination double, round robin.
 * Le **système suisse** n'y figure pas : ses appariements dépendent du classement
 * après chaque tour, il ne peut donc pas être pré-calculé (voir
 * `docs/adr/0008-formats-de-bracket.md`).
 */
object GenerateurBracket {

    /**
     * Ordre des seeds dans les slots du premier tour : 1 affronte le plus bas, le
     * 2 est à l'opposé du tableau. Pour 8 : [1, 8, 4, 5, 2, 7, 3, 6].
     */
    fun ordreDesSeeds(taille: Int): List<Int> {
        var slots = listOf(1)
        while (slots.size < taille) {
            val n = slots.size * 2
            slots = slots.flatMap { listOf(it, n + 1 - it) }
        }
        return slots
    }

    /** Taille du tableau : la puissance de deux immédiatement supérieure ou égale. */
    fun tailleDuTableau(nbParticipants: Int): Int {
        var taille = 1
        while (taille < nbParticipants) taille *= 2
        return taille
    }

    // ----------------------------------------------------------------------- //
    // Élimination simple
    // ----------------------------------------------------------------------- //

    /**
     * `n - 1` matchs pour `n` places : chaque match élimine exactement un
     * participant, et il faut éliminer tout le monde sauf le vainqueur.
     */
    fun eliminationSimple(nbParticipants: Int): List<MatchPlanifie> {
        val taille = tailleDuTableau(nbParticipants)
        val nbTours = Integer.numberOfTrailingZeros(taille)
        val slots = ordreDesSeeds(taille)

        return (1..nbTours).flatMap { tour ->
            val nbMatchs = taille shr tour
            (1..nbMatchs).map { position ->
                MatchPlanifie(
                    cle = cle(BracketType.winner, tour, position),
                    round = tour,
                    position = position,
                    bracket = BracketType.winner,
                    vainqueurVers = if (tour < nbTours) {
                        cle(BracketType.winner, tour + 1, (position + 1) / 2)
                    } else {
                        null
                    },
                    seedA = if (tour == 1) slots[2 * (position - 1)] else null,
                    seedB = if (tour == 1) slots[2 * (position - 1) + 1] else null,
                )
            }
        }
    }

    // ----------------------------------------------------------------------- //
    // Élimination double
    // ----------------------------------------------------------------------- //

    /**
     * Deux tableaux et une grande finale : `2n - 2` matchs.
     *
     * Le tableau des vainqueurs est celui de l'élimination simple ; chaque perdant
     * bascule dans le tableau des perdants au lieu d'être éliminé. Ce dernier
     * alterne deux sortes de tours :
     *
     * - un tour **de bascule**, où les perdants fraîchement descendus du tableau
     *   des vainqueurs rencontrent les survivants du tableau des perdants ;
     * - un tour **de consolidation**, où les survivants s'affrontent entre eux.
     *
     * Le vainqueur du tableau des perdants affronte celui des vainqueurs en grande
     * finale. Pas de *bracket reset* : une seule grande finale, choix assumé.
     */
    fun eliminationDouble(nbParticipants: Int): List<MatchPlanifie> {
        val taille = tailleDuTableau(nbParticipants)
        require(taille >= 4) { "L'élimination double exige au moins 4 places" }
        val nbToursW = Integer.numberOfTrailingZeros(taille)
        val slots = ordreDesSeeds(taille)
        val matchs = mutableListOf<MatchPlanifie>()

        // Tours du tableau des perdants, dans l'ordre, avec leur nombre de matchs.
        // Pour 8 places : [2, 2, 1, 1] — bascule, consolidation, bascule, …
        val toursL = mutableListOf<Int>()
        var restants = taille / 4 // perdants du 1er tour W, appariés entre eux
        toursL += restants
        for (tourW in 2..nbToursW) {
            toursL += restants // bascule : survivants L contre perdants de W(tourW)
            if (restants > 1) {
                restants /= 2
                toursL += restants // consolidation
            }
        }

        // --- tableau des vainqueurs
        for (tour in 1..nbToursW) {
            val nbMatchs = taille shr tour
            for (position in 1..nbMatchs) {
                // Le perdant descend : 1er tour W → 1er tour L ; ensuite, dans le
                // tour de bascule correspondant.
                val tourLCible = if (tour == 1) 1 else indiceTourBascule(tour, toursL)
                val positionL = if (tour == 1) (position + 1) / 2 else position
                matchs += MatchPlanifie(
                    cle = cle(BracketType.winner, tour, position),
                    round = tour,
                    position = position,
                    bracket = BracketType.winner,
                    vainqueurVers = if (tour < nbToursW) {
                        cle(BracketType.winner, tour + 1, (position + 1) / 2)
                    } else {
                        cleGrandeFinale()
                    },
                    perdantVers = cle(BracketType.loser, tourLCible, positionL),
                    seedA = if (tour == 1) slots[2 * (position - 1)] else null,
                    seedB = if (tour == 1) slots[2 * (position - 1) + 1] else null,
                )
            }
        }

        // --- tableau des perdants
        toursL.forEachIndexed { index, nbMatchs ->
            val tour = index + 1
            val dernier = tour == toursL.size
            for (position in 1..nbMatchs) {
                val suivantEstBascule = !dernier && toursL[tour] == nbMatchs
                matchs += MatchPlanifie(
                    cle = cle(BracketType.loser, tour, position),
                    round = nbToursW + tour,
                    position = position,
                    bracket = BracketType.loser,
                    vainqueurVers = when {
                        dernier -> cleGrandeFinale()
                        // vers un tour de bascule : même position (l'autre slot
                        // sera rempli par un perdant du tableau des vainqueurs)
                        suivantEstBascule -> cle(BracketType.loser, tour + 1, position)
                        // vers un tour de consolidation : deux entrants par match
                        else -> cle(BracketType.loser, tour + 1, (position + 1) / 2)
                    },
                    // Dans le tableau des perdants, perdre élimine définitivement.
                    perdantVers = null,
                )
            }
        }

        // --- grande finale
        matchs += MatchPlanifie(
            cle = cleGrandeFinale(),
            round = nbToursW + toursL.size + 1,
            position = 1,
            bracket = BracketType.grand_final,
        )
        return matchs
    }

    /**
     * Indice, dans la liste des tours du tableau des perdants, du tour de bascule
     * qui accueille les perdants du tour `tourW` du tableau des vainqueurs.
     *
     * Les tours de bascule sont, par construction, ceux dont le nombre de matchs
     * égale celui du tour précédent (1er tour excepté).
     */
    private fun indiceTourBascule(tourW: Int, toursL: List<Int>): Int {
        var rencontres = 0
        for (i in 1 until toursL.size) {
            if (toursL[i] == toursL[i - 1]) {
                rencontres++
                if (rencontres == tourW - 1) return i + 1
            }
        }
        return toursL.size // dernier tour : accueille le perdant de la finale W
    }

    // ----------------------------------------------------------------------- //
    // Round robin
    // ----------------------------------------------------------------------- //

    /**
     * Toutes les rencontres possibles : `n (n-1) / 2` matchs, répartis en
     * journées par la **méthode du cercle**, de sorte qu'un participant ne joue
     * qu'une fois par journée.
     *
     * Aucun chaînage : il n'y a pas d'arbre, donc pas de vainqueur à propager. Le
     * classement se calcule à partir des résultats.
     */
    fun roundRobin(nbParticipants: Int): List<MatchPlanifie> {
        require(nbParticipants >= 2) { "Un round robin exige au moins 2 participants" }
        // Un participant fictif rend le nombre pair : son adversaire est au repos.
        val pair = if (nbParticipants % 2 == 0) nbParticipants else nbParticipants + 1
        val auRepos = if (nbParticipants % 2 == 0) null else pair

        var rotation = (1..pair).toList()
        val matchs = mutableListOf<MatchPlanifie>()

        for (journee in 1 until pair) {
            var position = 1
            for (i in 0 until pair / 2) {
                val a = rotation[i]
                val b = rotation[pair - 1 - i]
                if (a == auRepos || b == auRepos) continue // journée de repos
                matchs += MatchPlanifie(
                    cle = cle(BracketType.group, journee, position),
                    round = journee,
                    position = position,
                    bracket = BracketType.group,
                    seedA = a,
                    seedB = b,
                )
                position++
            }
            // Le premier reste fixe, les autres tournent d'un cran.
            rotation = listOf(rotation.first()) + rotation.drop(2) + rotation[1]
        }
        return matchs
    }

    // ----------------------------------------------------------------------- //

    private fun cle(bracket: BracketType, tour: Int, position: Int): String {
        val prefixe = when (bracket) {
            BracketType.winner -> "WB"
            BracketType.loser -> "LB"
            BracketType.group -> "GR"
            BracketType.grand_final -> "GF"
        }
        return "$prefixe-$tour-$position"
    }

    private fun cleGrandeFinale() = "GF-1-1"
}
