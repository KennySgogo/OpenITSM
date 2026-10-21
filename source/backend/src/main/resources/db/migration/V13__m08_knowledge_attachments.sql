CREATE TABLE knowledge_attachment (
    id uuid PRIMARY KEY,
    article_id uuid NOT NULL REFERENCES knowledge_article(id),
    uploader_id uuid NOT NULL REFERENCES app_user(id),
    file_name text NOT NULL,
    size bigint NOT NULL CHECK (size BETWEEN 1 AND 104857600),
    content_hash varchar(64) NOT NULL,
    created_at timestamptz NOT NULL,
    removed_at timestamptz,
    removed_by uuid REFERENCES app_user(id),
    CHECK ((removed_at IS NULL) = (removed_by IS NULL))
);
CREATE INDEX knowledge_attachment_article ON knowledge_attachment(article_id, created_at, id) WHERE removed_at IS NULL;
