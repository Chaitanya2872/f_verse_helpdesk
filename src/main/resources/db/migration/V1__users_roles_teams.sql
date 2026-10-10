CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    erp_user_id     VARCHAR(100) UNIQUE NOT NULL,
    email           VARCHAR(255),
    name            VARCHAR(255),
    is_active       BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE roles (
    id      BIGSERIAL PRIMARY KEY,
    name    VARCHAR(50) UNIQUE NOT NULL
);

CREATE TABLE user_roles (
    user_id     BIGINT NOT NULL REFERENCES users(id),
    role_id     BIGINT NOT NULL REFERENCES roles(id),
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE teams (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(255) NOT NULL,
    description         TEXT,
    working_hours_json  JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE team_members (
    team_id     BIGINT NOT NULL REFERENCES teams(id),
    user_id     BIGINT NOT NULL REFERENCES users(id),
    is_lead     BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (team_id, user_id)
);
