--liquibase formatted sql

--changeset pa:0001-initial-schema splitStatements:true endDelimiter:;
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='users'
CREATE EXTENSION IF NOT EXISTS "pgcrypto"; -- gen_random_uuid()

-- ---------- Enums ----------
CREATE TYPE team_member_role AS ENUM ('captain', 'member', 'substitute');
CREATE TYPE tournament_visibility AS ENUM ('public', 'private');
CREATE TYPE tournament_status AS ENUM ('draft', 'registration', 'check_in', 'ongoing', 'finished', 'cancelled');
CREATE TYPE organizer_role AS ENUM ('owner', 'co_organizer');
CREATE TYPE phase_type AS ENUM ('single_elim', 'double_elim', 'round_robin', 'swiss');
CREATE TYPE registration_status AS ENUM ('pending', 'confirmed', 'waitlist', 'checked_in', 'withdrawn', 'disqualified');
CREATE TYPE bracket_type AS ENUM ('winner', 'loser', 'grand_final', 'group');
CREATE TYPE match_status AS ENUM ('pending', 'ongoing', 'finished', 'disputed', 'forfeited');
CREATE TYPE dispute_status AS ENUM ('open', 'resolved');
CREATE TYPE notification_channel AS ENUM ('email', 'discord_webhook');
CREATE TYPE notification_event AS ENUM ('match_starting', 'registration_validated', 'score_disputed', 'tournament_starting');
CREATE TYPE job_type AS ENUM ('team_import', 'team_export');
CREATE TYPE job_status AS ENUM ('pending', 'processing', 'done', 'failed');

-- ---------- Utilisateurs & équipes ----------
CREATE TABLE users (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    keycloak_id VARCHAR UNIQUE,            -- NULL = joueur fantôme (importé)
    pseudo      VARCHAR NOT NULL,
    email       VARCHAR UNIQUE,            -- sert au rattachement des fantômes
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE game_accounts (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    game       VARCHAR NOT NULL,           -- ex : "lol", "valorant"
    identifier VARCHAR NOT NULL            -- ex : Riot ID
);

CREATE TABLE teams (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR NOT NULL,
    tag        VARCHAR(8),
    logo_url   VARCHAR,
    created_by UUID REFERENCES users (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE team_members (
    team_id   UUID NOT NULL REFERENCES teams (id) ON DELETE CASCADE,
    user_id   UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role      team_member_role NOT NULL DEFAULT 'member',
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (team_id, user_id)
);

-- ---------- Tournois ----------
CREATE TABLE tournaments (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                    VARCHAR NOT NULL,
    description             TEXT,
    visibility              tournament_visibility NOT NULL DEFAULT 'public',
    status                  tournament_status NOT NULL DEFAULT 'draft',
    team_size               INT NOT NULL DEFAULT 1,    -- 1 = solo
    max_participants        INT,
    check_in_required       BOOLEAN NOT NULL DEFAULT FALSE,
    check_in_window_minutes INT,
    registration_open_at    TIMESTAMPTZ,
    registration_close_at   TIMESTAMPTZ,
    start_at                TIMESTAMPTZ,
    end_at                  TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tournament_organizers (
    tournament_id UUID NOT NULL REFERENCES tournaments (id) ON DELETE CASCADE,
    user_id       UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role          organizer_role NOT NULL DEFAULT 'co_organizer',
    PRIMARY KEY (tournament_id, user_id)
);

CREATE TABLE phases (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tournament_id UUID NOT NULL REFERENCES tournaments (id) ON DELETE CASCADE,
    game          VARCHAR NOT NULL,          -- jeu de la phase (multi-jeu)
    position      INT NOT NULL,              -- ordre des phases
    type          phase_type NOT NULL,
    default_bo    INT NOT NULL DEFAULT 1,
    settings      JSONB                      -- nb de poules, qualifiés par poule, etc.
);

-- ---------- Inscriptions ----------
CREATE TABLE registrations (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tournament_id UUID NOT NULL REFERENCES tournaments (id) ON DELETE CASCADE,
    team_id       UUID REFERENCES teams (id),
    user_id       UUID REFERENCES users (id),
    status        registration_status NOT NULL DEFAULT 'pending',
    seed          INT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- exactement un de team_id / user_id est non NULL
    CONSTRAINT registration_one_participant CHECK (num_nonnulls(team_id, user_id) = 1),
    CONSTRAINT registration_unique_team UNIQUE (tournament_id, team_id),
    CONSTRAINT registration_unique_user UNIQUE (tournament_id, user_id)
);

-- ---------- Matchs & résultats ----------
CREATE TABLE matches (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phase_id            UUID NOT NULL REFERENCES phases (id) ON DELETE CASCADE,
    round               INT NOT NULL,
    position            INT NOT NULL,              -- position dans le round
    bracket             bracket_type NOT NULL DEFAULT 'winner',
    best_of             INT NOT NULL DEFAULT 1,
    participant1_id     UUID REFERENCES registrations (id),  -- NULL = bye / en attente
    participant2_id     UUID REFERENCES registrations (id),
    winner_id           UUID REFERENCES registrations (id),
    status              match_status NOT NULL DEFAULT 'pending',
    next_match_id       UUID REFERENCES matches (id),        -- où va le vainqueur
    next_match_loser_id UUID REFERENCES matches (id),        -- où va le perdant (double élim)
    scheduled_at        TIMESTAMPTZ,
    station             VARCHAR
);

CREATE TABLE match_games (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    match_id    UUID NOT NULL REFERENCES matches (id) ON DELETE CASCADE,
    game_number INT NOT NULL,
    score1      INT,
    score2      INT
);

CREATE TABLE score_reports (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    match_id    UUID NOT NULL REFERENCES matches (id) ON DELETE CASCADE,
    reported_by UUID NOT NULL REFERENCES users (id),
    scores      JSONB NOT NULL,            -- scores déclarés par manche
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE disputes (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    match_id     UUID NOT NULL REFERENCES matches (id) ON DELETE CASCADE,
    opened_by    UUID NOT NULL REFERENCES users (id),
    evidence_url VARCHAR,                  -- screenshot
    status       dispute_status NOT NULL DEFAULT 'open',
    resolved_by  UUID REFERENCES users (id),
    resolution   TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------- Transverse ----------
CREATE TABLE notification_settings (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    channel    notification_channel NOT NULL,
    target     VARCHAR NOT NULL,           -- email ou URL du webhook
    event_type notification_event NOT NULL,
    enabled    BOOLEAN NOT NULL DEFAULT TRUE
);

-- File de tâches du Worker Rust (décision 6.1.1 : table pollée)
CREATE TABLE jobs (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type        job_type NOT NULL,
    status      job_status NOT NULL DEFAULT 'pending',
    payload     JSONB NOT NULL,            -- team_id ou tournament_id, options
    file_url    VARCHAR,                   -- fichier source (import) ou généré (export)
    error       TEXT,
    created_by  UUID REFERENCES users (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);

-- ---------- Index utiles ----------
CREATE INDEX idx_registrations_tournament ON registrations (tournament_id);
CREATE INDEX idx_phases_tournament ON phases (tournament_id);
CREATE INDEX idx_matches_phase ON matches (phase_id);
CREATE INDEX idx_jobs_status ON jobs (status) WHERE status = 'pending';
CREATE INDEX idx_team_members_user ON team_members (user_id);
