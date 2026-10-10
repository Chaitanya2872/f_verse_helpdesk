CREATE TABLE kb_articles (
    id              BIGSERIAL PRIMARY KEY,
    title           VARCHAR(500) NOT NULL,
    content         TEXT NOT NULL,
    category        VARCHAR(150),
    tags            TEXT,
    status          VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PUBLISHED')),
    author_id       BIGINT NOT NULL REFERENCES users(id),
    view_count      INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_kb_articles_status_category ON kb_articles (status, category);
CREATE INDEX idx_kb_articles_author_id ON kb_articles (author_id);
