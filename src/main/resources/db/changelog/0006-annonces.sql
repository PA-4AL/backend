--liquibase formatted sql

-- Annonces d'un tournoi : début de match, fin de match, passage au tour suivant…
--
-- Le message est stocké **déjà rédigé**, en texte simple. Deux raisons :
--
--  1. le reconstruire à la lecture exigerait de conserver les identifiants des
--     équipes et de refaire la mise en forme à chaque affichage, alors qu'une
--     annonce décrit un fait passé qui ne changera plus ;
--  2. surtout, pas de balisage : le fil d'activité a déjà produit une faille XSS
--     en concaténant du HTML avec des noms saisis par les utilisateurs
--     (cf. docs/adr/…). Une annonce est du texte, affiché comme du texte.

--changeset pa:0006-announcements
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.tables WHERE table_name='announcements'
CREATE TABLE announcements (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tournament_id UUID        NOT NULL REFERENCES tournaments (id) ON DELETE CASCADE,
    -- match_start | match_end | round_advance | bracket_generated | tournament_finished
    kind          VARCHAR     NOT NULL,
    message       VARCHAR     NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Toutes les lectures sont « les dernières annonces de ces tournois » : l'index
-- porte donc sur le couple, dans l'ordre d'affichage.
--changeset pa:0006-announcements-index
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT count(*) FROM pg_indexes WHERE indexname='idx_announcements_tournament_date'
CREATE INDEX idx_announcements_tournament_date ON announcements (tournament_id, created_at DESC);

-- Date de dernière consultation, pour le compteur de non-lues.
--
-- Une simple date plutôt qu'une table de lectures par annonce et par
-- utilisateur : le compteur de la cloche n'a pas besoin de savoir *lesquelles*
-- ont été lues, seulement combien sont arrivées depuis la dernière visite. Une
-- table de jointure coûterait une ligne par annonce et par destinataire pour
-- répondre à une question plus simple.

--changeset pa:0006-users-announcements-seen
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.columns WHERE table_name='users' AND column_name='announcements_seen_at'
ALTER TABLE users ADD COLUMN announcements_seen_at TIMESTAMPTZ;
