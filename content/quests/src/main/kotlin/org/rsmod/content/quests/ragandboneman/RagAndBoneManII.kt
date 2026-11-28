package org.rsmod.content.quests.ragandboneman

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.isQuestComplete
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.content.quests.ragandboneman.RagAndBoneManI.Companion.RAG_AND_BONE_MAN_I
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Rag and Bone Man II (OSRS Wiki). Quest varbit `rag_quest_2`, end state 2 (needs Rag and Bone Man
 * I). The Odd Old Man's wish list: 27 more bones (wolf, bat, rat, baby blue dragon, ogre, jogre,
 * zogre, mogre, dagannoth, snake, zombie, werewolf, moss, fire and ice giant, terrorbird, ghoul,
 * troll, seagull, undead cow, experiment, rabbit, basilisk, lizard, cave goblin, vulture, jackal),
 * each a 1 in 4 drop from its monster while still wanted, boiled in vinegar on his pot-boiler as in
 * the first quest. Hand-ins are server varp `rb2_progress` (bit per bone). The last one: 1 QP,
 * 5,000 Prayer XP and the ram skull helm or the bonesack (or both, with space).
 *
 * Simplified: the drop chance is a flat 1 in 4 (Wiki) from any monster of that name.
 */
class RagAndBoneManII
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val objTypes: ObjTypeList,
    private val objRepo: ObjRepository,
    private val random: GameRandom,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(RagAndBoneManII::class.java, "rag_and_bone_man_2") }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, RbNpcs.odd_old_man, RAG_AND_BONE_MAN_II, wants = { wants() }) { talkOldMan(it) }
        }
        onEvent<NpcKilledEvent> { npcKilled(this) }
        for (bone in Rb2Objs.bones) {
            onOpHeldU(bone.raw, RbObjs.pot_vinegar) { potBone(bone) }
            for (boiler in RbLocs.boilers) onOpLocU(boiler, bone.potted) { boil() }
        }
    }

    private fun ProtectedAccess.wants(): Boolean =
        player.isQuestComplete(RAG_AND_BONE_MAN_I) && player.questState(RAG_AND_BONE_MAN_II) < COMPLETE

    private val ProtectedAccess.state: Int
        get() = player.questState(RAG_AND_BONE_MAN_II)

    private fun Player.handed(): Int = vars[Rb2Varps.progress]

    private fun ProtectedAccess.isHanded(bone: Rb2Bone): Boolean = (player.handed() and (1 shl bone.index)) != 0

    private suspend fun ProtectedAccess.talkOldMan(npc: Npc) {
        when {
            Rb2Objs.bones.any { !isHanded(it) && carries(it.polished) } -> handIn(npc)
            Rb2Objs.bones.any { !isHanded(it) && (carries(it.raw) || carries(it.potted)) } ->
                runQuestSection(npc, dialogue, "Trying to hand in unclean bones")
            else -> {
                runQuestSection(npc, dialogue, "Talking to the Odd Old Man without any bones")
                if (state == 0) player.setQuestState(RAG_AND_BONE_MAN_II, STARTED)
                val left = Rb2Objs.bones.count { !isHanded(it) }
                mes("The Odd Old Man still wants $left more bones.") // Authored (not Wiki).
            }
        }
    }

    private suspend fun ProtectedAccess.handIn(npc: Npc) {
        if (state == 0) player.setQuestState(RAG_AND_BONE_MAN_II, STARTED)
        var handed = player.handed()
        for (bone in Rb2Objs.bones) {
            if (handed and (1 shl bone.index) != 0 || !carries(bone.polished)) continue
            invDel(inv, bone.polished)
            handed = handed or (1 shl bone.index)
        }
        VarPlayerIntMapSetter.set(player, Rb2Varps.progress, handed)
        if (Rb2Objs.bones.any { handed and (1 shl it.index) == 0 }) {
            runQuestSection(npc, dialogue, "Handing in some bones")
            return
        }
        var helm = false
        var sack = false
        val hooks =
            SimpleQuestHooks(
                conditions = mapOf("If the player's inventory is full:" to { inv.freeSpace() < 2 }),
                options =
                    mapOf(
                        "The helm" to { helm = true },
                        "The bonesack" to { sack = true },
                        "Can I have both?" to {
                            helm = inv.freeSpace() >= 2
                            sack = inv.freeSpace() >= 2
                        },
                    ),
            )
        runQuestSection(npc, dialogue, "Finishing up", hooks)
        if (helm) invAddOrDrop(objRepo, Rb2Objs.helm)
        if (sack) invAddOrDrop(objRepo, Rb2Objs.bonesack)
        if (!helm && !sack) invAddOrDrop(objRepo, Rb2Objs.helm)
        statAdvance(stats.prayer, PRAYER_XP * xpMods.get(player, stats.prayer))
        completeQuest(RAG_AND_BONE_MAN_II)
    }

    private fun ProtectedAccess.potBone(bone: Rb2Bone) {
        invDel(inv, bone.raw)
        invReplace(inv, RbObjs.pot_vinegar, 1, bone.potted)
        mes("You put the bone in the pot of vinegar.") // Authored (not Wiki).
    }

    private suspend fun ProtectedAccess.boil() {
        if (!carries(RbObjs.logs) || !carries(RbObjs.tinderbox)) {
            mes("You need some logs and a tinderbox to light the pot-boiler.") // Authored (not Wiki).
            return
        }
        invDel(inv, RbObjs.logs)
        mes("You light the logs under the pot-boiler.") // Authored (not Wiki).
        for (bone in Rb2Objs.bones) {
            while (carries(bone.potted)) {
                delay(BOIL_TICKS)
                invReplace(inv, bone.potted, 1, bone.polished)
                invAddOrDrop(objRepo, RbObjs.pot_empty)
                mes("The vinegar boils away, leaving a nice clean bone.") // Authored (not Wiki).
            }
        }
    }

    private fun npcKilled(event: NpcKilledEvent) {
        val player = event.hero
        if (player.questState(RAG_AND_BONE_MAN_II) != STARTED) return
        val name = event.npc.type.name.lowercase()
        val bone = Rb2Objs.bones.firstOrNull { name in it.monsters } ?: return
        if (player.handed() and (1 shl bone.index) != 0) return
        if (listOf(bone.raw, bone.potted, bone.polished).any { player.inv.count(objTypes[it]) > 0 }) return
        if (random.of(1, DROP_ODDS) != 1) return
        objRepo.add(bone.raw, event.npc.coords, DROP_TICKS, player)
    }

    companion object {
        const val STARTED = 1
        const val COMPLETE = 2

        private const val PRAYER_XP = 5_000.0
        private const val BOIL_TICKS = 3
        private const val DROP_ODDS = 4
        private const val DROP_TICKS = 200

        val RAG_AND_BONE_MAN_II =
            QuestDef(
                name = "Rag and Bone Man II",
                varbit = { Rb2VarBits.quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("5,000 Prayer XP", "Ram skull helm or bonesack"),
                scrollObj = { Rb2Objs.bonesack },
                listRow = "quest_ragandboneman2",
            )
    }
}

/** One wished-for bone: its bit, monsters (lower-case npc names) and raw/potted/polished objs. */
internal class Rb2Bone(
    val name: String,
    val index: Int,
    val monsters: Set<String>,
    val raw: ObjType,
    val potted: ObjType,
    val polished: ObjType,
)

internal object Rb2Objs : ObjReferences() {
    val helm = find("rag_ram_helm")
    val bonesack = find("rag_bonesack")
    private val wolf = find("rag_wolf_bone")
    private val wolf_pot = find("rag_pot_wolf_bone")
    private val wolf_polished = find("rag_polished_wolf_bone")
    private val bat = find("rag_bat_bone")
    private val bat_pot = find("rag_pot_bat_bone")
    private val bat_polished = find("rag_polished_bat_bone")
    private val rat = find("rag_rat_bone")
    private val rat_pot = find("rag_pot_rat_bone")
    private val rat_polished = find("rag_polished_rat_bone")
    private val baby_blue_dragon = find("rag_baby_blue_dragon_bone")
    private val baby_blue_dragon_pot = find("rag_pot_baby_blue_dragon_bone")
    private val baby_blue_dragon_polished = find("rag_polished_baby_blue_dragon_bone")
    private val ogre = find("rag_ogre_bone")
    private val ogre_pot = find("rag_pot_ogre_bone")
    private val ogre_polished = find("rag_polished_ogre_bone")
    private val jogre = find("rag_jogre_bone")
    private val jogre_pot = find("rag_pot_jogre_bone")
    private val jogre_polished = find("rag_polished_jogre_bone")
    private val zogre = find("rag_zogre_bone")
    private val zogre_pot = find("rag_pot_zogre_bone")
    private val zogre_polished = find("rag_polished_zogre_bone")
    private val mogre = find("rag_mogre_bone")
    private val mogre_pot = find("rag_pot_mogre_bone")
    private val mogre_polished = find("rag_polished_mogre_bone")
    private val dagganoth = find("rag_dagganoth_bone")
    private val dagganoth_pot = find("rag_pot_dagganoth_bone")
    private val dagganoth_polished = find("rag_polished_dagganoth_bone")
    private val snake = find("rag_snake_bone")
    private val snake_pot = find("rag_pot_snake_bone")
    private val snake_polished = find("rag_polished_snake_bone")
    private val zombie = find("rag_zombie_bone")
    private val zombie_pot = find("rag_pot_zombie_bone")
    private val zombie_polished = find("rag_polished_zombie_bone")
    private val werewolf = find("rag_werewolf_bone")
    private val werewolf_pot = find("rag_pot_werewolf_bone")
    private val werewolf_polished = find("rag_polished_werewolf_bone")
    private val moss_giant = find("rag_moss_giant_bone")
    private val moss_giant_pot = find("rag_pot_moss_giant_bone")
    private val moss_giant_polished = find("rag_polished_moss_giant_bone")
    private val fire_giant = find("rag_fire_giant_bone")
    private val fire_giant_pot = find("rag_pot_fire_giant_bone")
    private val fire_giant_polished = find("rag_polished_fire_giant_bone")
    private val ice_giant = find("rag_ice_giant_bone")
    private val ice_giant_pot = find("rag_pot_ice_giant_bone")
    private val ice_giant_polished = find("rag_polished_ice_giant_bone")
    private val terrorbird = find("rag_terrorbird_bone")
    private val terrorbird_pot = find("rag_pot_terrorbird_bone")
    private val terrorbird_polished = find("rag_polished_terrorbird_bone")
    private val ghoul = find("rag_ghoul_bone")
    private val ghoul_pot = find("rag_pot_ghoul_bone")
    private val ghoul_polished = find("rag_polished_ghoul_bone")
    private val troll = find("rag_troll_bone")
    private val troll_pot = find("rag_pot_troll_bone")
    private val troll_polished = find("rag_polished_troll_bone")
    private val seagull = find("rag_seagull_bone")
    private val seagull_pot = find("rag_pot_seagull_bone")
    private val seagull_polished = find("rag_polished_seagull_bone")
    private val undead_cow = find("rag_undead_cow_bone")
    private val undead_cow_pot = find("rag_pot_undead_cow_bone")
    private val undead_cow_polished = find("rag_polished_undead_cow_bone")
    private val experiment = find("rag_experiment_bone")
    private val experiment_pot = find("rag_pot_experiment_bone")
    private val experiment_polished = find("rag_polished_experiment_bone")
    private val rabbit = find("rag_rabbit_bone")
    private val rabbit_pot = find("rag_pot_rabbit_bone")
    private val rabbit_polished = find("rag_polished_rabbit_bone")
    private val baby_basilisk = find("rag_baby_basilisk_bone")
    private val baby_basilisk_pot = find("rag_pot_baby_basilisk_bone")
    private val baby_basilisk_polished = find("rag_polished_baby_basilisk_bone")
    private val desert_lizard = find("rag_desert_lizard_bone")
    private val desert_lizard_pot = find("rag_pot_desert_lizard_bone")
    private val desert_lizard_polished = find("rag_polished_desert_lizard_bone")
    private val cave_goblin = find("rag_cave_goblin_bone")
    private val cave_goblin_pot = find("rag_pot_cave_goblin_bone")
    private val cave_goblin_polished = find("rag_polished_cave_goblin_bone")
    private val vulture = find("rag_vulture_bone")
    private val vulture_pot = find("rag_pot_vulture_bone")
    private val vulture_polished = find("rag_polished_vulture_bone")
    private val jackal = find("rag_jackal_bone")
    private val jackal_pot = find("rag_pot_jackal_bone")
    private val jackal_polished = find("rag_polished_jackal_bone")
    val bones: List<Rb2Bone> by lazy {
        listOf(
            Rb2Bone("Wolf", 0, setOf("wolf", "white wolf", "big wolf", "jungle wolf", "desert wolf", "ice wolf"), wolf, wolf_pot, wolf_polished),
            Rb2Bone("Bat", 1, setOf("bat"), bat, bat_pot, bat_polished),
            Rb2Bone("Rat", 2, setOf("rat", "dungeon rat", "crypt rat"), rat, rat_pot, rat_polished),
            Rb2Bone("Baby blue dragon", 3, setOf("baby blue dragon"), baby_blue_dragon, baby_blue_dragon_pot, baby_blue_dragon_polished),
            Rb2Bone("Ogre", 4, setOf("ogre"), ogre, ogre_pot, ogre_polished),
            Rb2Bone("Jogre", 5, setOf("jogre"), jogre, jogre_pot, jogre_polished),
            Rb2Bone("Zogre", 6, setOf("zogre"), zogre, zogre_pot, zogre_polished),
            Rb2Bone("Mogre", 7, setOf("mogre"), mogre, mogre_pot, mogre_polished),
            Rb2Bone("Dagannoth", 8, setOf("dagannoth"), dagganoth, dagganoth_pot, dagganoth_polished),
            Rb2Bone("Snake", 9, setOf("snake"), snake, snake_pot, snake_polished),
            Rb2Bone("Zombie", 10, setOf("zombie"), zombie, zombie_pot, zombie_polished),
            Rb2Bone("Werewolf", 11, setOf("werewolf"), werewolf, werewolf_pot, werewolf_polished),
            Rb2Bone("Moss giant", 12, setOf("moss giant"), moss_giant, moss_giant_pot, moss_giant_polished),
            Rb2Bone("Fire giant", 13, setOf("fire giant"), fire_giant, fire_giant_pot, fire_giant_polished),
            Rb2Bone("Ice giant", 14, setOf("ice giant"), ice_giant, ice_giant_pot, ice_giant_polished),
            Rb2Bone("Terrorbird", 15, setOf("terrorbird"), terrorbird, terrorbird_pot, terrorbird_polished),
            Rb2Bone("Ghoul", 16, setOf("ghoul"), ghoul, ghoul_pot, ghoul_polished),
            Rb2Bone("Troll", 17, setOf("mountain troll", "thrower troll", "troll", "ice troll", "river troll", "troll general"), troll, troll_pot, troll_polished),
            Rb2Bone("Seagull", 18, setOf("seagull"), seagull, seagull_pot, seagull_polished),
            Rb2Bone("Undead cow", 19, setOf("undead cow"), undead_cow, undead_cow_pot, undead_cow_polished),
            Rb2Bone("Experiment", 20, setOf("experiment"), experiment, experiment_pot, experiment_polished),
            Rb2Bone("Rabbit", 21, setOf("rabbit"), rabbit, rabbit_pot, rabbit_polished),
            Rb2Bone("Basilisk", 22, setOf("basilisk"), baby_basilisk, baby_basilisk_pot, baby_basilisk_polished),
            Rb2Bone("Lizard", 23, setOf("lizard", "desert lizard", "small lizard"), desert_lizard, desert_lizard_pot, desert_lizard_polished),
            Rb2Bone("Cave goblin", 24, setOf("cave goblin"), cave_goblin, cave_goblin_pot, cave_goblin_polished),
            Rb2Bone("Vulture", 25, setOf("vulture"), vulture, vulture_pot, vulture_polished),
            Rb2Bone("Jackal", 26, setOf("jackal"), jackal, jackal_pot, jackal_polished),
        )
    }
}

internal object Rb2VarBits : VarBitReferences() {
    val quest = find("rag_quest_2")
}

internal object Rb2Varps : VarpReferences() {
    /** Server varp (65802): bits 0-26 the bones handed in. */
    val progress = find("rb2_progress")
}

internal object Rb2VarpBuilds : VarpBuilder() {
    init {
        build("rb2_progress")
    }
}
