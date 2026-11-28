CREATE TABLE ge_history (
    character_id INTEGER NOT NULL,
    idx INTEGER NOT NULL,
    sell BOOLEAN NOT NULL,
    obj INTEGER NOT NULL,
    count INTEGER NOT NULL,
    gold BIGINT NOT NULL,
    FOREIGN KEY (character_id) REFERENCES characters(id) ON DELETE CASCADE,
    UNIQUE (character_id, idx)
);

CREATE INDEX idx_ge_history_character_id ON ge_history(character_id);
