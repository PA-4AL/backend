--liquibase formatted sql

-- Rang en jeu d'un joueur dans son équipe (Diamant, Or, Platine…).
--
-- La colonne « Rang » existait dans les fichiers Excel depuis le début : le worker
-- la lisait à l'import et devait la réécrire à l'export. Mais rien ne la
-- persistait, l'aller-retour perdait donc l'information et la colonne ressortait
-- vide. Elle vit sur l'appartenance à l'équipe et non sur l'utilisateur : un même
-- joueur peut être classé différemment selon le jeu de l'équipe.

--changeset pa:0004-team-member-rank
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.columns WHERE table_name='team_members' AND column_name='rank'
ALTER TABLE team_members ADD COLUMN rank VARCHAR;

-- Classement final d'une inscription dans son tournoi (1 = vainqueur).
--
-- Le classement était calculé à la volée par le worker à chaque export, donc
-- jamais consultable dans l'application et impossible à corriger à la main. Il
-- devient une donnée du tournoi.

--changeset pa:0004-registration-final-rank
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.columns WHERE table_name='registrations' AND column_name='final_rank'
ALTER TABLE registrations ADD COLUMN final_rank INTEGER;

--changeset pa:0004-registration-final-rank-check
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.constraint_column_usage WHERE constraint_name='registrations_final_rank_positive'
ALTER TABLE registrations
    ADD CONSTRAINT registrations_final_rank_positive CHECK (final_rank IS NULL OR final_rank >= 1);
