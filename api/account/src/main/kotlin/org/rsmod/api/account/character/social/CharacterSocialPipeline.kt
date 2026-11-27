package org.rsmod.api.account.character.social

import jakarta.inject.Inject
import org.rsmod.api.account.character.CharacterDataStage
import org.rsmod.api.account.character.CharacterMetadataList
import org.rsmod.api.db.DatabaseConnection
import org.rsmod.game.entity.Player

/** Loads and saves the friend and ignore lists (`friends` / `ignores` tables). */
public class CharacterSocialPipeline
@Inject
constructor(private val applier: CharacterSocialApplier) : CharacterDataStage.Pipeline {
    override fun append(connection: DatabaseConnection, metadata: CharacterMetadataList) {
        val friends = connection.loadNames(FRIENDS_TABLE, metadata.characterId)
        val ignores = connection.loadNames(IGNORES_TABLE, metadata.characterId)
        metadata.add(applier, CharacterSocialData(friends, ignores))
    }

    override fun save(connection: DatabaseConnection, player: Player, characterId: Int) {
        connection.replaceNames(FRIENDS_TABLE, characterId, player.friendList)
        connection.replaceNames(IGNORES_TABLE, characterId, player.ignoreList)
    }

    private fun DatabaseConnection.loadNames(table: String, characterId: Int): List<String> {
        val select =
            prepareStatement(
                "SELECT display_name FROM $table WHERE character_id = ? ORDER BY position"
            )
        val names = ArrayList<String>()
        select.use {
            it.setInt(1, characterId)
            it.executeQuery().use { resultSet ->
                while (resultSet.next()) {
                    names += resultSet.getString("display_name")
                }
            }
        }
        return names
    }

    private fun DatabaseConnection.replaceNames(
        table: String,
        characterId: Int,
        names: List<String>,
    ) {
        prepareStatement("DELETE FROM $table WHERE character_id = ?").use {
            it.setInt(1, characterId)
            it.executeUpdate()
        }
        if (names.isEmpty()) {
            return
        }
        val insert =
            prepareStatement(
                "INSERT INTO $table (character_id, position, display_name) VALUES (?, ?, ?)"
            )
        insert.use {
            for ((position, name) in names.withIndex()) {
                it.setInt(1, characterId)
                it.setInt(2, position)
                it.setString(3, name)
                it.addBatch()
            }
            it.executeBatch()
        }
    }

    private companion object {
        private const val FRIENDS_TABLE = "friends"
        private const val IGNORES_TABLE = "ignores"
    }
}
