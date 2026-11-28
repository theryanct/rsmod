package org.rsmod.content.skills.cooking.scripts

import java.util.concurrent.CopyOnWriteArrayList
import org.rsmod.game.entity.Player
import org.rsmod.game.type.obj.ObjType

/**
 * Lets another module change what a heat recipe gives one player, without a second binding on
 * the heat sources. E.g. Tai Bwo Wannai Trio: raw karambwan cooks into poison karambwan (level 1,
 * 80 xp; burns as usual) until Tinsay teaches the player to cook it properly.
 *
 * Register from a plugin's `startup()`; the first override that returns a product wins.
 */
public object CookingOverrides {
    /** A successful cook gives [obj] and [xp] (before xp modifiers) and needs [level]. */
    public class Product(public val obj: ObjType, public val xp: Double, public val level: Int)

    public fun interface Override {
        /** The product for [raw] for this player, or null to keep the recipe's. */
        public fun product(player: Player, raw: ObjType): Product?
    }

    private val overrides = CopyOnWriteArrayList<Override>()

    public fun register(override: Override) {
        overrides += override
    }

    internal fun product(player: Player, raw: ObjType): Product? =
        overrides.firstNotNullOfOrNull { it.product(player, raw) }
}
