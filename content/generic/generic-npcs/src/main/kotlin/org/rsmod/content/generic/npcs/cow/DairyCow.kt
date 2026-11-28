package org.rsmod.content.generic.npcs.cow

import jakarta.inject.Inject
import org.rsmod.api.config.refs.content
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.seqs
import org.rsmod.api.config.refs.spotanims
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.synths
import org.rsmod.api.player.dialogue.Dialogue
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onPlayerQueue
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.game.hit.HitType
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class DairyCow @Inject constructor(private val xpMods: XpModifiers) : PluginScript() {
    override fun ScriptContext.startup() {
        onOpLoc1(content.dairy_cow) { attemptMilkingCow(it.loc) }
        onOpLoc2(content.dairy_cow) { stealCowbell(it.loc) }
        onOpLocU(content.dairy_cow) { mes("The cow doesn't want that.") }
        onOpLocU(content.dairy_cow, objs.bucket_empty) { attemptMilkingCow(it.loc) }

        onPlayerQueue(cow_queues.milk) { milkCow() }
    }

    private suspend fun ProtectedAccess.attemptMilkingCow(loc: BoundLocInfo) {
        arriveDelay()
        faceSquare(loc.coords)
        if (objs.bucket_empty !in inv) {
            if (loc.coords.isNearGillie()) {
                startDialogue { noBucket() }
            } else {
                mes("You'll need an empty bucket to collect the milk.")
            }
            return
        }
        weakQueue(cow_queues.milk, 2)
    }

    private fun ProtectedAccess.milkCow() {
        val replace = invReplace(inv, objs.bucket_empty, 1, objs.bucket_of_milk)
        if (replace.failure) {
            return
        }
        spam("You milk the cow.")
        anim(seqs.milkit)
        soundSynth(synths.milk_cow)
        weakQueue(cow_queues.milk, 8)
    }

    private suspend fun Dialogue.noBucket() {
        chatNpcSpecific(
            "Gillie Groats the Milkmaid",
            cow_npcs.gillie_the_milkmaid,
            laugh,
            "Tee hee! You've never milked a cow before, have you?",
        )
        chatPlayer(quiz, "Erm... No. How could you tell?")
        chatNpcSpecific(
            "Gillie Groats the Milkmaid",
            cow_npcs.gillie_the_milkmaid,
            laugh,
            "Because you're spilling milk all over the floor. What a " +
                "waste! You need something to hold the milk.",
        )
        chatPlayer(neutral, "Ah yes, I really should have guessed that one, shouldn't I?")
        chatNpcSpecific(
            "Gillie Groats the Milkmaid",
            cow_npcs.gillie_the_milkmaid,
            laugh,
            "You're from the city, aren't you... Try it again with an empty bucket.",
        )
        chatPlayer(neutral, "Right, I'll do that.")
    }

    /**
     * Cold War (OSRS Wiki "Cowbells", "Dairy cow"): once the quest is started, 15 Thieving steals
     * a pair of cowbells for 16 xp; success is 128/256 at level 1 to 200/256 at 99 (Mod Ash), a
     * failure kicks and stuns. The quest's cache varbit is read here directly (this module doesn't
     * depend on quests). Simplified: the talking Zanaris cow's conversation is not built.
     */
    private suspend fun ProtectedAccess.stealCowbell(loc: BoundLocInfo) {
        arriveDelay()
        faceSquare(loc.coords)
        if (player.vars[DairyCowVarBits.peng_quest] == 0) {
            mesbox("You need to have started the Cold War quest to attempt this.")
            return
        }
        if (statBase(stats.thieving) < COWBELL_LEVEL) {
            mes("You need a Thieving level of $COWBELL_LEVEL to steal a cowbell.")
            return
        }
        if (inv.isFull()) {
            mes("You don't have enough inventory space.")
            return
        }
        anim(DairyCowSeqs.pickpocket)
        delay(2)
        if (statRandom(stats.thieving, COWBELL_LOW, COWBELL_HIGH, 0)) {
            invAdd(inv, DairyCowObjs.cowbell)
            statAdvance(stats.thieving, COWBELL_XP * xpMods.get(player, stats.thieving))
            mes("You steal a cowbell.")
            return
        }
        mes("You fail to steal a cowbell.")
        mes("The cow kicks you and stuns you!")
        player.queueHit(1, HitType.Typeless, random.of(1, 2))
        spotanim(spotanims.stunned, height = 100)
        delay(COWBELL_STUN_TICKS)
    }

    private fun CoordGrid.isNearGillie(): Boolean {
        return this == CoordGrid(0, 50, 51, 54, 8) || this == CoordGrid(0, 50, 51, 52, 11)
    }
}

private const val COWBELL_LEVEL = 15
private const val COWBELL_LOW = 128
private const val COWBELL_HIGH = 200
private const val COWBELL_XP = 16.0
private const val COWBELL_STUN_TICKS = 4

internal object DairyCowVarBits : VarBitReferences() {
    /** Cold War's quest varbit (any value above 0: started). */
    val peng_quest = find("peng_quest")
}

internal object DairyCowObjs : ObjReferences() {
    val cowbell = find("peng_cowbell")
}

internal object DairyCowSeqs : SeqReferences() {
    val pickpocket = find("human_pickpocket")
}
