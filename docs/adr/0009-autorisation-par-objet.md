# ADR-0009 — Autoriser par objet, pas seulement par rôle

- **Date** : 2026-07-30
- **Statut** : accepté
- **Portée** : backend

## Contexte

Une revue a montré que **2 routes sur 29** portaient un contrôle d'autorisation,
et seulement sur le rôle. En production, tout compte authentifié pouvait donc,
sur n'importe quel tournoi : régénérer l'arbre, saisir un score, déplacer les
équipes, modifier un seed. Le `CLAUDE.md` l'annonçait comme une dette, mais
l'application est déployée publiquement.

`hasRole('organizer')` ne peut pas suffire : il affirme qu'on organise *quelque
chose*, pas ce tournoi-ci.

## Décision

Deux niveaux, tous les deux requis sur les routes d'écriture.

**Le rôle, par annotation.** `@PreAuthorize("hasAnyRole('organizer','admin'))"`
écarte immédiatement un simple joueur, sans toucher la base.

**La propriété, dans le domaine.** Un composant `Droits` vérifie la présence de
l'appelant dans `tournament_organizers`. Le contrôle vit dans le domaine et non
dans une expression SpEL : il dépend d'une lecture en base, et une condition
enfouie dans une annotation serait invisible aux tests unitaires. Il est ici
couvert par `DroitsTest`.

L'**administrateur passe outre**, délibérément : c'est le rôle de modération
globale de la spec, et il doit pouvoir intervenir sur un tournoi abandonné.

Le message de refus est **identique** que le tournoi soit inconnu ou simplement
interdit : distinguer les deux renseignerait sur l'existence d'une ressource
qu'on n'a pas le droit de voir.

Options écartées : tout mettre dans `@PreAuthorize` avec du SpEL appelant un bean
(illisible et mal testable) ; un filtre générique par URL (ne sait pas lire la
relation entre l'utilisateur et l'objet).

## Conséquences

- les méthodes de service prennent `callerId` et `estAdmin` : plus verbeux, mais
  l'autorisation devient une **donnée d'entrée explicite** plutôt qu'un effet de
  contexte, donc testable sans Spring ;
- l'export est désormais réservé aux organisateurs : il contient les pseudos de
  tous les joueurs, ce n'est pas une donnée publique même si le bracket l'est ;
- reste à faire : les routes d'inscription et de validation
  (`RegistrationV1Controller`) suivent la même logique et ne sont pas encore
  couvertes ; c'est la prochaine étape, documentée comme telle.
