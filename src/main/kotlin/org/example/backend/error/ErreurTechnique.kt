package org.example.backend.error

/**
 * Erreurs **techniques** : une dépendance externe est indisponible ou a échoué.
 * Rien à voir avec une règle du domaine — l'utilisateur n'y peut rien, et le
 * message doit rester générique.
 *
 * Séparées des [ErreurMetier] à la demande du brief : ce ne sont ni les mêmes
 * causes, ni les mêmes statuts, ni le même traitement (celles-ci se journalisent
 * en `ERROR` et méritent une alerte).
 */
sealed class ErreurTechnique(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {

    /** Le service n'est pas configuré ou pas disponible dans cet environnement. */
    class ServiceIndisponible(message: String) : ErreurTechnique(message)

    /** Une dépendance a répondu en erreur ou n'a pas répondu. */
    class DependanceEnEchec(service: String, cause: Throwable? = null) :
        ErreurTechnique("Le service « $service » est momentanément indisponible", cause)
}
