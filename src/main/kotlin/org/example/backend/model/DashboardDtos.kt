package org.example.backend.model

/* DTOs du tableau de bord (frontend/src/api/types.ts). */

data class DashboardKpisDto(
    val activeTournaments: Int,
    val activeTournamentsDelta: String,
    val liveMatches: Int,
    val participants: Int,
    val participantsDelta: String,
    val pendingValidations: Int,
)

/**
 * Événement du fil d'activité, en **champs séparés**.
 *
 * Portait auparavant un `html` construit par concaténation côté serveur, que le
 * frontend rendait via `dangerouslySetInnerHTML`. Comme ni les pseudos ni les
 * noms de tournoi ne sont validés, un tournoi nommé `<img src=x onerror=…>`
 * exécutait du code chez tout organisateur ouvrant son tableau de bord — une
 * faille XSS stockée.
 *
 * Le correctif ne consiste pas à échapper le HTML mais à **ne plus en produire** :
 * le serveur n'a pas à décider du balisage, et une donnée transmise comme donnée
 * ne peut pas être interprétée comme du code.
 *
 * @param sujet ce dont on parle, mis en valeur à l'affichage (participant, tournoi)
 * @param action ce qui s'est passé, texte fixe issu du serveur
 * @param complement précision optionnelle (nom du tournoi concerné)
 */
data class ActivityItemDto(
    val id: String,
    val kind: String, // win | live | registration | dispute | finished
    val sujet: String,
    val action: String,
    val complement: String? = null,
    val time: String,
)
