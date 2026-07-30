package org.example.backend.service.tournoi

import org.example.backend.database.enums.TournamentStatus

/**
 * Cycle de vie d'un tournoi — **table de transitions pure**, sans base ni Spring.
 *
 * Jusqu'ici un tournoi naissait en `draft` et n'en sortait que **tout seul** : la
 * saisie du premier score le passait en `ongoing`. Les statuts `registration` et
 * `check_in` étaient donc **inatteignables** pour un tournoi réellement créé dans
 * l'application — seul le jeu de démonstration, inséré en SQL, les portait. Un
 * tournoi restait affiché « brouillon » alors qu'il avait des inscrits.
 *
 * Les transitions sont **listées** plutôt que déduites d'un ordre : l'ordre
 * suggérerait qu'on peut toujours revenir en arrière, ce qui est faux. Un tournoi
 * terminé ne se rouvre pas, et un tournoi annulé non plus — ce sont des résultats,
 * pas des étapes.
 */
object TransitionsTournoi {

    /**
     * Ce qu'on peut faire depuis chaque statut.
     *
     * Deux retours en arrière sont volontairement permis :
     *
     * - `check_in → registration` : rouvrir les inscriptions quand trop peu
     *   d'équipes se sont présentées, situation banale d'un tournoi amateur ;
     * - `registration → draft` : refermer un tournoi ouvert par erreur, tant que
     *   rien n'a commencé.
     */
    private val AUTORISEES: Map<TournamentStatus, Set<TournamentStatus>> = mapOf(
        TournamentStatus.draft to setOf(
            TournamentStatus.registration,
            TournamentStatus.cancelled,
        ),
        TournamentStatus.registration to setOf(
            TournamentStatus.check_in,
            TournamentStatus.ongoing,
            TournamentStatus.draft,
            TournamentStatus.cancelled,
        ),
        TournamentStatus.check_in to setOf(
            TournamentStatus.ongoing,
            TournamentStatus.registration,
            TournamentStatus.cancelled,
        ),
        TournamentStatus.ongoing to setOf(
            TournamentStatus.finished,
            TournamentStatus.cancelled,
        ),
        // Terminaux : un résultat ne se rejoue pas, une annulation ne se rétracte pas.
        TournamentStatus.finished to emptySet(),
        TournamentStatus.cancelled to emptySet(),
    )

    /** Statuts atteignables depuis celui-ci — sert aussi à l'interface. */
    fun depuis(actuel: TournamentStatus): Set<TournamentStatus> = AUTORISEES[actuel] ?: emptySet()

    fun estAutorisee(de: TournamentStatus, vers: TournamentStatus): Boolean = vers in depuis(de)

    /** Aucun retour possible : le tournoi a atteint son état définitif. */
    fun estTerminal(statut: TournamentStatus): Boolean = depuis(statut).isEmpty()

    /**
     * Le tournoi occupe-t-il un état où les inscriptions restent ouvertes ?
     *
     * `draft` en fait partie : l'organisateur ajoute ses participants avant
     * d'annoncer le tournoi, et le refuser l'obligerait à publier un tournoi vide.
     */
    fun accepteDesInscriptions(statut: TournamentStatus): Boolean = statut in setOf(
        TournamentStatus.draft,
        TournamentStatus.registration,
        TournamentStatus.check_in,
    )

    /**
     * Libellé de l'action qui mène à ce statut, à l'impératif.
     *
     * Rédigé côté serveur pour que l'interface n'ait pas à traduire une énumération
     * de base de données — et pour que les deux ne divergent pas.
     */
    fun libelleAction(vers: TournamentStatus): String = when (vers) {
        TournamentStatus.draft -> "Repasser en brouillon"
        TournamentStatus.registration -> "Ouvrir les inscriptions"
        TournamentStatus.check_in -> "Ouvrir le check-in"
        TournamentStatus.ongoing -> "Démarrer le tournoi"
        TournamentStatus.finished -> "Clore le tournoi"
        TournamentStatus.cancelled -> "Annuler le tournoi"
    }
}
