package org.rsmod.api.player.ui

import java.util.WeakHashMap
import org.rsmod.api.config.constants
import org.rsmod.api.player.output.runClientScript
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.type.comp.ComponentType
import org.rsmod.game.type.interf.IfEvent
import org.rsmod.game.type.obj.ObjType

/**
 * One product in the skillmulti ("What would you like to make?") chatbox.
 *
 * @param available `false` greys the product's name; the product stays clickable so the caller can
 *   tell the player what is missing (as OSRS does).
 */
public data class SkillMultiProduct(val obj: ObjType, val name: String, val available: Boolean)

/** The product the player clicked and the quantity chosen with the 1/5/10/X/All buttons. */
public data class SkillMultiChoice(val index: Int, val product: SkillMultiProduct, val quantity: Int)

/**
 * Skillmulti verb types: the key of client enum 1809, which sets the left-click op text (and enum
 * 5178/1810 decide whether the quantity buttons show). Values from the cache.
 */
public object SkillMultiVerb {
    public const val MAKE_SETS: Int = 3
    public const val COOK: Int = 6
    public const val STRING: Int = 10
    public const val CUT: Int = 12
    public const val SMELT: Int = 13
    public const val SPIN: Int = 16
    public const val MAKE: Int = 22
    public const val SMITH: Int = 25
}

/**
 * Interface refs for the skillmulti chatbox, kept in one place. Rev 243 names 18 item slots
 * (`a`-`r`), matching the 18 products the setup script takes.
 */
internal object SkillMultiRefs : InterfaceReferences() {
    val skillmulti = find("skillmulti")
}

/**
 * Client varp the setup script reads (when enum 3623 allows it for the verb) to bind the Space key
 * to a product slot.
 */
internal object SkillMultiVarps : VarpReferences() {
    val previous_selection = find("skillmulti_previousselection")
}

/**
 * Slot Space should pick: the best product the player can make right now. Callers list products
 * from lowest to highest level/tier, so that is the last available one; slot 0 when none are.
 */
internal fun List<SkillMultiProduct>.spaceDefaultIndex(): Int = indexOfLast { it.available }.coerceAtLeast(0)

internal object SkillMultiComponents : ComponentReferences() {
    val items: List<ComponentType> =
        ('a'..'r').map { find("skillmulti:$it") }
}

/**
 * Last quantity each player picked this session ([SKILLMULTI_REMEMBER_ALL] = "All"). The dialog
 * defaults to All until the player picks something else, like OSRS. Weak keys drop on logout.
 */
internal val skillMultiLastQuantity = WeakHashMap<Player, Int>()
internal const val SKILLMULTI_REMEMBER_ALL = Int.MAX_VALUE

/** Max products the client script takes; the interface may name fewer slots (see refs). */
internal const val SKILLMULTI_SCRIPT_SLOTS = 18
internal const val SKILLMULTI_SETUP_SCRIPT = 2046
internal const val SKILLMULTI_MAX_QUANTITY = 28

private const val GREY_NAME = "<col=7f7f7f>"

/**
 * Opens the skillmulti chatbox via client script `skillmulti_setup` (2046): args are verb type,
 * "title|name1|name2|...", max quantity, 18 objs (-1 = empty), suggested quantity.
 */
internal fun Player.ifSkillMulti(
    verb: Int,
    title: String,
    products: List<SkillMultiProduct>,
    maxQuantity: Int,
    suggestedQuantity: Int,
    eventBus: EventBus,
) {
    ifOpenChat(SkillMultiRefs.skillmulti, constants.modal_infinitewidthandheight, eventBus)
    val names = products.joinToString("|") { if (it.available) it.name else GREY_NAME + it.name }
    val objs = List(SKILLMULTI_SCRIPT_SLOTS) { products.getOrNull(it)?.obj?.id ?: -1 }
    runClientScript(
        SKILLMULTI_SETUP_SCRIPT,
        listOf<Any>(verb, "$title|$names", maxQuantity) + objs + suggestedQuantity,
    )
    // The client resumes with the clicked slot and sub = chosen quantity (1-28).
    for (slot in SkillMultiComponents.items.take(products.size)) {
        ifSetEvents(slot, 0..SKILLMULTI_MAX_QUANTITY, IfEvent.PauseButton)
    }
}
