package org.rsmod.content.quests.framework

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Spot handlers for the spade's "Dig" op (one handler per op, so every quest digs through here):
 * each returns `true` if it handled the dig at the player's position.
 */
public object QuestDigSpots {
    private val handlers = mutableListOf<suspend ProtectedAccess.() -> Boolean>()

    /** Register from a plugin's `startup()`; runs after the dig animation. */
    public fun register(handler: suspend ProtectedAccess.() -> Boolean) {
        handlers += handler
    }

    internal suspend fun dig(access: ProtectedAccess): Boolean =
        handlers.any { handler -> handler(access) }
}

/** Binds the spade's "Dig" (unless something else owns it) and runs [QuestDigSpots]. */
public class SpadeDig @Inject constructor(private val objTypes: ObjTypeList) : PluginScript() {
    override fun ScriptContext.startup() {
        onHeldOpLabel(objTypes, SpadeDigObjs.spade, "Dig") { dig() }
    }

    private suspend fun ProtectedAccess.dig() {
        anim(SpadeDigSeqs.dig)
        delay(1)
        if (!QuestDigSpots.dig(this)) {
            mes("You dig but find nothing.")
        }
    }
}

internal object SpadeDigObjs : ObjReferences() {
    val spade = find("spade")
}

internal object SpadeDigSeqs : SeqReferences() {
    val dig = find("human_dig")
}
