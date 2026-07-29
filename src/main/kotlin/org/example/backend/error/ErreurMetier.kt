package org.example.backend.error

/**
 * Erreurs **métier** : une règle du domaine est violée, ou la demande est
 * incohérente avec l'état courant.
 *
 * Aucune dépendance à Spring Web ici, volontairement : le domaine ne connaît pas
 * HTTP. La traduction en statut se fait à la frontière, dans
 * [org.example.backend.web.GestionnaireErreurs]. Voir `docs/adr/0007-erreurs-metier-et-http.md`.
 *
 * Le message est destiné à l'utilisateur final : il est renvoyé tel quel au
 * frontend, donc rédigé en français et sans détail d'implémentation.
 */
sealed class ErreurMetier(message: String) : RuntimeException(message) {

    /** La ressource demandée n'existe pas (ou n'est pas visible de l'appelant). */
    class Introuvable(message: String) : ErreurMetier(message)

    /** La demande est malformée : champ absent, valeur hors domaine, format inconnu. */
    class Invalide(message: String) : ErreurMetier(message)

    /**
     * L'état actuel interdit l'opération : tournoi déjà démarré, match déjà
     * terminé, transition d'inscription impossible… La demande n'est pas fausse,
     * elle arrive trop tôt ou trop tard.
     */
    class Conflit(message: String) : ErreurMetier(message)

    /**
     * L'appelant est authentifié mais n'a pas le droit **métier** de faire cela :
     * il n'est pas capitaine de l'équipe, ni organisateur du tournoi.
     */
    class NonAutorise(message: String) : ErreurMetier(message)

    /** La charge transmise dépasse une limite technique connue et documentée. */
    class TropVolumineux(message: String) : ErreurMetier(message)
}
