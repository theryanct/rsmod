package org.rsmod.content.quests.framework

import org.rsmod.api.type.editors.dbrow.DbRowEditor
import org.rsmod.api.type.refs.dbcol.DbColumnReferences
import org.rsmod.api.type.refs.dbrow.DbRowReferences
import org.rsmod.api.type.refs.dbtable.DbTableReferences
import org.rsmod.content.quests.animalmagnetism.AnimalMagnetism.Companion.ANIMAL_MAGNETISM
import org.rsmod.content.quests.belowicemountain.BelowIceMountain.Companion.BELOW_ICE_MOUNTAIN
import org.rsmod.content.quests.biohazard.Biohazard.Companion.BIOHAZARD
import org.rsmod.content.quests.bigchompy.BigChompy.Companion.BIG_CHOMPY
import org.rsmod.content.quests.blackknights.BlackKnightsFortress.Companion.BLACK_KNIGHTS_FORTRESS
import org.rsmod.content.quests.clocktower.ClockTower.Companion.CLOCK_TOWER
import org.rsmod.content.quests.cooksassistant.CooksAssistant.Companion.COOKS_ASSISTANT
import org.rsmod.content.quests.corsaircurse.CorsairCurse.Companion.CORSAIR_CURSE
import org.rsmod.content.quests.deathplateau.DeathPlateau.Companion.DEATH_PLATEAU
import org.rsmod.content.quests.demonslayer.DemonSlayer.Companion.DEMON_SLAYER
import org.rsmod.content.quests.doricsquest.DoricsQuest.Companion.DORICS_QUEST
import org.rsmod.content.quests.dragonslayer.DragonSlayer.Companion.DRAGON_SLAYER
import org.rsmod.content.quests.druidicritual.DruidicRitual.Companion.DRUIDIC_RITUAL
import org.rsmod.content.quests.dwarfcannon.DwarfCannon.Companion.DWARF_CANNON
import org.rsmod.content.quests.elementalworkshop1.ElementalWorkshopI.Companion.ELEMENTAL_WORKSHOP_I
import org.rsmod.content.quests.ernest.ErnestTheChicken.Companion.ERNEST
import org.rsmod.content.quests.fairytale1.FairytaleI.Companion.FAIRYTALE_I
import org.rsmod.content.quests.fightarena.FightArena.Companion.FIGHT_ARENA
import org.rsmod.content.quests.golem.TheGolem.Companion.THE_GOLEM
import org.rsmod.content.quests.fishingcontest.FishingContest.Companion.FISHING_CONTEST
import org.rsmod.content.quests.gertrudescat.GertrudesCat.Companion.GERTRUDES_CAT
import org.rsmod.content.quests.ghostsahoy.GhostsAhoy.Companion.GHOSTS_AHOY
import org.rsmod.content.quests.goblindiplomacy.GoblinDiplomacy.Companion.GOBLIN_DIPLOMACY
import org.rsmod.content.quests.grandtree.GrandTree.Companion.GRAND_TREE
import org.rsmod.content.quests.hazeelcult.HazeelCult.Companion.HAZEEL_CULT
import org.rsmod.content.quests.holygrail.HolyGrail.Companion.HOLY_GRAIL
import org.rsmod.content.quests.impcatcher.ImpCatcher.Companion.IMP_CATCHER
import org.rsmod.content.quests.junglepotion.JunglePotion.Companion.JUNGLE_POTION
import org.rsmod.content.quests.knightssword.KnightsSword.Companion.KNIGHTS_SWORD
import org.rsmod.content.quests.lostcity.LostCity.Companion.LOST_CITY
import org.rsmod.content.quests.merlinscrystal.MerlinsCrystal.Companion.MERLINS_CRYSTAL
import org.rsmod.content.quests.losttribe.LostTribe.Companion.LOST_TRIBE
import org.rsmod.content.quests.misthalinmystery.MisthalinMystery.Companion.MISTHALIN_MYSTERY
import org.rsmod.content.quests.monksfriend.MonksFriend.Companion.MONKS_FRIEND
import org.rsmod.content.quests.murdermystery.MurderMystery.Companion.MURDER_MYSTERY
import org.rsmod.content.quests.naturespirit.NatureSpirit.Companion.NATURE_SPIRIT
import org.rsmod.content.quests.observatory.ObservatoryQuest.Companion.OBSERVATORY_QUEST
import org.rsmod.content.quests.piratestreasure.PiratesTreasure.Companion.PIRATES_TREASURE
import org.rsmod.content.quests.plaguecity.PlagueCity.Companion.PLAGUE_CITY
import org.rsmod.content.quests.priestinperil.PriestInPeril.Companion.PRIEST_IN_PERIL
import org.rsmod.content.quests.princeali.PrinceAliRescue.Companion.PRINCE_ALI_RESCUE
import org.rsmod.content.quests.restlessghost.RestlessGhost.Companion.RESTLESS_GHOST
import org.rsmod.content.quests.romeojuliet.RomeoAndJuliet.Companion.ROMEO_AND_JULIET
import org.rsmod.content.quests.runemysteries.RuneMysteries.Companion.RUNE_MYSTERIES
import org.rsmod.content.quests.seaslug.SeaSlug.Companion.SEA_SLUG
import org.rsmod.content.quests.sheepherder.SheepHerder.Companion.SHEEP_HERDER
import org.rsmod.content.quests.sheepshearer.SheepShearer.Companion.SHEEP_SHEARER
import org.rsmod.content.quests.shieldofarrav.ShieldOfArrav.Companion.SHIELD_OF_ARRAV
import org.rsmod.content.quests.templeoftheeye.TempleOfTheEye.Companion.TEMPLE_OF_THE_EYE
import org.rsmod.content.quests.toweroflife.TowerOfLife.Companion.TOWER_OF_LIFE
import org.rsmod.content.quests.treegnomevillage.TreeGnomeVillage.Companion.TREE_GNOME_VILLAGE
import org.rsmod.content.quests.tribaltotem.TribalTotem.Companion.TRIBAL_TOTEM
import org.rsmod.content.quests.vampyreslayer.VampyreSlayer.Companion.VAMPYRE_SLAYER
import org.rsmod.content.quests.waterfall.WaterfallQuest.Companion.WATERFALL_QUEST
import org.rsmod.content.quests.witchshouse.WitchsHouse.Companion.WITCHS_HOUSE
import org.rsmod.content.quests.witchspotion.WitchsPotion.Companion.WITCHS_POTION
import org.rsmod.content.quests.xmarksthespot.XMarksTheSpot.Companion.X_MARKS_THE_SPOT

/**
 * Every quest this server implements. **Add a quest here when it's built**: the quest tab shows
 * only these (QuestListEditor hides the rest).
 */
public object BuiltQuests {
    public val all: List<QuestDef> =
        listOf(
            ANIMAL_MAGNETISM,
            BELOW_ICE_MOUNTAIN,
            BIOHAZARD,
            BIG_CHOMPY,
            BLACK_KNIGHTS_FORTRESS,
            CLOCK_TOWER,
            COOKS_ASSISTANT,
            CORSAIR_CURSE,
            DEATH_PLATEAU,
            DEMON_SLAYER,
            DORICS_QUEST,
            DRAGON_SLAYER,
            DRUIDIC_RITUAL,
            DWARF_CANNON,
            ELEMENTAL_WORKSHOP_I,
            ERNEST,
            FAIRYTALE_I,
            FIGHT_ARENA,
            THE_GOLEM,
            FISHING_CONTEST,
            GERTRUDES_CAT,
            GHOSTS_AHOY,
            GOBLIN_DIPLOMACY,
            GRAND_TREE,
            HAZEEL_CULT,
            HOLY_GRAIL,
            IMP_CATCHER,
            JUNGLE_POTION,
            KNIGHTS_SWORD,
            LOST_CITY,
            MERLINS_CRYSTAL,
            LOST_TRIBE,
            MISTHALIN_MYSTERY,
            MONKS_FRIEND,
            MURDER_MYSTERY,
            NATURE_SPIRIT,
            OBSERVATORY_QUEST,
            PIRATES_TREASURE,
            PLAGUE_CITY,
            PRIEST_IN_PERIL,
            PRINCE_ALI_RESCUE,
            RESTLESS_GHOST,
            ROMEO_AND_JULIET,
            RUNE_MYSTERIES,
            SEA_SLUG,
            SHEEP_HERDER,
            SHEEP_SHEARER,
            SHIELD_OF_ARRAV,
            TEMPLE_OF_THE_EYE,
            TOWER_OF_LIFE,
            TREE_GNOME_VILLAGE,
            TRIBAL_TOTEM,
            VAMPYRE_SLAYER,
            WATERFALL_QUEST,
            WITCHS_HOUSE,
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
