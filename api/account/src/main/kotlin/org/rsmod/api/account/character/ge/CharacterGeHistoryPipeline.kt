package org.rsmod.api.account.character.ge

import jakarta.inject.Inject
import org.rsmod.api.account.character.CharacterDataStage
import org.rsmod.api.account.character.CharacterMetadataList
import org.rsmod.api.db.DatabaseConnection
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.player.GeHistoryEntry

public data class CharacterGeHistoryData(val entries: List<GeHistoryEntry>) :
    CharacterDataStage.Segment

public class CharacterGeHistoryApplier @Inject constructor() :
    CharacterDataStage.Applier<CharacterGeHistoryData> {
    override fun apply(player: Player, data: CharacterGeHistoryData) {
        player.geHistory.clear()
        player.geHistory.addAll(data.entries.take(GeHistoryEntry.MAX_ENTRIES))
    }
}

/** Loads and saves the Grand Exchange trade history (`ge_history`, migration V9), newest first. */
public class CharacterGeHistoryPipeline
@Inject
constructor(private val applier: CharacterGeHistoryApplier) : CharacterDataStage.Pipeline {
    override fun append(connection: DatabaseConnection, metadata: CharacterMetadataList) {
        val select =
            connection.prepareStatement(
                """
                    SELECT sell, obj, count, gold
                    FROM ge_history
                    WHERE character_id = ?
                    ORDER BY idx
                """
                    .trimIndent()
            )
        val entries = ArrayList<GeHistoryEntry>()
        select.use {
            it.setInt(1, metadata.characterId)
            it.executeQuery().use { rs ->
                while (rs.next()) {
                    entries +=
                        GeHistoryEntry(
                            sell = rs.getBoolean("sell"),
                            obj = rs.getInt("obj"),
                            count = rs.getInt("count"),
                            gold = rs.getLong("gold"),
                        )
                }
            }
        }
        metadata.add(applier, CharacterGeHistoryData(entries))
    }

    override fun save(connection: DatabaseConnection, player: Player, characterId: Int) {
        connection.prepareStatement("DELETE FROM ge_history WHERE character_id = ?").use {
            it.setInt(1, characterId)
            it.executeUpdate()
        }
        if (player.geHistory.isEmpty()) {
            return
        }
        val insert =
            connection.prepareStatement(
                """
                    INSERT INTO ge_history (character_id, idx, sell, obj, count, gold)
                    VALUES (?, ?, ?, ?, ?, ?)
                """
                    .trimIndent()
            )
        insert.use {
            for ((index, entry) in player.geHistory.take(GeHistoryEntry.MAX_ENTRIES).withIndex()) {
                it.setInt(1, characterId)
                it.setInt(2, index)
                it.setBoolean(3, entry.sell)
                it.setInt(4, entry.obj)
                it.setInt(5, entry.count)
                it.setLong(6, entry.gold)
                it.addBatch()
            }
            it.executeBatch()
        }
    }
}
