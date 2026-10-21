CREATE TABLE knowledge_article (
    id uuid PRIMARY KEY,
    title text NOT NULL CHECK (length(btrim(title)) > 0),
    content text NOT NULL CHECK (length(btrim(content)) > 0),
    category text NOT NULL CHECK (length(btrim(category)) > 0),
    status varchar(16) NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED')),
    author_id uuid NOT NULL REFERENCES app_user(id),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    published_at timestamptz,
    CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL))
);
CREATE INDEX knowledge_article_published ON knowledge_article(updated_at DESC, id DESC) WHERE status = 'PUBLISHED';
CREATE INDEX knowledge_article_author ON knowledge_article(author_id, updated_at DESC, id DESC);
