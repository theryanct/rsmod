package org.rsmod.content.generic.npcs.sheep

import jakarta.inject.Inject
import org.rsmod.api.config.refs.content
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.params
import org.rsmod.api.config.refs.queues
import org.rsmod.api.config.refs.seqs
import org.rsmod.api.config.refs.synths
import org.rsmod.api.npc.access.StandardNpcAccess
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.tools.hasTool
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.repo.world.WorldRepository
import org.rsmod.api.script.onAiTimer
import org.rsmod.api.script.onNpcQueue
import org.rsmod.api.script.isNpcOpBound
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpcU
import org.rsmod.game.entity.Npc
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class Sheep
@Inject
constructor(
    private val objRepo: ObjRepository,
    private val worldRepo: WorldRepository,
    private val random: GameRandom,
) : PluginScript() {
    override fun ScriptContext.startup() {
        onAiTimer(content.sheep) { npc.sheepTimer() }
        onOpNpc1(content.sheep) { shearSheep(it.npc) }
        onOpNpcU(content.sheep, objs.shears) { shearSheep(it.npc) }
        // Wiki "Sheep (penguins)": trying to shear the disguised penguins makes them walk away.
        if (!isNpcOpBound(sheep_npcs.fake_sheep, 1)) onOpNpc1(sheep_npcs.fake_sheep) { shearFake(it.npc) }
        onOpNpcU(sheep_npcs.fake_sheep, objs.shears) { shearFake(it.npc) }

        onAiTimer(content.sheared_sheep) { npc.sheepTimer() }
        onNpcQueue(content.sheared_sheep, queues.generic_queue1) { queueTransmogReset() }
    }

    private fun Npc.sheepTimer() {
        val next = random.of(15..34)
        aiTimer(next)

        if (random.randomBoolean(4)) {
            sayFlavourText()
        }
    }

    private fun Npc.sayFlavourText() {
        worldRepo.soundArea(coords, synths.sheep_atmospheric1)
        say("Baa!")
    }

    private suspend fun ProtectedAccess.shearSheep(npc: Npc) {
        if (!player.hasTool(objs.shears)) {
            mes("You need a set of shears to do this.")
            return
        }
        val sheared = npcParam(npc, params.next_npc_stage)
        anim(seqs.human_shearing)
        soundSynth(synths.shear_sheep, delay = 10)
        faceEntitySquare(npc)
        delay(1)
        npcPlayerFaceClose(npc)
        delay(1)
        npcChangeType(npc, sheared, duration = 50)
        mes("You get some wool.")
        invAddOrDrop(objRepo, objs.wool)
        npc.queue(queues.generic_queue1, cycles = 1)
    }

    private fun ProtectedAccess.shearFake(npc: Npc) {
        // Authored message (the Wiki only says it walks away and gives no wool).
        mes("The sheep trots away from you before you can shear it.")
        val dest = npc.coords.translate(random.of(-3..3), random.of(-3..3))
        npc.walk(dest)
    }

    private fun StandardNpcAccess.queueTransmogReset() {
        resetMode()
        npc.sayFlavourText()
    }
}
