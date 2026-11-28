package org.rsmod.content.quests.framework

import org.rsmod.api.type.editors.dbrow.DbRowEditor
import org.rsmod.api.type.refs.dbcol.DbColumnReferences
import org.rsmod.api.type.refs.dbrow.DbRowReferences
import org.rsmod.api.type.refs.dbtable.DbTableReferences
import org.rsmod.content.quests.belowicemountain.BelowIceMountain.Companion.BELOW_ICE_MOUNTAIN
import org.rsmod.content.quests.blackknights.BlackKnightsFortress.Companion.BLACK_KNIGHTS_FORTRESS
import org.rsmod.content.quests.cooksassistant.CooksAssistant.Companion.COOKS_ASSISTANT
import org.rsmod.content.quests.corsaircurse.CorsairCurse.Companion.CORSAIR_CURSE
import org.rsmod.content.quests.demonslayer.DemonSlayer.Companion.DEMON_SLAYER
import org.rsmod.content.quests.doricsquest.DoricsQuest.Companion.DORICS_QUEST
import org.rsmod.content.quests.dragonslayer.DragonSlayer.Companion.DRAGON_SLAYER
import org.rsmod.content.quests.druidicritual.DruidicRitual.Companion.DRUIDIC_RITUAL
import org.rsmod.content.quests.ernest.ErnestTheChicken.Companion.ERNEST
import org.rsmod.content.quests.fishingcontest.FishingContest.Companion.FISHING_CONTEST
import org.rsmod.content.quests.goblindiplomacy.GoblinDiplomacy.Companion.GOBLIN_DIPLOMACY
import org.rsmod.content.quests.impcatcher.ImpCatcher.Companion.IMP_CATCHER
import org.rsmod.content.quests.knightssword.KnightsSword.Companion.KNIGHTS_SWORD
import org.rsmod.content.quests.misthalinmystery.MisthalinMystery.Companion.MISTHALIN_MYSTERY
import org.rsmod.content.quests.monksfriend.MonksFriend.Companion.MONKS_FRIEND
import org.rsmod.content.quests.piratestreasure.PiratesTreasure.Companion.PIRATES_TREASURE
import org.rsmod.content.quests.plaguecity.PlagueCity.Companion.PLAGUE_CITY
import org.rsmod.content.quests.princeali.PrinceAliRescue.Companion.PRINCE_ALI_RESCUE
import org.rsmod.content.quests.restlessghost.RestlessGhost.Companion.RESTLESS_GHOST
import org.rsmod.content.quests.romeojuliet.RomeoAndJuliet.Companion.ROMEO_AND_JULIET
import org.rsmod.content.quests.runemysteries.RuneMysteries.Companion.RUNE_MYSTERIES
import org.rsmod.content.quests.sheepshearer.SheepShearer.Companion.SHEEP_SHEARER
import org.rsmod.content.quests.shieldofarrav.ShieldOfArrav.Companion.SHIELD_OF_ARRAV
import org.rsmod.content.quests.vampyreslayer.VampyreSlayer.Companion.VAMPYRE_SLAYER
import org.rsmod.content.quests.witchspotion.WitchsPotion.Companion.WITCHS_POTION
import org.rsmod.content.quests.xmarksthespot.XMarksTheSpot.Companion.X_MARKS_THE_SPOT

/**
 * Every quest this server implements. **Add a quest here when it's built**: the quest tab shows
 * only these (QuestListEditor hides the rest).
 */
public object BuiltQuests {
    public val all: List<QuestDef> =
        listOf(
            BELOW_ICE_MOUNTAIN,
            BLACK_KNIGHTS_FORTRESS,
            COOKS_ASSISTANT,
            CORSAIR_CURSE,
            DEMON_SLAYER,
            DORICS_QUEST,
            DRAGON_SLAYER,
            DRUIDIC_RITUAL,
            ERNEST,
            FISHING_CONTEST,
            GOBLIN_DIPLOMACY,
            IMP_CATCHER,
            KNIGHTS_SWORD,
            MISTHALIN_MYSTERY,
            MONKS_FRIEND,
            PIRATES_TREASURE,
            PLAGUE_CITY,
            PRINCE_ALI_RESCUE,
            RESTLESS_GHOST,
            ROMEO_AND_JULIET,
            RUNE_MYSTERIES,
            SHEEP_SHEARER,
            SHIELD_OF_ARRAV,
            VAMPYRE_SLAYER,
            WITCHS_POTION,
            X_MARKS_THE_SPOT,
        )

    public val rows: Set<String> = all.mapTo(HashSet()) { it.listRow }
}

/**
 * Hides unbuilt quests from the quest tab. The rev 243 client builds the list from dbtable
 * `quest`; its `questlist_hide_quest` (cs2 3238) skips any row whose `quest:release_type` is 0
 * (column 3: `db_getfield(row, quest:release_type, 0) == 0 -> hidden`). So every quest row that
 * isn't in [BuiltQuests] gets release_type 0; built quests keep their cache value.
 *
 * Only that one column changes (DbRowTypeBuilder.merge keeps the other columns and the larger
 * column count). Nothing finds quest rows by identity hash, so the edit can't trip the hash
 * verifier.
 */
internal object QuestListEditor : DbRowEditor() {
    init {
        for ((name, row) in QuestListRows.byName) {
            if (name in BuiltQuests.rows) continue
            edit(row) {
                table = QuestListTables.quest
                column(QuestListColumns.release_type) { value = HIDDEN }
            }
        }
    }

    private const val HIDDEN = 0
}

internal object QuestListRows : DbRowReferences() {
    /** Every quest row in the cache (resources quest_rows.txt, from .data/symbols/dbrow.sym). */
    val byName =
        checkNotNull(QuestListRows::class.java.getResourceAsStream("quest_rows.txt")) {
                "quest_rows.txt missing"
            }
            .bufferedReader()
            .useLines { lines ->
                lines
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toList()
            }
            .associateWith { find(it) }
}

internal object QuestListTables : DbTableReferences() {
    val quest = find("quest")
}

internal object QuestListColumns : DbColumnReferences() {
    val release_type = int("quest:release_type")
}
