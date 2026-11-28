package org.rsmod.api.type.editors.comp

import org.rsmod.api.type.editors.TypeEditor
import org.rsmod.game.type.comp.ComponentType

/**
 * An op-label edit on one interface component: [ops] maps a 1-based op slot to its new text.
 * [packed] is filled in by [ComponentEditorResolver].
 */
public data class ComponentOpEdit(
    val name: String,
    val ops: Map<Int, String>,
    val packed: Int = -1,
)

/**
 * Edits client interface components. Only op labels (plus their transmit flags) can be edited:
 * enough to add right-click options such as "Bank" on a side-panel tab.
 *
 * Note: this changes the component's identity hash, so refs to an edited component must use
 * `find(name)` without a hash. Edits are written into the interfaces archive by the
 * cache packer, which refuses to touch a component that doesn't re-encode byte-for-byte.
 *
 * Ops on dynamic (cc) children can't be edited here: they are created by client scripts.
 */
public abstract class ComponentEditor : TypeEditor<ComponentOpEdit>() {
    public fun editOps(component: ComponentType, vararg ops: Pair<Int, String>) {
        require(ops.isNotEmpty()) { "No ops given for ${component.internalNameValue}." }
        require(ops.all { it.first in 1..MAX_OP_SLOT }) { "Op slots must be 1-$MAX_OP_SLOT." }
        cache += ComponentOpEdit(component.internalNameValue, ops.toMap())
    }

    public companion object {
        public const val MAX_OP_SLOT: Int = 10

        /**
         * [events] with the cache transmit bit (`1 shl slot`) set for every edited op slot. A
         * static component's op only reaches the server when its cache flags transmit it: the
         * server checks static (comsub -1) clicks against these flags, not `if_setevents`.
         */
        public fun applyEvents(events: Int, slots: Set<Int>): Int =
            slots.fold(events) { acc, slot -> acc or (1 shl slot) }

        /** [current] ops with [ops] applied (1-based), growing the array with "" as needed. */
        public fun applyOps(current: Array<String>, ops: Map<Int, String>): Array<String> {
            val size = maxOf(current.size, ops.keys.maxOrNull() ?: 0)
            return Array(size) { index -> ops[index + 1] ?: current.getOrElse(index) { "" } }
        }
    }
}
