CREATE TABLE collection_log (
    character_id INTEGER NOT NULL,
    obj INTEGER NOT NULL,
    count INTEGER NOT NULL,
    FOREIGN KEY (character_id) REFERENCES characters(id) ON DELETE CASCADE,
    UNIQUE (character_id, obj)
);

CREATE INDEX idx_collection_log_character_id ON collection_log(character_id);

CREATE TABLE kill_counts (
    character_id INTEGER NOT NULL,
    npc TEXT NOT NULL,
    count INTEGER NOT NULL,
    FOREIGN KEY (character_id) REFERENCES characters(id) ON DELETE CASCADE,
    UNIQUE (character_id, npc)
);

CREATE INDEX idx_kill_counts_character_id ON kill_counts(character_id);
