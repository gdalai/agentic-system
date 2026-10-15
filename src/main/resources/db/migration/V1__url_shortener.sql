CREATE TABLE short_url (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(16)   NOT NULL UNIQUE,
    target_url  VARCHAR(2048) NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE,
    click_count BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE click_event (
    id         BIGSERIAL PRIMARY KEY,
    url_id     BIGINT NOT NULL REFERENCES short_url (id) ON DELETE CASCADE,
    clicked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    referrer   VARCHAR(512),
    user_agent VARCHAR(512)
);

CREATE INDEX idx_click_event_url ON click_event (url_id);
