/*Account of the Website*/
CREATE TABLE usersDb
(
    id            BIGINT PRIMARY KEY,
    username      VARCHAR(50) UNIQUE NOT NULL,
    password_hash VARCHAR(255)       NOT NULL,
    role          VARCHAR(20) DEFAULT 'USER',
    created_at    TIMESTAMP   DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE tournamentDb
(
    id          BIGINT PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    start_date  DATE,
    end_date    DATE,
    max_players INTEGER,
    status      VARCHAR(20) DEFAULT 'PENDING'
);

/*No need to have a website account to play in a tournament*/
CREATE TABLE playerDb
(
    id            BIGINT PRIMARY KEY,
    user_id       BIGINT REFERENCES playerDb (id),
    tournament_id BIGINT      NOT NULL REFERENCES tournamentDb (id) ON DELETE CASCADE,
    name          VARCHAR(255) NOT NULL,
    score         INTEGER   DEFAULT 0,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE matchDb
(
    id            BIGINT PRIMARY KEY,
    tournament_id BIGINT NOT NULL REFERENCES tournamentDb (id) ON DELETE CASCADE,
    player1_id    BIGINT NOT NULL REFERENCES playerDb (id),
    player2_id    BIGINT NOT NULL REFERENCES playerDb (id),
    winner_id     BIGINT REFERENCES playerDb (id),
    score_player1 INTEGER   DEFAULT 0,
    score_player2 INTEGER   DEFAULT 0,
    played_at     TIMESTAMP,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tournament_id, player1_id, player2_id)
);