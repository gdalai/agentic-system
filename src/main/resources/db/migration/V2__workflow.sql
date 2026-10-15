CREATE TABLE workflow_run (
    id                    UUID PRIMARY KEY,
    scenario              VARCHAR(32)   NOT NULL,
    requirement           VARCHAR(4000) NOT NULL,
    requirement_version   INT           NOT NULL,
    autonomy_level        VARCHAR(32)   NOT NULL,
    approval_required_for VARCHAR(512),
    status                VARCHAR(32)   NOT NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE task_execution (
    id          BIGSERIAL PRIMARY KEY,
    run_id      UUID         NOT NULL REFERENCES workflow_run (id) ON DELETE CASCADE,
    task_id     VARCHAR(64)  NOT NULL,
    agent_type  VARCHAR(64)  NOT NULL,
    depends_on  VARCHAR(1024),
    status      VARCHAR(32)  NOT NULL,
    attempts    INT          NOT NULL DEFAULT 0,
    outputs     VARCHAR(8000),
    error       VARCHAR(1000),
    started_at  TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_task_execution_run_task UNIQUE (run_id, task_id)
);

CREATE TABLE run_event (
    id          BIGSERIAL PRIMARY KEY,
    run_id      UUID        NOT NULL REFERENCES workflow_run (id) ON DELETE CASCADE,
    task_id     VARCHAR(64),
    event_type  VARCHAR(48) NOT NULL,
    from_status VARCHAR(32),
    to_status   VARCHAR(32),
    detail      VARCHAR(1000),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_run_event_run ON run_event (run_id, id);
