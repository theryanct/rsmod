package org.rsmod.content.interfaces.bank.configs

import org.rsmod.api.type.editors.comp.ComponentEditor

/**
 * Adds op2 "Sort" (bank auto-sort, BankSortScript) to the bank's Swap/Insert toggle. Client script
 * 287 only rewrites op1 ("Enable Swap/Insert") on it, so a cache op2 survives.
 *
 * Note: this changes the identity hashes of `bankmain:swap_insert` and `bankmain`; refs to both
 * must stay unhashed.
 */
internal object BankSortEdits : ComponentEditor() {
    init {
        editOps(BankComponents.rearrange_mode_toggle, 2 to "Sort")
    }
}
