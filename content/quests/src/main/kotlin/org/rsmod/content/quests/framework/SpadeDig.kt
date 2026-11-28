package org.rsmod.content.quests.framework

import jakarta.inject.Inject
import org.rsmod.api.player.dig.DigSpots
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.seq.SeqReferences
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Quest dig spots: a thin alias for the shared [DigSpots] registry (api), which clue scrolls and
 * other dig triggers also use. Each handler returns `true` if it handled the dig here.
 */
public object QuestDigSpots {
    /** Register from a plugin's `startup()`; runs after the dig animation. */
    public fun register(handler: suspend ProtectedAccess.() -> Boolean) {
        DigSpots.register(handler)
    }
}

/** Binds the spade's "Dig" (unless something else owns it) and runs [QuestDigSpots]. */
public class SpadeDig @Inject constructor(private val objTypes: ObjTypeList) : PluginScript() {
    override fun ScriptContext.startup() {
        onHeldOpLabel(objTypes, SpadeDigObjs.spade, "Dig") { dig() }
    }

    private suspend fun ProtectedAccess.dig() {
        anim(SpadeDigSeqs.dig)
        delay(1)
        if (!DigSpots.dig(this)) {
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
