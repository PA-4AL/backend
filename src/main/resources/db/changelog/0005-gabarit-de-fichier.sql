--liquibase formatted sql

-- Gabarit de fichier Excel du tournoi (import et export).
--
-- Il était **déduit de la taille d'équipe** : 11 → football, tout le reste →
-- esport. La règle tenait pour un tournoi de football à 11, et se trompait pour
-- tout le reste — un 7v7 de football recevait le gabarit esport, donc les
-- colonnes Pseudo/Rang au lieu de Nom/Prénom/Poste/Numéro.
--
-- Le gabarit est une propriété de la **discipline**, pas du nombre de joueurs :
-- aucune heuristique sur `team_size` ne peut la deviner. Il devient donc une
-- donnée du tournoi, choisie à la création.
--
-- `NULL` = esport, qui reste le cas par défaut : cela évite de réécrire les
-- tournois existants, dont aucun n'est un tournoi de football.

--changeset pa:0005-tournament-file-template
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.columns WHERE table_name='tournaments' AND column_name='file_template'
ALTER TABLE tournaments ADD COLUMN file_template VARCHAR;

--changeset pa:0005-tournament-file-template-check
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.constraint_column_usage WHERE constraint_name='tournaments_file_template_connu'
ALTER TABLE tournaments
    ADD CONSTRAINT tournaments_file_template_connu
    CHECK (file_template IS NULL OR file_template IN ('esport_5v5', 'football_11v11'));
