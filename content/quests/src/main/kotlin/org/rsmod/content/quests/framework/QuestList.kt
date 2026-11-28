package org.rsmod.content.quests.framework

import org.rsmod.api.type.refs.dbrow.DbRowReferences
import org.rsmod.content.quests.animalmagnetism.AnimalMagnetism.Companion.ANIMAL_MAGNETISM
import org.rsmod.content.quests.barcrawl.Barcrawl.Companion.BARCRAWL
import org.rsmod.content.quests.belowicemountain.BelowIceMountain.Companion.BELOW_ICE_MOUNTAIN
import org.rsmod.content.quests.biohazard.Biohazard.Companion.BIOHAZARD
import org.rsmod.content.quests.bigchompy.BigChompy.Companion.BIG_CHOMPY
import org.rsmod.content.quests.blackknights.BlackKnightsFortress.Companion.BLACK_KNIGHTS_FORTRESS
import org.rsmod.content.quests.clocktower.ClockTower.Companion.CLOCK_TOWER
import org.rsmod.content.quests.coldwar.ColdWar.Companion.COLD_WAR
import org.rsmod.content.quests.cooksassistant.CooksAssistant.Companion.COOKS_ASSISTANT
import org.rsmod.content.quests.corsaircurse.CorsairCurse.Companion.CORSAIR_CURSE
import org.rsmod.content.quests.daddyshome.DaddysHome.Companion.DADDYS_HOME
import org.rsmod.content.quests.deathplateau.DeathPlateau.Companion.DEATH_PLATEAU
import org.rsmod.content.quests.demonslayer.DemonSlayer.Companion.DEMON_SLAYER
import org.rsmod.content.quests.doricsquest.DoricsQuest.Companion.DORICS_QUEST
import org.rsmod.content.quests.dragonslayer.DragonSlayer.Companion.DRAGON_SLAYER
import org.rsmod.content.quests.druidicritual.DruidicRitual.Companion.DRUIDIC_RITUAL
import org.rsmod.content.quests.dwarfcannon.DwarfCannon.Companion.DWARF_CANNON
import org.rsmod.content.quests.eadgarsruse.EadgarsRuse.Companion.EADGARS_RUSE
import org.rsmod.content.quests.elementalworkshop1.ElementalWorkshopI.Companion.ELEMENTAL_WORKSHOP_I
import org.rsmod.content.quests.elementalworkshop2.ElementalWorkshopII.Companion.ELEMENTAL_WORKSHOP_II
import org.rsmod.content.quests.ernest.ErnestTheChicken.Companion.ERNEST
import org.rsmod.content.quests.fairytale1.FairytaleI.Companion.FAIRYTALE_I
import org.rsmod.content.quests.fightarena.FightArena.Companion.FIGHT_ARENA
import org.rsmod.content.quests.golem.TheGolem.Companion.THE_GOLEM
import org.rsmod.content.quests.fishingcontest.FishingContest.Companion.FISHING_CONTEST
import org.rsmod.content.quests.fremenniktrials.FremennikTrials.Companion.FREMENNIK_TRIALS
import org.rsmod.content.quests.gertrudescat.GertrudesCat.Companion.GERTRUDES_CAT
import org.rsmod.content.quests.ghostsahoy.GhostsAhoy.Companion.GHOSTS_AHOY
import org.rsmod.content.quests.goblindiplomacy.GoblinDiplomacy.Companion.GOBLIN_DIPLOMACY
import org.rsmod.content.quests.grandtree.GrandTree.Companion.GRAND_TREE
import org.rsmod.content.quests.handinthesand.HandInTheSand.Companion.HAND_IN_THE_SAND
import org.rsmod.content.quests.hazeelcult.HazeelCult.Companion.HAZEEL_CULT
import org.rsmod.content.quests.holygrail.HolyGrail.Companion.HOLY_GRAIL
import org.rsmod.content.quests.idesofmilk.IdesOfMilk.Companion.IDES_OF_MILK
import org.rsmod.content.quests.impcatcher.ImpCatcher.Companion.IMP_CATCHER
import org.rsmod.content.quests.junglepotion.JunglePotion.Companion.JUNGLE_POTION
import org.rsmod.content.quests.knightssword.KnightsSword.Companion.KNIGHTS_SWORD
import org.rsmod.content.quests.lostcity.LostCity.Companion.LOST_CITY
import org.rsmod.content.quests.magearena.MageArena.Companion.MAGE_ARENA_I
import org.rsmod.content.quests.merlinscrystal.MerlinsCrystal.Companion.MERLINS_CRYSTAL
import org.rsmod.content.quests.losttribe.LostTribe.Companion.LOST_TRIBE
import org.rsmod.content.quests.misthalinmystery.MisthalinMystery.Companion.MISTHALIN_MYSTERY
import org.rsmod.content.quests.monksfriend.MonksFriend.Companion.MONKS_FRIEND
import org.rsmod.content.quests.mountaindaughter.MountainDaughter.Companion.MOUNTAIN_DAUGHTER
import org.rsmod.content.quests.mudfoot.MudfootsBigAdventure.Companion.MUDFOOT
import org.rsmod.content.quests.murdermystery.MurderMystery.Companion.MURDER_MYSTERY
import org.rsmod.content.quests.naturespirit.NatureSpirit.Companion.NATURE_SPIRIT
import org.rsmod.content.quests.observatory.ObservatoryQuest.Companion.OBSERVATORY_QUEST
import org.rsmod.content.quests.piratestreasure.PiratesTreasure.Companion.PIRATES_TREASURE
import org.rsmod.content.quests.plaguecity.PlagueCity.Companion.PLAGUE_CITY
import org.rsmod.content.quests.porcine.PorcineOfInterest.Companion.PORCINE
import org.rsmod.content.quests.priestinperil.PriestInPeril.Companion.PRIEST_IN_PERIL
import org.rsmod.content.quests.princeali.PrinceAliRescue.Companion.PRINCE_ALI_RESCUE
import org.rsmod.content.quests.ragandboneman.RagAndBoneManI.Companion.RAG_AND_BONE_MAN_I
import org.rsmod.content.quests.restlessghost.RestlessGhost.Companion.RESTLESS_GHOST
import org.rsmod.content.quests.romeojuliet.RomeoAndJuliet.Companion.ROMEO_AND_JULIET
import org.rsmod.content.quests.runemysteries.RuneMysteries.Companion.RUNE_MYSTERIES
import org.rsmod.content.quests.scorpioncatcher.ScorpionCatcher.Companion.SCORPION_CATCHER
import org.rsmod.content.quests.seaslug.SeaSlug.Companion.SEA_SLUG
import org.rsmod.content.quests.sheepherder.SheepHerder.Companion.SHEEP_HERDER
import org.rsmod.content.quests.sheepshearer.SheepShearer.Companion.SHEEP_SHEARER
import org.rsmod.content.quests.shieldofarrav.ShieldOfArrav.Companion.SHIELD_OF_ARRAV
import org.rsmod.content.quests.skippy.SkippyAndTheMogres.Companion.SKIPPY_AND_THE_MOGRES
import org.rsmod.content.quests.sleepinggiants.SleepingGiants.Companion.SLEEPING_GIANTS
import org.rsmod.content.quests.templeoftheeye.TempleOfTheEye.Companion.TEMPLE_OF_THE_EYE
import org.rsmod.content.quests.toweroflife.TowerOfLife.Companion.TOWER_OF_LIFE
import org.rsmod.content.quests.treegnomevillage.TreeGnomeVillage.Companion.TREE_GNOME_VILLAGE
import org.rsmod.content.quests.tribaltotem.TribalTotem.Companion.TRIBAL_TOTEM
import org.rsmod.content.quests.trollstronghold.TrollStronghold.Companion.TROLL_STRONGHOLD
import org.rsmod.content.quests.vampyreslayer.VampyreSlayer.Companion.VAMPYRE_SLAYER
import org.rsmod.content.quests.waterfall.WaterfallQuest.Companion.WATERFALL_QUEST
import org.rsmod.content.quests.witchshouse.WitchsHouse.Companion.WITCHS_HOUSE
import org.rsmod.content.quests.witchspotion.WitchsPotion.Companion.WITCHS_POTION
import org.rsmod.content.quests.xmarksthespot.XMarksTheSpot.Companion.X_MARKS_THE_SPOT

/**
 * Every quest this server implements. **Add a quest here when it's built**: quest points and
 * QuestTalk ordering use this list.
 */
public object BuiltQuests {
    public val all: List<QuestDef> =
        listOf(
            ANIMAL_MAGNETISM,
            BARCRAWL,
            BELOW_ICE_MOUNTAIN,
            BIOHAZARD,
            BIG_CHOMPY,
            BLACK_KNIGHTS_FORTRESS,
            CLOCK_TOWER,
            COLD_WAR,
            COOKS_ASSISTANT,
            CORSAIR_CURSE,
            DADDYS_HOME,
            DEATH_PLATEAU,
            DEMON_SLAYER,
            DORICS_QUEST,
            DRAGON_SLAYER,
            DRUIDIC_RITUAL,
            DWARF_CANNON,
            EADGARS_RUSE,
            ELEMENTAL_WORKSHOP_I,
            ELEMENTAL_WORKSHOP_II,
            ERNEST,
            FAIRYTALE_I,
            FIGHT_ARENA,
            THE_GOLEM,
            FISHING_CONTEST,
            FREMENNIK_TRIALS,
            GERTRUDES_CAT,
            GHOSTS_AHOY,
            GOBLIN_DIPLOMACY,
            GRAND_TREE,
            HAND_IN_THE_SAND,
            HAZEEL_CULT,
            HOLY_GRAIL,
            IDES_OF_MILK,
            IMP_CATCHER,
            JUNGLE_POTION,
            KNIGHTS_SWORD,
            LOST_CITY,
            MAGE_ARENA_I,
            MERLINS_CRYSTAL,
            LOST_TRIBE,
            MISTHALIN_MYSTERY,
            MONKS_FRIEND,
            MOUNTAIN_DAUGHTER,
            MUDFOOT,
            MURDER_MYSTERY,
            NATURE_SPIRIT,
            OBSERVATORY_QUEST,
            PIRATES_TREASURE,
            PLAGUE_CITY,
            PORCINE,
            PRIEST_IN_PERIL,
            PRINCE_ALI_RESCUE,
            RAG_AND_BONE_MAN_I,
            RESTLESS_GHOST,
            ROMEO_AND_JULIET,
            RUNE_MYSTERIES,
            SCORPION_CATCHER,
            SEA_SLUG,
            SHEEP_HERDER,
            SHEEP_SHEARER,
            SHIELD_OF_ARRAV,
            SKIPPY_AND_THE_MOGRES,
            SLEEPING_GIANTS,
            TEMPLE_OF_THE_EYE,
            TOWER_OF_LIFE,
            TREE_GNOME_VILLAGE,
            TRIBAL_TOTEM,
            TROLL_STRONGHOLD,
            VAMPYRE_SLAYER,
            WATERFALL_QUEST,
            WITCHS_HOUSE,
            WITCHS_POTION,
            X_MARKS_THE_SPOT,
        )

    public val rows: Set<String> = all.mapTo(HashSet()) { it.listRow }
}

/*
 * The quest tab used to hide unbuilt quests (release_type 0 on their dbrows); Ryan asked for the
 * full real list instead (2026-11-28), so no quest rows are edited any more.
 */

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
