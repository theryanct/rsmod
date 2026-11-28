package org.rsmod.content.quests.mudfoot

import jakarta.inject.Inject
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.isQuestComplete
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Mudfoot's Big Adventure (OSRS Wiki). Quest varbit `mba` (bits 0-8 of `mba_primary`), end state
 * 32. Where Mudfoot stands is `mba_mudfoot_vis` (1 the goblin house, 2 following, 3 windmill,
 * 4 woods, 5 graveyard); the cache multis show the outlaws at the general store (6), the windmill
 * (10-12, no op: Mudfoot starts that fight), the woods (14-18) and the graveyard (20-24).
 * - 2 Captain Harlan (Lumbridge general store) offers the bounty. Mudfoot (south of the cow field)
 *   says humans dumped the loot on the goblins and follows you (6). Confronting the ranger at the
 *   store: he shrugs off Mudfoot's warhammer; Harlan explains the combat triangle (10).
 * - The ranger by the windmill (stab), the mage by the H.A.M. hideout (ranged), the armoured warrior
 *   in the graveyard who prays against melee (magic or ranged) are fought in turn (14, 20, 26).
 * - 32 Harlan: 1 QP, four antique lamps (500 XP in a combat skill each), 500 coins, and his
 *   "Claim-weapons" basic kit every 30 minutes.
 *
 * Simplified: Mudfoot follows only from the goblin house to the store (then waits at each fight);
 * the combat comments ignore your weapon; the claim cooldown isn't saved across logins.
 */
class MudfootsBigAdventure
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val aiInteractions: AiPlayerInteractions,
    private val protectedAccess: ProtectedAccessLauncher,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(MudfootsBigAdventure::class.java, "mudfoots_big_adventure") }

    private val followers = HashMap<Int, Npc>()
    private val lastClaim = HashMap<Int, Long>()

    private val speakers: Map<String, NpcType> by lazy {
        mapOf(
            "Mudfoot" to MbaNpcs.mudfoot_vis,
            "Captain Harlan" to MbaNpcs.harlan,
            "Outlaw" to MbaNpcs.outlaw_ranged_vis,
        )
    }

    override fun ScriptContext.startup() {
        with(QuestTalk) {
            register(npcTypes, MbaNpcs.harlan, MUDFOOT, wants = { state < COMPLETE }) { talkHarlan(it) }
            default(npcTypes, MbaNpcs.harlan) {
                startDialogue(it) { chatNpc(happy, "Hello again, adventurer. Stay safe out there.") } // Authored.
            }
        }
        onNpcOpLabel(npcTypes, MbaNpcs.harlan, "Claim-weapons") { claimWeapons() }
        // Mudfoot's and the outlaws' multinpcs have no ops of their own.
        onOpNpc1(MbaNpcs.mudfoot_house) { talkMudfoot(it.npc) }
        onOpNpc1(MbaNpcs.mudfoot_follower) { talkMudfoot(it.npc) }
        onOpNpc1(MbaNpcs.mudfoot_windmill) { talkMudfoot(it.npc) }
        onOpNpc1(MbaNpcs.mudfoot_woods) { talkMudfoot(it.npc) }
        onOpNpc1(MbaNpcs.mudfoot_graveyard) { talkMudfoot(it.npc) }
        onOpNpc1(MbaNpcs.outlaw_store) { confrontStore(it.npc) }
        onOpNpc1(MbaNpcs.outlaw_woods) { fight(MAGE, it.npc) }
        onOpNpc1(MbaNpcs.outlaw_graveyard) { fight(WARRIOR, it.npc) }
        onEvent<NpcKilledEvent> { outlawKilled(this) }
        onPlayerLogout { removeFollower(player) }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(MUDFOOT)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(MUDFOOT, value)
    }

    private fun ProtectedAccess.setMudfoot(where: Int) {
        VarPlayerIntMapSetter.set(player, MbaVarBits.mudfoot, where)
    }

    /* Captain Harlan */

    private suspend fun ProtectedAccess.talkHarlan(npc: Npc) {
        when (state) {
            0 -> {
                var start = false
                runQuestSection(npc, dialogue, "Starting off", SimpleQuestHooks(options = mapOf("Yes." to { start = true })))
                if (start) {
                    setState(STARTED)
                    setMudfoot(AT_HOUSE)
                }
            }
            in STARTED until FOLLOWING -> runQuestSection(npc, dialogue, "Talking to Harlan again after starting the quest")
            FOLLOWING -> runQuestSection(npc, dialogue, "Talking to Captain Harlan", speakers = speakers)
            BEATEN_ALL -> finish(npc)
            else -> runQuestSection(npc, dialogue, "Talking to Captain Harlan again without Mudfoot")
        }
    }

    private suspend fun ProtectedAccess.finish(npc: Npc) {
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has Mudfoot with them:" to { true },
                        "If the player does not have Mudfoot with them:" to { false },
                        "If the player has no inventory space:" to { inv.freeSpace() < 2 },
                        "If the player has inventory space:" to { inv.freeSpace() >= 2 },
                    )
            )
        runQuestSection(npc, dialogue, "Finishing up", hooks, speakers)
        setMudfoot(0)
        invAddOrDrop(objRepo, MbaObjs.lamp, LAMPS)
        invAddOrDrop(objRepo, MbaObjs.coins, REWARD_COINS)
        completeQuest(MUDFOOT)
    }

    /** Harlan's basic kit (Wiki: unlocked during the quest, every 30 minutes). */
    private suspend fun ProtectedAccess.claimWeapons() {
        if (state < CONFRONTED && !player.isQuestComplete(MUDFOOT)) {
            mes("Captain Harlan has nothing for you yet.") // Authored (not Wiki).
            return
        }
        val now = System.currentTimeMillis()
        val last = lastClaim[player.uid.packed] ?: 0L
        if (now - last < CLAIM_COOLDOWN_MS) {
            mes("You've already received some equipment recently. Come back later.") // Authored.
            return
        }
        var kit = -1
        startDialogue { kit = choice3("Melee", 0, "Ranged", 1, "Magic", 2, title = "Which equipment?") }
        val items: List<Pair<ObjType, Int>> =
            when (kit) {
                0 -> listOf(MbaObjs.sword to 1)
                1 -> listOf(MbaObjs.shortbow to 1, MbaObjs.arrows to KIT_AMMO)
                2 -> listOf(MbaObjs.air to KIT_AMMO, MbaObjs.mind to KIT_AMMO)
                else -> return
            }
        for ((obj, count) in items) invAddOrDrop(objRepo, obj, count)
        lastClaim[player.uid.packed] = now
        mes("Captain Harlan hands you some equipment.") // Authored (not Wiki).
    }

    /* Mudfoot */

    private suspend fun ProtectedAccess.talkMudfoot(npc: Npc) {
        when (state) {
            STARTED -> {
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf("If the player has a follower:" to { false }, "If the player does not have a follower:" to { true })
                    )
                runQuestSection(npc, dialogue, "Talking to Mudfoot", hooks, speakers)
                setMudfoot(FOLLOWING_VIS)
                spawnFollower(player)
                setState(FOLLOWING)
            }
            FOLLOWING -> runQuestSection(npc, dialogue, "Talking to Mudfoot (2)", speakers = speakers)
            CONFRONTED -> fight(RANGER, null)
            in RANGER_BEATEN until BEATEN_ALL -> runQuestSection(npc, dialogue, "Talking to Mudfoot again", speakers = speakers)
            BEATEN_ALL -> runQuestSection(npc, dialogue, "Talking to Mudfoot (3)", speakers = speakers)
            else -> Unit
        }
    }

    private fun spawnFollower(player: Player) {
        removeFollower(player)
        val npc = Npc(npcTypes[MbaNpcs.mudfoot_follower], player.coords)
        npcRepo.add(npc, FOLLOWER_TICKS)
        npc.resetMovement()
        npc.mode = NpcMode.PlayerFollow
        npc.facePlayer(player)
        followers[player.uid.packed] = npc
    }

    private fun removeFollower(player: Player) {
        val npc = followers.remove(player.uid.packed) ?: return
        if (npc.isSlotAssigned) npcRepo.del(npc, Int.MAX_VALUE)
    }

    /* The outlaws */

    private suspend fun ProtectedAccess.confrontStore(npc: Npc) {
        if (state != FOLLOWING) {
            runQuestSection(null, dialogue, "Attempting to confront the outlaw without Mudfoot")
            return
        }
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player didn't speak with Captain Harlan with Mudfoot before:" to { true },
                        "If the player spoke with Captain Harlan with Mudfoot before:" to { false },
                    )
            )
        runQuestSection(npc, dialogue, "Confronting the outlaw", hooks, speakers)
        removeFollower(player)
        setMudfoot(AT_WINDMILL)
        setState(CONFRONTED)
    }

    private suspend fun ProtectedAccess.fight(which: Int, vis: Npc?) {
        val wanted = when (which) { RANGER -> CONFRONTED; MAGE -> RANGER_BEATEN; else -> MAGE_BEATEN }
        if (state != wanted) return
        val section =
            when (which) {
                RANGER -> "Confronting the ranging outlaw again"
                MAGE -> "Confronting the maging outlaw"
                else -> "During the battle"
            }
        runQuestSection(vis, dialogue, section, SimpleQuestHooks(), speakers)
        val type = when (which) { RANGER -> MbaNpcs.ranged_combat; MAGE -> MbaNpcs.magic_combat; else -> MbaNpcs.melee_combat }
        val at = vis?.coords ?: WINDMILL_OUTLAW
        val outlaw = Npc(npcTypes[type], at)
        npcRepo.add(outlaw, FIGHT_TICKS)
        outlaw.opPlayer2(player, aiInteractions)
    }

    private fun outlawKilled(event: NpcKilledEvent) {
        val player = event.hero
        val state = player.questState(MUDFOOT)
        val (next, where, section) =
            when (event.npc.type.id) {
                MbaNpcs.ranged_combat.id -> if (state == CONFRONTED) Triple(RANGER_BEATEN, AT_WOODS, "After the battle") else return
                MbaNpcs.magic_combat.id -> if (state == RANGER_BEATEN) Triple(MAGE_BEATEN, AT_GRAVEYARD, "After the battle (2)") else return
                MbaNpcs.melee_combat.id -> if (state == MAGE_BEATEN) Triple(BEATEN_ALL, AT_GRAVEYARD, "After the battle (3)") else return
                else -> return
            }
        player.setQuestState(MUDFOOT, next)
        VarPlayerIntMapSetter.set(player, MbaVarBits.mudfoot, where)
        protectedAccess.launch(player) { runQuestSection(null, dialogue, section, SimpleQuestHooks(), speakers) }
    }

    companion object {
        const val STARTED = 2
        const val FOLLOWING = 6
        const val CONFRONTED = 10
        const val RANGER_BEATEN = 14
        const val MAGE_BEATEN = 20
        const val BEATEN_ALL = 26
        const val COMPLETE = 32

        private const val AT_HOUSE = 1
        private const val FOLLOWING_VIS = 2
        private const val AT_WINDMILL = 3
        private const val AT_WOODS = 4
        private const val AT_GRAVEYARD = 5

        private const val RANGER = 0
        private const val MAGE = 1
        private const val WARRIOR = 2

        private const val LAMPS = 4
        private const val REWARD_COINS = 500
        private const val KIT_AMMO = 50
        private const val CLAIM_COOLDOWN_MS = 30L * 60 * 1000
        private const val FOLLOWER_TICKS = 3000
        private const val FIGHT_TICKS = 300
        private val WINDMILL_OUTLAW = CoordGrid(3166, 3292, 0)

        val MUDFOOT =
            QuestDef(
                name = "Mudfoot's Big Adventure",
                varbit = { MbaVarBits.quest },
                complete = COMPLETE,
                questPoints = 1,
                rewards = listOf("4 antique lamps (500 combat XP each)", "500 coins", "Basic equipment from Captain Harlan"),
                scrollObj = { MbaObjs.lamp },
                listRow = "quest_mudfootsbigadventure",
            )
    }
}

// Every cache ref Mudfoot's Big Adventure uses (re-check after cache updates).

internal object MbaNpcs : NpcReferences() {
    val harlan = find("captain_harlan")
    val mudfoot_house = find("mba_mudfoot_ruined_building")
    val mudfoot_follower = find("mba_mudfoot_follower")
    val mudfoot_windmill = find("mba_mudfoot_windmill")
    val mudfoot_woods = find("mba_mudfoot_woods")
    val mudfoot_graveyard = find("mba_mudfoot_graveyard")
    val mudfoot_vis = find("mba_mudfoot_vis")
    val outlaw_store = find("mba_outlaw_ranged_general_store")
    val outlaw_woods = find("mba_outlaw_magic_woods")
    val outlaw_graveyard = find("mba_outlaw_melee_graveyard")
    val outlaw_ranged_vis = find("mba_outlaw_ranged_vis")
    val ranged_combat = find("mba_outlaw_ranged_combat")
    val magic_combat = find("mba_outlaw_magic_combat")
    val melee_combat = find("mba_outlaw_melee_combat")
}

internal object MbaObjs : ObjReferences() {
    val lamp = find("mba_lamp")
    val coins = find("coins")
    val sword = find("bronze_sword")
    val shortbow = find("shortbow")
    val arrows = find("bronze_arrow")
    val air = find("airrune")
    val mind = find("mindrune")
}

internal object MbaVarBits : VarBitReferences() {
    val quest = find("mba")
    val mudfoot = find("mba_mudfoot_vis")
}
