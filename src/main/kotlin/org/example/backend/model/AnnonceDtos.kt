package org.example.backend.model

/**
 * Une annonce prête à afficher.
 *
 * `message` est du **texte**, pas du HTML : le fil d'activité a déjà produit une
 * faille XSS en renvoyant du balisage construit avec des noms d'utilisateurs.
 */
data class AnnonceDto(
    val id: String,
    val tournamentId: String,
    val tournamentName: String,
    /** match_start | match_end | round_advance | bracket_generated | tournament_finished */
    val kind: String,
    val message: String,
    val time: String,
)

/** Cloche : les annonces qui concernent le lecteur, et combien sont nouvelles. */
data class MesAnnoncesDto(val annonces: List<AnnonceDto>, val nonLues: Int)
