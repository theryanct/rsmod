CREATE TABLE ge_slots (
    character_id INTEGER NOT NULL,
    slot INTEGER NOT NULL,
    buy BOOLEAN NOT NULL,
    obj INTEGER NOT NULL,
    count INTEGER NOT NULL,
    price INTEGER NOT NULL,
    collect_obj INTEGER NOT NULL,
    collect_count INTEGER NOT NULL,
    FOREIGN KEY (character_id) REFERENCES characters(id) ON DELETE CASCADE,
    UNIQUE (character_id, slot)
);

CREATE INDEX idx_ge_slots_character_id ON ge_slots(character_id);
