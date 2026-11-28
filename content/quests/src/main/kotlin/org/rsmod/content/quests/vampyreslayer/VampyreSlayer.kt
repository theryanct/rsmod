package org.rsmod.content.quests.vampyreslayer

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.invtx.invDel
import org.rsmod.api.npc.interact.AiPlayerInteractions
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.hit.modifier.PlayerHitModifiers
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.stat.statAdvance
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.grantQuest
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.hit.HitBuilder
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Vampyre Slayer (OSRS Wiki). `vampire` varp (cache db end state 3): 0, 1 Morgan asked for help,
 * 2 Dr Harlow gave the stake (for a beer), 3 complete. Opening the coffin in the Draynor Manor
 * basement wakes Count Draynor; killing him with a stake and a hammer carried finishes the quest
 * (4,825 Attack xp, 3 QP), otherwise he rises again. Morgan keeps garlic in his upstairs cupboard.
 *
 * Garlic carried halves the Count's hits (PlayerHitModifiers). Maris (Morgan's wife, new in the
 * 243 cache) has her one line.
 */
class VampyreSlayer
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val locRepo: LocRepository,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
    private val aiInteractions: AiPlayerInteractions,
    private val protectedAccess: ProtectedAccessLauncher,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(VampyreSlayer::class.java, "vampyre_slayer") }

    /** The Count each player woke, so the coffin can't spawn a crowd. */
    private val awake = HashMap<Int, Npc>()

    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, VampyreNpcs.morgan, "Talk-to") { talkMorgan(it) }
        onNpcOpLabel(npcTypes, VampyreNpcs.harlow, "Talk-to") { talkHarlow(it) }
        onNpcOpLabel(npcTypes, VampyreNpcs.maris, "Talk-to") {
            runQuestSection(it, dialogue, "Talking to Maris")
        }
        onOpNpcU(VampyreNpcs.harlow, VampyreObjs.beer) { talkHarlow(it.npc) }
        onLocOpLabel(locTypes, VampyreLocs.cupboard_shut, "Open") {
            locRepo.change(it, VampyreLocs.cupboard_open, CUPBOARD_TICKS)
        }
        onLocOpLabel(locTypes, VampyreLocs.cupboard_open, "Close") {
            locRepo.change(it, VampyreLocs.cupboard_shut, CUPBOARD_TICKS)
        }
        onLocOpLabel(locTypes, VampyreLocs.cupboard_open, "Search") { searchCupboard() }
        onLocOpLabel(locTypes, VampyreLocs.coffin, "Open") { openCoffin(it) }
        onEvent<NpcKilledEvent> { countKilled(this) }
        PlayerHitModifiers.register { target -> garlicWard(target) }
    }

    /** Garlic in the inventory halves the hits of the Count this player woke. */
    private fun HitBuilder.garlicWard(target: Player) {
        val count = awake[target.uid.packed] ?: return
        if (isFromNpc && sourceUid == count.uid.packed && VampyreObjs.garlic in target.inv) {
            damage /= 2
        }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(VAMPYRE_SLAYER)

    private suspend fun ProtectedAccess.talkMorgan(npc: Npc) {
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has a combat level less than 20:" to {
                            player.appearance.combatLevel < RECOMMENDED_COMBAT
                        },
                    ),
                options =
                    mapOf("Yes." to { if (state == 0) player.setQuestState(VAMPYRE_SLAYER, 1) }),
            )
        when (state) {
            0 -> runQuestSection(npc, dialogue, "Starting off", hooks)
            1 -> runQuestSection(npc, dialogue, "Talking to Morgan again", hooks)
            2 -> runQuestSection(npc, dialogue, "Returning to Morgan", hooks)
            else ->
                startDialogue(npc) {
                    chatNpc(happy, "Thank you for slaying Count Draynor, brave adventurer!")
                }
        }
    }

    private suspend fun ProtectedAccess.talkHarlow(npc: Npc) {
        val stake = VampyreObjs.stake
        var gaveStake = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player lost the stake:" to { !carries(stake) },
                        "If the player has the stake:" to { carries(stake) },
                    ),
                boxes =
                    mapOf(
                        "You give a beer to Dr Harlow." to {
                            invDel(inv, VampyreObjs.beer)
                            false
                        },
                        "Dr Harlow hands you a stake." to {
                            gaveStake = true
                            false
                        },
                    ),
            )
        when {
            state == 1 && carries(VampyreObjs.beer) ->
                runQuestSection(npc, dialogue, "Talking to him again", hooks)
            state == 1 -> runQuestSection(npc, dialogue, "Dr Harlow", hooks)
            state == 2 -> runQuestSection(npc, dialogue, AFTER_STAKE, hooks)
            else -> startDialogue(npc) { chatNpc(neutral, "Buy me a drink pleassh...") }
        }
        if (gaveStake) {
            invAddOrDrop(objRepo, stake)
            if (state == 1) player.setQuestState(VAMPYRE_SLAYER, 2)
        }
    }

    private fun ProtectedAccess.searchCupboard() {
        if (inv.isFull()) {
            mes("You don't have enough inventory space.")
            return
        }
        invAdd(inv, VampyreObjs.garlic)
        mes("The cupboard contains garlic. You take a clove.")
    }

    private suspend fun ProtectedAccess.openCoffin(coffin: BoundLocInfo) {
        if (state != 2) {
            mes("The coffin is sealed shut.")
            return
        }
        val current = awake[player.uid.packed]
        if (current != null && current.isSlotAssigned) {
            mes("The coffin is empty.")
            return
        }
        locRepo.change(coffin, VampyreLocs.coffin_open, COFFIN_TICKS)
        mes("The vampyre jumps out of the coffin!")
        delay(1)
        wake(player)
    }

    private fun wake(player: Player) {
        val count = Npc(npcTypes[VampyreNpcs.count], player.coords.translate(1, 0))
        npcRepo.add(count, COUNT_TICKS)
        awake[player.uid.packed] = count
        count.opPlayer2(player, aiInteractions)
    }

    /** Only a stake hammered in (stake + hammer carried) keeps him dead; otherwise he rises. */
    private fun countKilled(event: NpcKilledEvent) {
        val player = event.hero
        if (event.npc.type.id != VampyreNpcs.count.id) return
        if (player.questState(VAMPYRE_SLAYER) != 2) return
        awake.remove(player.uid.packed)
        val stake = VampyreObjs.stake
        if (stake !in player.inv || VampyreObjs.hammer !in player.inv) {
            player.mes("The vampyre seems to regenerate!")
            player.mes("You'll need a stake and a hammer to finish him off.")
            wake(player)
            return
        }
        player.invDel(player.inv, stake)
        player.mes("You hammer the stake into the vampyre's chest!")
        player.statAdvance(stats.attack, ATTACK_XP * xpMods.get(player, stats.attack))
        val scrolled = protectedAccess.launch(player) { completeQuest(VAMPYRE_SLAYER) }
        if (!scrolled) {
            player.grantQuest(VAMPYRE_SLAYER)
            player.mes("Congratulations, you've completed a quest: Vampyre Slayer")
        }
    }

    companion object {
        private const val RECOMMENDED_COMBAT = 20
        private const val CUPBOARD_TICKS = 200
        private const val COFFIN_TICKS = 50
        private const val COUNT_TICKS = 500
        private const val ATTACK_XP = 4825.0
        private const val AFTER_STAKE = "Talking to Dr Harlow after getting the stake"

        val VAMPYRE_SLAYER =
            QuestDef(
                name = "Vampyre Slayer",
                varp = { VampyreVarps.vampire },
                complete = 3,
                questPoints = 3,
                rewards = listOf("4,825 Attack XP"),
                scrollObj = { VampyreObjs.stake },
            )
    }
}

// Every cache ref Vampyre Slayer uses (re-check after the rev 243 cutover).

internal object VampyreNpcs : NpcReferences() {
    val morgan = find("morgan")
    val harlow = find("dr_harlow")
    val maris = find("maris")
    val count = find("count_draynor")
}

internal object VampyreLocs : LocReferences() {
    val cupboard_shut = find("garliccupboardshut")
    val cupboard_open = find("garliccupboardopen")
    val coffin = find("vampcoffin")
    val coffin_open = find("vampcoffinopen")
}

internal object VampyreObjs : ObjReferences() {
    val stake = find("stake")
    val hammer = find("hammer")
    val garlic = find("garlic")
    val beer = find("beer")
}

internal object VampyreVarps : VarpReferences() {
    val vampire = find("vampire")
}
