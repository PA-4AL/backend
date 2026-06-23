--liquibase formatted sql

--changeset pa:0002-seed-demo splitStatements:true endDelimiter:;
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT count(*) FROM tournaments

-- Données de démonstration (dev) : 3 tournois et quelques inscriptions solo.

INSERT INTO users (id, keycloak_id, pseudo, email) VALUES
  ('00000000-0000-0000-0000-000000000001', NULL, 'PA Esports', 'orga@pa.local'),
  ('00000000-0000-0000-0000-000000000011', NULL, 'Nebula',      'nebula@pa.local'),
  ('00000000-0000-0000-0000-000000000012', NULL, 'Vortex',      'vortex@pa.local'),
  ('00000000-0000-0000-0000-000000000013', NULL, 'ApexPred',    'apex@pa.local'),
  ('00000000-0000-0000-0000-000000000014', NULL, 'Syndicate',   'syndicate@pa.local'),
  ('00000000-0000-0000-0000-000000000015', NULL, 'Quasar',      'quasar@pa.local'),
  ('00000000-0000-0000-0000-000000000016', NULL, 'Riftwalker',  'rift@pa.local'),
  ('00000000-0000-0000-0000-000000000017', NULL, 'Titan',       'titan@pa.local'),
  ('00000000-0000-0000-0000-000000000018', NULL, 'Omega',       'omega@pa.local');

INSERT INTO tournaments (id, name, description, visibility, status, team_size, max_participants,
                         check_in_required, registration_open_at, registration_close_at, start_at)
VALUES
  ('10000000-0000-0000-0000-000000000001', 'APEX Invitational 2026',
   'Tournoi invitationnel — phase finale à 8.', 'public', 'ongoing', 1, 8,
   TRUE, now() - interval '7 days', now() - interval '1 day', now() - interval '2 hours'),
  ('10000000-0000-0000-0000-000000000002', 'Rookie Cup #18',
   'Tournoi découverte ouvert à tous.', 'public', 'registration', 1, 24,
   FALSE, now() - interval '2 days', now() + interval '3 days', now() + interval '4 days'),
  ('10000000-0000-0000-0000-000000000003', 'Pro League — Finale',
   'Finale de la saison.', 'public', 'finished', 1, 8,
   TRUE, now() - interval '30 days', now() - interval '10 days', now() - interval '9 days');

INSERT INTO tournament_organizers (tournament_id, user_id, role) VALUES
  ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'owner'),
  ('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'owner'),
  ('10000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', 'owner');

INSERT INTO phases (id, tournament_id, game, position, type, default_bo) VALUES
  ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'Apex Legends', 1, 'single_elim', 3),
  ('20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002', 'Rocket League', 1, 'round_robin', 1),
  ('20000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000003', 'Valorant', 1, 'double_elim', 5);

INSERT INTO registrations (id, tournament_id, user_id, status, seed) VALUES
  ('30000000-0000-0000-0000-000000000011', '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000011', 'checked_in', 1),
  ('30000000-0000-0000-0000-000000000012', '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000012', 'checked_in', 8),
  ('30000000-0000-0000-0000-000000000013', '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000013', 'checked_in', 4),
  ('30000000-0000-0000-0000-000000000014', '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000014', 'checked_in', 5),
  ('30000000-0000-0000-0000-000000000015', '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000015', 'checked_in', 2),
  ('30000000-0000-0000-0000-000000000016', '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000016', 'checked_in', 7),
  ('30000000-0000-0000-0000-000000000017', '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000017', 'checked_in', 3),
  ('30000000-0000-0000-0000-000000000018', '10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000018', 'checked_in', 6),
  ('30000000-0000-0000-0000-000000000021', '10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000011', 'confirmed', NULL),
  ('30000000-0000-0000-0000-000000000022', '10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000015', 'pending', NULL);
