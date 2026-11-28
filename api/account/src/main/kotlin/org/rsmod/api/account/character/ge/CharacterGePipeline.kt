package org.rsmod.api.account.character.ge

import jakarta.inject.Inject
import org.rsmod.api.account.character.CharacterDataStage
import org.rsmod.api.account.character.CharacterMetadataList
import org.rsmod.api.db.DatabaseConnection
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.player.GeSlot

public data class CharacterGeData(val slots: Map<Int, GeSlot>) : CharacterDataStage.Segment

public class CharacterGeApplier @Inject constructor() :
    CharacterDataStage.Applier<CharacterGeData> {
    override fun apply(player: Player, data: CharacterGeData) {
        player.geSlots.fill(null)
        for ((slot, ge) in data.slots) {
            if (slot in player.geSlots.indices) {
                player.geSlots[slot] = ge
            }
        }
    }
}

/** Loads and saves Grand Exchange slots (`ge_slots` table, migration V8). */
public class CharacterGePipeline @Inject constructor(private val applier: CharacterGeApplier) :
    CharacterDataStage.Pipeline {
    override fun append(connection: DatabaseConnection, metadata: CharacterMetadataList) {
        val select =
            connection.prepareStatement(
                """
                    SELECT slot, buy, obj, count, price, collect_obj, collect_count
                    FROM ge_slots
                    WHERE character_id = ?
                """
                    .trimIndent()
            )
        val slots = HashMap<Int, GeSlot>()
        select.use {
            it.setInt(1, metadata.characterId)
            it.executeQuery().use { rs ->
                while (rs.next()) {
                    slots[rs.getInt("slot")] =
                        GeSlot(
                            buy = rs.getBoolean("buy"),
                            obj = rs.getInt("obj"),
                            count = rs.getInt("count"),
                            price = rs.getInt("price"),
                            collectObj = rs.getInt("collect_obj"),
                            collectCount = rs.getInt("collect_count"),
                        )
                }
            }
        }
        metadata.add(applier, CharacterGeData(slots))
    }

    override fun save(connection: DatabaseConnection, player: Player, characterId: Int) {
        connection.prepareStatement("DELETE FROM ge_slots WHERE character_id = ?").use {
            it.setInt(1, characterId)
            it.executeUpdate()
        }
        val filled = player.geSlots.withIndex().filter { it.value != null }
        if (filled.isEmpty()) {
            return
        }
        val insert =
            connection.prepareStatement(
                """
                    INSERT INTO ge_slots
                        (character_id, slot, buy, obj, count, price, collect_obj, collect_count)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """
                    .trimIndent()
            )
        insert.use {
            for ((slot, ge) in filled) {
                ge ?: continue
                it.setInt(1, characterId)
                it.setInt(2, slot)
                it.setBoolean(3, ge.buy)
                it.setInt(4, ge.obj)
                it.setInt(5, ge.count)
                it.setInt(6, ge.price)
                it.setInt(7, ge.collectObj)
                it.setInt(8, ge.collectCount)
                it.addBatch()
            }
            it.executeBatch()
        }
    }
}
