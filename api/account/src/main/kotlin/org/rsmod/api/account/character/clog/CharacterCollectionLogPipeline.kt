package org.rsmod.api.account.character.clog

import jakarta.inject.Inject
import org.rsmod.api.account.character.CharacterDataStage
import org.rsmod.api.account.character.CharacterMetadataList
import org.rsmod.api.db.DatabaseConnection
import org.rsmod.game.entity.Player

public data class CharacterCollectionLogData(
    val obtained: Map<Int, Int>,
    val kills: Map<String, Int>,
) : CharacterDataStage.Segment

public class CharacterCollectionLogApplier @Inject constructor() :
    CharacterDataStage.Applier<CharacterCollectionLogData> {
    override fun apply(player: Player, data: CharacterCollectionLogData) {
        val log = player.collectionLog
        log.clear()
        log.obtained.putAll(data.obtained)
        log.kills.putAll(data.kills)
    }
}

/** Loads and saves the collection log (`collection_log`) and kill counts (`kill_counts`), V10. */
public class CharacterCollectionLogPipeline
@Inject
constructor(private val applier: CharacterCollectionLogApplier) : CharacterDataStage.Pipeline {
    override fun append(connection: DatabaseConnection, metadata: CharacterMetadataList) {
        val obtained = LinkedHashMap<Int, Int>()
        connection
            .prepareStatement("SELECT obj, count FROM collection_log WHERE character_id = ?")
            .use {
                it.setInt(1, metadata.characterId)
                it.executeQuery().use { rs ->
                    while (rs.next()) {
                        obtained[rs.getInt("obj")] = rs.getInt("count")
                    }
                }
            }
        val kills = HashMap<String, Int>()
        connection
            .prepareStatement("SELECT npc, count FROM kill_counts WHERE character_id = ?")
            .use {
                it.setInt(1, metadata.characterId)
                it.executeQuery().use { rs ->
                    while (rs.next()) {
                        kills[rs.getString("npc")] = rs.getInt("count")
                    }
                }
            }
        metadata.add(applier, CharacterCollectionLogData(obtained, kills))
    }

    override fun save(connection: DatabaseConnection, player: Player, characterId: Int) {
        val log = player.collectionLog
        for (table in listOf("collection_log", "kill_counts")) {
            connection.prepareStatement("DELETE FROM $table WHERE character_id = ?").use {
                it.setInt(1, characterId)
                it.executeUpdate()
            }
        }
        if (log.obtained.isNotEmpty()) {
            connection
                .prepareStatement(
                    "INSERT INTO collection_log (character_id, obj, count) VALUES (?, ?, ?)"
                )
                .use {
                    for ((obj, count) in log.obtained) {
                        it.setInt(1, characterId)
                        it.setInt(2, obj)
                        it.setInt(3, count)
                        it.addBatch()
                    }
                    it.executeBatch()
                }
        }
        if (log.kills.isNotEmpty()) {
            connection
                .prepareStatement(
                    "INSERT INTO kill_counts (character_id, npc, count) VALUES (?, ?, ?)"
                )
                .use {
                    for ((npc, count) in log.kills) {
                        it.setInt(1, characterId)
                        it.setString(2, npc)
                        it.setInt(3, count)
                        it.addBatch()
                    }
                    it.executeBatch()
                }
        }
    }
}
