CREATE TABLE friends (
    character_id INTEGER NOT NULL,
    position INTEGER NOT NULL,
    display_name TEXT NOT NULL,
    FOREIGN KEY (character_id) REFERENCES characters(id) ON DELETE CASCADE,
    UNIQUE (character_id, position)
);

CREATE INDEX idx_friends_character_id ON friends(character_id);

CREATE TABLE ignores (
    character_id INTEGER NOT NULL,
    position INTEGER NOT NULL,
    display_name TEXT NOT NULL,
    FOREIGN KEY (character_id) REFERENCES characters(id) ON DELETE CASCADE,
    UNIQUE (character_id, position)
);

CREATE INDEX idx_ignores_character_id ON ignores(character_id);
