# ADR-0010 — Annonces en direct par WebSocket, sans diffusion inter-instances

- **Date** : 2026-07-30
- **Statut** : accepté
- **Portée** : backend

## Contexte

Le projet demande des annonces sur un tournoi — début de match, fin de match,
passage au tour suivant — notifiées à l'organisateur et aux joueurs, en direct.

Le backend tourne sur Cloud Run, qui répartit les requêtes entre plusieurs
instances (jusqu'à 4 en production). Une WebSocket n'est ouverte que sur **une**
instance : une annonce produite sur l'instance A n'atteint pas nativement un
client connecté à l'instance B.

## Décision

**WebSocket brute** sur `/ws/annonces?tournoi=<id>`, sans STOMP ni SockJS : un
seul canal texte suffit, et chaque couche ajoutée serait à exploiter et à déboguer.

**Pas de diffusion inter-instances.** La limite est assumée plutôt que résolue :

- l'**annonce est d'abord persistée**, la diffusion n'est qu'un confort. Le client
  recharge la liste complète à chaque (re)connexion, donc **rien n'est perdu
  durablement** — seule l'instantanéité peut manquer ;
- au trafic de ce projet, une seule instance sert l'essentiel du temps.

Options écartées : republier par Pub/Sub pour que chaque instance serve les
siennes (correct, mais une demi-journée pour un gain invisible à cette échelle) ;
forcer `max_instances = 1` (supprime le problème en plafonnant la montée en
charge, ce qui échange un défaut invisible contre un défaut structurel).

**Canal non authentifié**, délibérément. Un navigateur ne peut pas poser d'en-tête
sur une WebSocket, et passer le jeton en paramètre d'URL le ferait figurer dans
les journaux d'accès. Le canal ne transporte donc que ce qui est **déjà public** :
des résultats de matchs, lisibles par quiconque sur la page du bracket.

Le ciblage « organisateur et joueurs, **pas** les administrateurs » est assuré par
la **cloche**, qui passe par l'API authentifiée : elle agrège les annonces des
tournois où le lecteur est engagé. Un administrateur n'est pas abonné à tout — il
en recevrait des centaines et n'en lirait aucune.

**Compteur de non-lues par date**, `users.announcements_seen_at`, plutôt qu'une
table de lectures par annonce et par destinataire : la cloche n'a pas besoin de
savoir *lesquelles* ont été lues, seulement combien sont arrivées depuis la
dernière visite.

## Conséquences

- une annonce peut ne pas arriver en direct à un client servi par une autre
  instance ; elle apparaît au rechargement suivant. À dire en soutenance plutôt
  qu'à découvrir ;
- le canal ne pourra jamais transporter de données privées en l'état : y ajouter
  des messages ciblés exigerait d'abord de l'authentifier ;
- les annonces ne peuvent pas faire échouer l'action qui les déclenche — un score
  enregistré ne s'annule pas parce qu'une WebSocket s'est fermée. Deux tests le
  verrouillent.
