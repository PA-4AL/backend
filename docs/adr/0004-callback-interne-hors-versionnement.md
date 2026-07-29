# ADR-0004 — Endpoint interne hors du versionnement d'API

- **Date** : 2026-07-29
- **Statut** : accepté
- **Portée** : backend

## Contexte

Pub/Sub livre les réponses du worker sur un endpoint HTTP du backend. Cet
endpoint n'est pas une route d'API publique : son appelant est un service, pas un
client. Or `WebMvcConfig` préfixe `/api/{version}` à **tout** le paquet
`controller`, et la résolution de version (`usePathSegment(1)`) valide le
deuxième segment de **toutes** les routes servies par le DispatcherServlet.

## Décision

Placer le controller de callback dans un paquet **`internal`**, hors de
`controller`, et lui donner un chemin dont le deuxième segment est une version
lisible : **`/internal/v1/jobs/callback`**.

Écarté : `setVersionRequired(false)` — cela ne règle rien, le segment `jobs`
reste illisible comme version, et cela toucherait la configuration de
versionnement de toute l'API pour un besoin local.

## Conséquences

- le préfixe `/api/{version}` ne s'applique pas à l'endpoint interne
- la route reste servie même si l'API passe en v2
- **le piège est invisible sans jeton** : Spring Security répond 401 avant la
  résolution de version, donc un test non authentifié passait au vert alors que
  la production échouait à chaque livraison. `CallbackAuthenticatedTests`
  emprunte désormais le chemin réel, jeton compris
- tout futur endpoint interne doit suivre la même règle : hors du paquet
  `controller`, et un segment de version en deuxième position
