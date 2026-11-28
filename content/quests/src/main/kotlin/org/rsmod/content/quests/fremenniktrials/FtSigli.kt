package org.rsmod.content.quests.fremenniktrials

import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.npc.opPlayer2
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onEvent
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.onHeldOpLabel
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.game.entity.Npc
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.ScriptContext
import kotlin.math.abs

/**
 * Sigli the Huntsman's trial (Wiki): his hunters' talisman's "Locate" points towards the invisible
 * Draugen; within three tiles it appears and attacks, and defeating it absorbs its essence into
 * the talisman for Sigli.
 *
 * Simplified: the Draugen waits at one of eight Fremennik-province spots (picked when you first
 * locate it, and again if it escapes) instead of wandering, and no butterfly follows it.
 */
internal class FtSigli(private val ft: FtContext) {
    /** Each hunter's Draugen (uid.packed is an Int). */
    private val draugens = HashMap<Int, Npc>()

    fun ScriptContext.bind() {
        ft.bound("talisman Locate") { onHeldOpLabel(ft.objTypes, FtObjs.talisman, "Locate") { locate() } }
        ft.bound("talisman_charged Locate") { onHeldOpLabel(ft.objTypes, FtObjs.talisman_charged, "Locate") { mes("You have already captured the Draugen.") } }
        onEvent<NpcKilledEvent> { draugenKilled(this) }
    }

    suspend fun ProtectedAccess.talk(npc: Npc) {
        when {
            !player.accepted(Trial.Sigli) -> {
                var accepted = false
                val room = inv.freeSpace() > 0
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player does not have inventory space:" to { !room },
                                "If the player has inventory space:" to { room },
                            ),
                        options = mapOf("Yes" to { accepted = room }),
                    )
                runQuestSection(npc, ft.dialogue, "Sigli the Huntsman", hooks)
                if (accepted) {
                    player.accept(Trial.Sigli)
                    invAdd(inv, FtObjs.talisman)
                }
            }
            player.voted(Trial.Sigli) -> runQuestSection(npc, ft.dialogue, "Subsequent dialogue with Sigli")
            player.ftHas(FtVarps.sigli, CAPTURED) && (carries(FtObjs.talisman_charged) || carries(FtObjs.talisman)) -> {
                runQuestSection(npc, ft.dialogue, "Talking to Sigli after defeating The Draugen")
                invDel(inv, if (carries(FtObjs.talisman_charged)) FtObjs.talisman_charged else FtObjs.talisman)
                player.giveVote(Trial.Sigli)
                mes("Congratulations! You have completed the Hunter's trial!") // Authored (not Wiki).
            }
            else -> {
                val has = carries(FtObjs.talisman) || carries(FtObjs.talisman_charged)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player has the talisman:" to { has },
                                "If the player does not have a talisman:" to { !has },
                            )
                    )
                runQuestSection(npc, ft.dialogue, "Talking to Sigli again", hooks)
                if (!has) {
                    invAddOrDrop(ft.objRepo, if (player.ftHas(FtVarps.sigli, CAPTURED)) FtObjs.talisman_charged else FtObjs.talisman)
                }
            }
        }
    }

    private suspend fun ProtectedAccess.locate() {
        if (!player.onTrial(Trial.Sigli)) {
            mes("Nothing interesting happens.")
            return
        }
        if (player.ftHas(FtVarps.sigli, CAPTURED)) {
            invReplace(inv, FtObjs.talisman, 1, FtObjs.talisman_charged)
            mes("You have already captured the Draugen.")
            return
        }
        val spot = spot()
        val here = player.coords
        val existing = draugens[player.uid.packed]
        if (existing != null && existing.isSlotAssigned) {
            mes("The Draugen is here! Beware!")
            return
        }
        if (here.level == spot.level && here.chebyshevDistance(spot) <= FOUND_RADIUS) {
            mes("The Draugen is here! Beware!")
            val draugen = Npc(ft.npcTypes[FtNpcs.draugen], spot)
            ft.npcRepo.add(draugen, DRAUGEN_TICKS)
            draugens[player.uid.packed] = draugen
            draugen.opPlayer2(player, ft.aiInteractions)
            return
        }
        if (existing != null) {
            // It escaped (despawned) last time: it has moved elsewhere.
            draugens.remove(player.uid.packed)
            pickSpot()
            mes("The Draugen has moved elsewhere...")
            return
        }
        mes("The talisman guides you ${direction(here, spot)}.")
    }

    private fun ProtectedAccess.spot(): CoordGrid {
        if (!player.ftHas(FtVarps.sigli, SPOT_PICKED)) pickSpot()
        val (x, z) = SPOTS[player.ftField(FtVarps.sigli, 0, 3)]
        return ft.tile(x, z, 0)
    }

    private fun ProtectedAccess.pickSpot() {
        player.ftSetField(FtVarps.sigli, 0, 3, ft.random.of(0, SPOTS.lastIndex))
        player.ftSet(FtVarps.sigli, SPOT_PICKED)
    }

    private fun direction(from: CoordGrid, to: CoordGrid): String {
        val dx = to.x - from.x
        val dz = to.z - from.z
        val ns = if (dz > 0) "north" else "south"
        val ew = if (dx > 0) "east" else "west"
        return when {
            abs(dx) > abs(dz) * 2 -> ew
            abs(dz) > abs(dx) * 2 -> ns
            else -> "$ns-$ew"
        }
    }

    private fun draugenKilled(event: NpcKilledEvent) {
        if (event.npc.type.id != FtNpcs.draugen.id) return
        val player = event.hero
        draugens.remove(player.uid.packed)
        if (!player.onTrial(Trial.Sigli)) return
        // The talisman itself turns over on its next use or at Sigli (no inventory access here).
        player.ftSet(FtVarps.sigli, CAPTURED)
        player.mes("You absorb the Draugen's essence into your talisman.")
    }

    private companion object {
        const val SPOT_PICKED = 1 shl 3
        const val CAPTURED = 1 shl 4
        const val FOUND_RADIUS = 3
        const val DRAUGEN_TICKS = 500

        /** Coastline and moorland around Rellekka (snapped to a walkable tile when used). */
        val SPOTS =
            listOf(
                2650 to 3610,
                2690 to 3640,
                2735 to 3630,
                2705 to 3600,
                2615 to 3640,
                2665 to 3625,
                2720 to 3660,
                2630 to 3612,
            )
    }
}
