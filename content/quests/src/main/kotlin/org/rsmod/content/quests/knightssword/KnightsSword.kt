package org.rsmod.content.quests.knightssword

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.events.interact.LocEvents
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.righthand
import org.rsmod.api.player.stat.miningLvl
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.obj.ObjRepository
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
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.content.skills.mining.scripts.Mining
import org.rsmod.content.skills.mining.scripts.Mining.Companion.pickaxeAnim
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The Knight's Sword (OSRS Wiki). `squire` varp (cache db end state 7): 0, 1 started (the
 * Squire), 2 asked Reldo about Imcando dwarves, 4 gave Thurgo a redberry pie (he needs a picture),
 * 5 gave him Sir Vyvin's portrait (from the cupboard), 6 Thurgo made the sword (blurite ore + 2
 * iron bars), 7 complete. Reward: 12,725 Smithing xp, 1 QP.
 *
 * Also: blurite rocks become mineable (Mining 10, 17.5 xp) if nothing else handles them. Not
 * done: Sir Vyvin catching the player at the cupboard. Reldo's Talk-to (state 1 -> 2) is in
 * `reldo/Reldo.kt`, shared with Shield of Arrav.
 */
class KnightsSword
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val locRepo: LocRepository,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(KnightsSword::class.java, "knights_sword") }

    override fun ScriptContext.startup() {
        onNpcOpLabel(npcTypes, KnightsSwordNpcs.squire, "Talk-to") { talkSquire(it) }
        onNpcOpLabel(npcTypes, KnightsSwordNpcs.thurgo, "Talk-to") { talkThurgo(it) }
        onLocOpLabel(locTypes, KnightsSwordLocs.cupboard_shut, "Open") { openCupboard(it) }
        onLocOpLabel(locTypes, KnightsSwordLocs.cupboard_open, "Search") { searchCupboard() }
        onLocOpLabel(locTypes, KnightsSwordLocs.cupboard_open, "Shut") { shutCupboard(it) }
        // Blurite falls back to this simple handler unless Mining (content group) or another
        // script already owns the rock's Mine op.
        for (rock in KnightsSwordLocs.blurite_rocks) {
            val type = locTypes[rock]
            val op = type.op.indexOfFirst { it.equals("Mine", ignoreCase = true) } + 1
            val event = MINE_EVENTS.getOrNull(op - 1) ?: continue
            if (type.contentGroup == -1 && !eventBus.contains(event, rock.id)) {
                onLocOpLabel(locTypes, rock, "Mine") { mineBlurite() }
            }
        }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(KNIGHTS_SWORD)

    private fun ProtectedAccess.hasSword(): Boolean =
        carries(KnightsSwordObjs.sword) || player.righthand?.id == KnightsSwordObjs.sword.id

    private suspend fun ProtectedAccess.talkSquire(npc: Npc) {
        var handedIn = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player hasn't met Thurgo yet:" to { true },
                        "If the player is wielding the sword" to {
                            player.righthand?.id == KnightsSwordObjs.sword.id
                        },
                        "If the sword is in the player's inventory" to {
                            carries(KnightsSwordObjs.sword)
                        },
                    ),
                options =
                    mapOf("Yes." to { if (state == 0) player.setQuestState(KNIGHTS_SWORD, 1) }),
                boxes =
                    mapOf(
                        "You give the sword to the squire." to {
                            handedIn = takeSword()
                            false
                        },
                    ),
            )
        val section =
            when (state) {
                0 -> "Talking to the Squire"
                1,
                2 -> SQUIRE_BEFORE_THURGO
                4 ->
                    if (carries(KnightsSwordObjs.portrait)) {
                        "Talking to the Squire again after finding the portrait"
                    } else {
                        "Talking to the Squire after giving Thurgo a redberry Pie"
                    }
                5 -> "Talking to the Squire before making the sword"
                6 ->
                    if (hasSword()) {
                        "Giving the sword to the Squire"
                    } else {
                        "Talking to the Squire before making the sword"
                    }
                else -> null
            }
        if (section == null) {
            startDialogue(npc) { chatNpc(happy, "Thank you so much for the sword!") }
            return
        }
        runQuestSection(npc, dialogue, section, hooks)
        if (handedIn) {
            statAdvance(stats.smithing, 12725.0 * xpMods.get(player, stats.smithing))
            completeQuest(KNIGHTS_SWORD)
        }
    }

    private fun ProtectedAccess.takeSword(): Boolean {
        val sword = KnightsSwordObjs.sword
        if (carries(sword)) {
            invDel(inv, sword)
            return true
        }
        if (player.righthand?.id == sword.id) {
            invDel(worn, sword)
            return true
        }
        return false
    }

    private suspend fun ProtectedAccess.talkThurgo(npc: Npc) {
        val pie = KnightsSwordObjs.redberry_pie
        val portrait = KnightsSwordObjs.portrait
        var made = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player hasn't already met Thurgo in The Giant Dwarf:" to { true },
                    ),
                boxes =
                    mapOf(
                        "You hand over the pie." to {
                            if (carries(pie)) {
                                invDel(inv, pie)
                                player.setQuestState(KNIGHTS_SWORD, 4)
                            }
                            false
                        },
                        "You give the Portrait to Thurgo. Thurgo studies the portrait." to {
                            if (carries(portrait)) {
                                invDel(inv, portrait)
                                player.setQuestState(KNIGHTS_SWORD, 5)
                            }
                            false
                        },
                        SWORD_BOX to {
                            if (hasMaterials()) {
                                invDel(inv, KnightsSwordObjs.blurite_ore)
                                invDel(inv, KnightsSwordObjs.iron_bar, 2)
                                invAddOrDrop(objRepo, KnightsSwordObjs.sword)
                                made = true
                            }
                            false
                        },
                    ),
            )
        val bars = carries(KnightsSwordObjs.iron_bar, 2)
        val blurite = carries(KnightsSwordObjs.blurite_ore)
        val section =
            when (state) {
                0,
                1 -> "Talking to Thurgo before talking to Reldo"
                2 ->
                    if (carries(pie)) {
                        "Talking to Thurgo with a Redberry Pie"
                    } else {
                        "Talking to Thurgo without a Redberry Pie"
                    }
                4 ->
                    if (carries(portrait)) {
                        "Giving Thurgo the portrait"
                    } else {
                        "Talking to Thurgo again without the Portrait"
                    }
                5 ->
                    when {
                        bars && blurite -> "Giving both the iron bars and blurite to Thurgo"
                        bars -> "Talking to Thurgo with only the iron bars"
                        blurite -> "Talking to Thurgo with only the blurite"
                        else -> "Talking to Thurgo without the materials"
                    }
                else -> "After the sword has been made"
            }
        runQuestSection(npc, dialogue, section, hooks)
        if (made) {
            player.setQuestState(KNIGHTS_SWORD, 6)
        }
    }

    private fun ProtectedAccess.hasMaterials(): Boolean =
        carries(KnightsSwordObjs.blurite_ore) && carries(KnightsSwordObjs.iron_bar, 2)

    private fun ProtectedAccess.openCupboard(cupboard: BoundLocInfo) {
        locRepo.change(cupboard, KnightsSwordLocs.cupboard_open, CUPBOARD_TICKS)
    }

    private fun ProtectedAccess.shutCupboard(cupboard: BoundLocInfo) {
        locRepo.change(cupboard, KnightsSwordLocs.cupboard_shut, CUPBOARD_TICKS)
    }

    private suspend fun ProtectedAccess.searchCupboard() {
        if (state != 4 || carries(KnightsSwordObjs.portrait)) {
            mes("You search the cupboard but find nothing of interest.")
            return
        }
        invAddOrDrop(objRepo, KnightsSwordObjs.portrait)
        runQuestSection(null, dialogue, "Searching the cupboard")
    }

    private suspend fun ProtectedAccess.mineBlurite() {
        val pickaxe = Mining.findPickaxe(player, objTypes)
        if (pickaxe == null) {
            mes("You need a pickaxe to mine this rock.")
            return
        }
        if (player.miningLvl < BLURITE_LEVEL) {
            mes("You need a Mining level of $BLURITE_LEVEL to mine this rock.")
            return
        }
        if (inv.isFull()) {
            mes("Your inventory is too full to hold any more ore.")
            return
        }
        anim(objTypes[pickaxe].pickaxeAnim)
        delay(BLURITE_TICKS)
        invAdd(inv, KnightsSwordObjs.blurite_ore)
        statAdvance(stats.mining, 17.5 * xpMods.get(player, stats.mining))
        mes("You manage to mine some blurite.")
    }

    companion object {
        private const val CUPBOARD_TICKS = 200
        private const val BLURITE_LEVEL = 10
        private const val BLURITE_TICKS = 4
        private const val SQUIRE_BEFORE_THURGO =
            "Talking to the Squire again after starting the quest but before talking to Thurgo"
        private val MINE_EVENTS =
            listOf(
                LocEvents.Op1::class.java,
                LocEvents.Op2::class.java,
                LocEvents.Op3::class.java,
                LocEvents.Op4::class.java,
                LocEvents.Op5::class.java,
            )
        private const val SWORD_BOX =
            "You give the blurite ore and iron bars to Thurgo. Thurgo makes you a sword."

        val KNIGHTS_SWORD =
            QuestDef(
                name = "The Knight's Sword",
                varp = { KnightsSwordVarps.squire },
                complete = 7,
                questPoints = 1,
                rewards = listOf("12,725 Smithing XP"),
                scrollObj = { KnightsSwordObjs.sword },
            )
    }
}

// Every cache ref The Knight's Sword uses (re-check after the rev 243 cutover).

internal object KnightsSwordNpcs : NpcReferences() {
    val squire = find("squire")
    val thurgo = find("thurgo")
}

internal object KnightsSwordLocs : LocReferences() {
    val cupboard_shut = find("vyvincupboardshut")
    val cupboard_open = find("vyvincupboardopen")
    val blurite_rocks = listOf(find("blurite_rock_1"), find("blurite_rock_2"))
}

internal object KnightsSwordObjs : ObjReferences() {
    val redberry_pie = find("redberry_pie")
    val portrait = find("knights_portrait")
    val blurite_ore = find("blurite_ore")
    val iron_bar = find("iron_bar")
    val sword = find("faladian_sword")
}

internal object KnightsSwordVarps : VarpReferences() {
    val squire = find("squire")
}
