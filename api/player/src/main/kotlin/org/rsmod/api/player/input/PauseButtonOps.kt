package org.rsmod.api.player.input

import org.rsmod.game.type.comp.ComponentType

/**
 * Lets right-click ops on a pause-button component answer the dialog that is waiting for it. When
 * a registered component's op is clicked while the player's coroutine awaits a
 * [ResumePauseButtonInput], the button handler resumes it with `subcomponent` = the mapped value
 * instead of starting a new (dialog-cancelling) modal-button action.
 */
public object PauseButtonOps {
    private val mappings = HashMap<Int, (Int) -> Int?>()

    /** [map] turns an op slot (1-10) into the resume subcomponent, or `null` to ignore it. */
    public fun register(component: ComponentType, map: (op: Int) -> Int?) {
        mappings[component.packed] = map
    }

    public fun resumeSub(component: ComponentType, op: Int): Int? =
        mappings[component.packed]?.invoke(op)
}
