package org.example.backend.service

import org.example.backend.error.ErreurMetier
import org.example.backend.repository.TournamentRepository
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Contrôles d'autorisation **par objet** : « cet utilisateur a-t-il le droit
 * d'agir sur CE tournoi ? »
 *
 * Le rôle porté par le jeton ne suffit pas. `hasRole('organizer')` dit qu'on est
 * organisateur *de quelque chose*, pas de ce tournoi-ci. Sans ce contrôle,
 * n'importe quel compte authentifié pouvait régénérer l'arbre d'un tournoi qui
 * n'était pas le sien, y saisir des scores ou en déplacer les équipes — ce qui
 * était le cas en production, seules 2 routes sur 29 étant protégées.
 *
 * Le contrôle vit dans le domaine et non dans une annotation : il dépend d'une
 * lecture en base (`tournament_organizers`), et une expression SpEL dans un
 * `@PreAuthorize` la rendrait invisible aux tests unitaires.
 */
@Component
class Droits(private val tournaments: TournamentRepository) {

    /**
     * Exige que l'appelant soit organisateur du tournoi, ou administrateur.
     *
     * L'administrateur passe outre volontairement : c'est le rôle de modération
     * globale de la spec, et il doit pouvoir intervenir sur un tournoi abandonné.
     *
     * @throws ErreurMetier.NonAutorise message volontairement identique dans les
     *   deux cas d'échec — préciser « vous n'êtes pas organisateur de CE tournoi »
     *   renseignerait sur l'existence d'un tournoi qu'on n'a pas le droit de voir.
     */
    fun exigerOrganisateur(tournamentId: UUID, callerId: UUID, estAdmin: Boolean) {
        if (estAdmin) return
        if (!tournaments.estOrganisateur(tournamentId, callerId)) {
            throw ErreurMetier.NonAutorise("Vous n'êtes pas organisateur de ce tournoi")
        }
    }

    /** Même contrôle, à partir d'une phase (les matchs n'en connaissent pas d'autre). */
    fun exigerOrganisateurDePhase(tournamentId: UUID?, callerId: UUID, estAdmin: Boolean) {
        if (tournamentId == null) throw ErreurMetier.Introuvable("Tournoi introuvable")
        exigerOrganisateur(tournamentId, callerId, estAdmin)
    }
}
