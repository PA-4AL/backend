# ADR-0008 — Formats d'arbre : élimination double et round robin

- **Date** : 2026-07-29
- **Statut** : accepté
- **Portée** : backend, frontend

## Contexte

Le schéma prévoyait quatre formats de phase (`single_elim`, `double_elim`,
`round_robin`, `swiss`) et les colonnes nécessaires (`matches.bracket`,
`next_match_loser_id`), mais seule l'élimination simple était implémentée. Le
frontend affichait les trois autres avec la mention « bientôt ».

## Décision

Implémenter **l'élimination double** et **le round robin**. Laisser le **système
suisse** non implémenté, et le désactiver explicitement dans l'interface.

Le calcul de la structure est extrait dans un objet **pur**,
`service/bracket/GenerateurBracket` : il prend un nombre de participants et rend
une liste de matchs planifiés, liés par des clés locales (`WB-2-1`). Aucune
dépendance à la base, à Spring ni à HTTP.

Pourquoi le suisse est à part : ses appariements dépendent du **classement après
chaque tour**. Il n'existe aucun arbre à pré-générer — il faudrait un endpoint
« générer le tour suivant », donc un modèle d'interaction différent. Le prétendre
« bientôt » dans un sélecteur alors que le bouton échoue est pire que de le
désactiver.

## Conséquences

- le générateur est **testable sans infrastructure** : 13 tests vérifient les
  invariants de chaque format (nombre de matchs, absence de lien pendant,
  séparation des seeds forts, un participant ne joue qu'une fois par journée)
- l'insertion se fait en deux passes — insérer puis câbler — car en élimination
  double un match du tableau des vainqueurs pointe vers un match du tableau des
  perdants créé après lui
- `reportScore` propage désormais **aussi le perdant** vers `next_match_loser_id`
- la fin de tournoi n'a plus un critère unique : en arbre c'est le match sans
  suite, en round robin c'est la dernière rencontre jouée
- les libellés de tour deviennent dépendants du format : « Journée 3 », « Perdants
  — tour 2 », « Finale des vainqueurs », « Grande finale »
- **pas de *bracket reset*** en élimination double : une seule grande finale, même
  si le vainqueur du tableau des perdants l'emporte. Choix assumé, simplification
  courante en tournoi amateur
- le classement d'un round robin se limite au **nombre de victoires** : ni
  différence de points, ni départage. À compléter si le besoin apparaît
