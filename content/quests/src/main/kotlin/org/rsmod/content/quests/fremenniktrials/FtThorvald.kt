package org.rsmod.content.quests.fremenniktrials

import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.statSub
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpNpcU
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.type.obj.Wearpos
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Thorvald the Warrior's trial (Wiki): down his ladder with no weapons, armour or runes, Koschei
 * the deathless appears and must be beaten three times, each form stronger; the fourth form is
 * fought to the "death" and passing the third is enough for the vote. Leaving before the third
 * form falls resets the fight.
 *
 * Simplified: the arena is the shared map, not an instance; "armour" is any body-slot item (worn
 * or carried) - only amulets and rings may come in, so clothing such as H.A.M. robes is refused
 * too; the fourth form does not stop at 1 hitpoint (dying to it is a normal death), so climb the
 * ladder once the third form falls; Koschei keeps his cache combat stats (no hidden-level tricks).
 */
internal class FtThorvald(private val ft: FtContext) {
    private val speakers by lazy { mapOf("Thorvald the Warrior" to FtNpcs.thorvald) }

    /** Each fighter's current Koschei (uid.packed is an Int). */
    private val opponents = HashMap<Int, Npc>()

    fun ScriptContext.bind() {
        ft.bound("warrior_ladder Climb-down") { onLocOpLabel(ft.locTypes, FtLocs.warrior_ladder, "Climb-down") { climbDown() } }
        ft.bound("arena_ladder Climb-up") { onLocOpLabel(ft.locTypes, FtLocs.arena_ladder, "Climb-up") { climbUp() } }
        onOpNpcU(FtNpcs.thorvald, FtObjs.raw_sardine) {
            with(ft) { cond(it.npc, "Talking to Thorvald again (3)", "If the player attempts to use a raw sardine on Thorvald:") }
        }
        onEvent<NpcKilledEvent> { koscheiKilled(this) }
    }

    suspend fun ProtectedAccess.talk(npc: Npc) {
        when {
            !player.accepted(Trial.Thorvald) -> {
                var accepted = false
                runQuestSection(npc, ft.dialogue, "Thorvald the Warrior", SimpleQuestHooks(options = mapOf("Yes" to { accepted = true })))
                if (accepted) player.accept(Trial.Thorvald)
            }
            player.voted(Trial.Thorvald) ->
                with(ft) { cond(npc, "Talking to Thorvald (3)", "If the player manages to defeat Koschei:") }
            player.ftHas(FtVarps.thorvald, PASSED) -> {
                with(ft) { cond(npc, "Talking to Thorvald (3)", "If the player does not manage to defeat Koschei's fourth phase:") }
                player.giveVote(Trial.Thorvald)
                mes("Congratulations! You have passed the warrior's trial!")
            }
            else -> runQuestSection(npc, ft.dialogue, "Talking to Thorvald again (3)")
        }
    }

    private suspend fun ProtectedAccess.climbDown() {
        when {
            player.voted(Trial.Thorvald) || player.ftHas(FtVarps.thorvald, PASSED) -> {
                runQuestSection(null, ft.dialogue, "Attempting to climb down the stairs again")
                return
            }
            !player.onTrial(Trial.Thorvald) -> {
                // Authored line (not Wiki): the battleground is for those taking his trial.
                mes("Thorvald guards this ladder. You should speak to him first.")
                return
            }
            carriesForbidden() -> {
                with(ft) {
                    cond(null, "Entering the arena", "If the player has any weapons or armour before entering the arena:", speakers = speakers)
                }
                return
            }
        }
        val level = ft.levelOf(ARENA_LADDER.x, ARENA_LADDER.z, FtLocs.arena_ladder)
        delay(1)
        telejump(ft.tile(ARENA_LADDER.x - 1, ARENA_LADDER.z - 1, level))
        player.ftSetField(FtVarps.thorvald, 0, 3, 0)
        mes("Explore this battleground and find your foe...")
        delay(KOSCHEI_DELAY)
        spawn(player, 1)
    }

    private suspend fun ProtectedAccess.climbUp() {
        val form = player.ftField(FtVarps.thorvald, 0, 3)
        opponents.remove(player.uid.packed)?.let { if (it.isSlotAssigned) ft.npcRepo.del(it, Int.MAX_VALUE) }
        player.ftSetField(FtVarps.thorvald, 0, 3, 0)
        delay(1)
        telejump(ft.tile(TOP.x, TOP.z, TOP.level))
        if (form in 1..3 && !player.ftHas(FtVarps.thorvald, PASSED)) {
            // Authored line (not Wiki): leaving mid-fight fails the attempt.
            mes("You leave the battleground. You will have to face Koschei from the start next time.")
        }
    }

    private fun ProtectedAccess.carriesForbidden(): Boolean {
        for (slot in worn.indices) {
            if (worn[slot] == null) continue
            if (slot != Wearpos.Front.slot && slot != Wearpos.Ring.slot) return true
        }
        for (obj in inv) {
            val type = ft.objTypes.getOrNull(obj) ?: continue
            if (type.wearpos1 in FORBIDDEN_WEARPOS) return true
            if (type.name.endsWith(" rune", ignoreCase = true)) return true
        }
        return false
    }

    /** Spawns form [form] (1-4) next to [player] and sends it at them. */
    private fun spawn(player: Player, form: Int) {
        val at = player.coords
        val koschei = Npc(ft.npcTypes[FtNpcs.koschei[form - 1]], ft.tile(at.x - 2, at.z - 2, at.level))
        ft.npcRepo.add(koschei, KOSCHEI_TICKS)
        opponents[player.uid.packed] = koschei
        player.ftSetField(FtVarps.thorvald, 0, 3, form)
        koschei.opPlayer2(player, ft.aiInteractions)
        koschei.say(
            when (form) {
                1 -> TAUNTS[ft.random.of(0, TAUNTS.lastIndex)].replace("{player}", player.displayName)
                2 -> "It seems you have some idea of combat after all, outerlander! I will not hold back so much this time!"
                3 -> "Impressive start... But now we fight for real!"
                else -> "You show some skill at combat... I will hold back no longer! This time you lose your prayer however, and fight like a warrior!"
            }
        )
    }

    private fun koscheiKilled(event: NpcKilledEvent) {
        val form = FtNpcs.koschei.indexOfFirst { it.id == event.npc.type.id } + 1
        if (form == 0) return
        val player = event.hero
        opponents.remove(player.uid.packed)
        if (!player.onTrial(Trial.Thorvald)) return
        when (form) {
            1, 2 -> spawn(player, form + 1)
            3 -> {
                player.ftSet(FtVarps.thorvald, PASSED)
                player.mes("You have beaten Koschei three times; Thorvald will be impressed. Climb the ladder whenever you wish.") // Authored.
                spawn(player, 4)
                player.statSub(stats.prayer, constant = 0, percent = 100)
            }
            else -> {
                player.ftSetField(FtVarps.thorvald, 0, 3, 0)
                player.mes("Congratulations! you have completed the warriors trial!")
            }
        }
    }

    private companion object {
        const val PASSED = 1 shl 3
        const val KOSCHEI_DELAY = 5
        const val KOSCHEI_TICKS = 1000

        /** The arena's ladder up (map level resolved at runtime) and the hut's ladder. */
        val ARENA_LADDER = CoordGrid(2672, 10099, 2)
        val TOP = CoordGrid(2668, 3694, 0)

        val FORBIDDEN_WEARPOS =
            setOf(
                Wearpos.Hat.slot,
                Wearpos.Back.slot,
                Wearpos.RightHand.slot,
                Wearpos.Torso.slot,
                Wearpos.LeftHand.slot,
                Wearpos.Legs.slot,
                Wearpos.Hands.slot,
                Wearpos.Feet.slot,
                Wearpos.Quiver.slot,
            )

        /** Wiki "Koschei's random dialogue". */
        val TAUNTS =
            listOf(
                "Prepare to die, {player}!",
                "I will crush your dead bones!",
                "Outerlander... you have made a mistake coming down here!",
                "You have no idea of my true power, outerlander!",
                "Today is the day you die, outerlander!",
                "{player}! Meet your doom!",
                "Prepare to face my power, outerlander!",
            )
    }
}
