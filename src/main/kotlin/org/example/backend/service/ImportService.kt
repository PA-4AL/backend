package org.example.backend.service

import org.example.backend.database.enums.RegistrationStatus
import org.example.backend.database.enums.TeamMemberRole
import org.example.backend.repository.RegistrationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Bilan d'une matérialisation, journalisé et renvoyé dans le résultat du job. */
data class BilanImport(
    val equipesCreees: Int,
    val equipesExistantes: Int,
    val joueursCrees: Int,
    val joueursRattaches: Int,
    val inscriptions: Int,
    /**
     * Équipes dont l'effectif ne correspond pas au format du tournoi.
     *
     * **Signalées, pas refusées.** L'inscription manuelle d'une équipe exige un
     * roster complet, mais un import est massif : rejeter tout un fichier pour une
     * équipe incomplète obligerait l'organisateur à le corriger en aveugle. Il est
     * plus utile de tout créer et de lui dire lesquelles vérifier — c'est lui qui
     * sait si un joueur manquant arrivera.
     */
    val equipesIncompletes: List<String> = emptyList(),
)

/**
 * Matérialisation d'un import Excel : transforme le résultat du worker en
 * **données réelles** — équipes, joueurs fantômes, rangs, inscriptions.
 *
 * Avant, `applyWorkerResponse` se contentait d'enregistrer la réponse dans le
 * JSON du job et de le marquer terminé. Un import « réussi » ne laissait donc
 * aucune trace exploitable : pas d'équipe en base, pas de joueur, et le rang lu
 * dans le fichier était perdu — c'est pourquoi la colonne « Rang » ressortait
 * vide à l'export. Les « joueurs fantômes » de la spec §6.1.3 étaient décrits et
 * jamais construits.
 *
 * **Idempotence obligatoire** : Pub/Sub garantit au moins une livraison, le même
 * résultat peut donc arriver deux fois. Équipes et joueurs sont retrouvés par nom
 * et par pseudo avant d'être créés, et l'inscription n'est posée que si elle
 * n'existe pas. Une redélivrance ne duplique rien.
 */
@Service
class ImportService(private val registrations: RegistrationRepository) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @param tournamentId tournoi où inscrire les équipes ; `null` = créer les
     *   équipes sans les inscrire (import de roster hors tournoi)
     * @param equipes structure rendue par le worker : `[{name, players:[{username, rank}]}]`
     */
    @Transactional
    fun materialiser(
        tournamentId: UUID?,
        equipes: List<Map<String, Any?>>,
        effectifAttendu: Int? = null,
    ): BilanImport {
        var equipesCreees = 0
        var equipesExistantes = 0
        var joueursCrees = 0
        var joueursRattaches = 0
        var inscriptions = 0
        val incompletes = mutableListOf<String>()

        equipes.forEach { equipe ->
            val nom = (equipe["name"] as? String)?.trim()
            if (nom.isNullOrEmpty()) {
                // Le worker valide déjà le fichier ; une ligne sans nom d'équipe
                // ici relèverait d'un contrat rompu, pas d'une saisie utilisateur.
                log.warn("Équipe sans nom dans le résultat d'import, ignorée")
                return@forEach
            }

            val existante = registrations.findTeamByName(nom)
            val teamId = existante ?: registrations.insertGhostTeam(nom).also { equipesCreees++ }
            if (existante != null) equipesExistantes++

            @Suppress("UNCHECKED_CAST")
            val joueurs = equipe["players"] as? List<Map<String, Any?>> ?: emptyList()
            joueurs.forEachIndexed { index, joueur ->
                val pseudo = (joueur["username"] as? String)?.trim()
                if (pseudo.isNullOrEmpty()) return@forEachIndexed
                val rang = (joueur["rank"] as? String)?.trim()?.ifEmpty { null }

                // Uniquement parmi les joueurs fantômes : un import ne doit pas
                // s'approprier le compte d'un joueur réellement inscrit.
                val userExistant = registrations.findGhostUserByPseudo(pseudo)
                val userId = userExistant ?: registrations.insertGhostUser(pseudo).also { joueursCrees++ }

                // Le premier joueur de la liste devient capitaine : le fichier ne
                // le désigne pas, et une équipe sans capitaine ne pourrait plus
                // être administrée par la suite.
                val role = if (index == 0) TeamMemberRole.captain else TeamMemberRole.member
                registrations.attacherMembre(teamId, userId, role, rang)
                joueursRattaches++
            }

            if (effectifAttendu != null && effectifAttendu > 1 && joueurs.size != effectifAttendu) {
                incompletes += "$nom (${joueurs.size}/$effectifAttendu)"
            }

            if (tournamentId != null && !registrations.existsForTeam(tournamentId, teamId)) {
                // Confirmée d'emblée : c'est l'organisateur qui importe le fichier,
                // la validation a déjà eu lieu hors de l'application.
                registrations.insertTeam(tournamentId, teamId, RegistrationStatus.confirmed)
                inscriptions++
            }
        }

        val bilan = BilanImport(
            equipesCreees,
            equipesExistantes,
            joueursCrees,
            joueursRattaches,
            inscriptions,
            incompletes,
        )
        log.info(
            "Import matérialisé : {} équipe(s) créée(s), {} existante(s), {} joueur(s) créé(s), {} inscription(s)",
            bilan.equipesCreees,
            bilan.equipesExistantes,
            bilan.joueursCrees,
            bilan.inscriptions,
        )
        if (incompletes.isNotEmpty()) {
            log.warn("Effectif incomplet pour : {}", incompletes.joinToString(", "))
        }
        return bilan
    }
}
