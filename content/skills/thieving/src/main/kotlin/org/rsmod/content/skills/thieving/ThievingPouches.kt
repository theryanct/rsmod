package org.rsmod.content.skills.thieving

import jakarta.inject.Inject
import org.rsmod.api.config.refs.objs
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.isHeldOpBound
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onOpHeld2
import org.rsmod.api.script.onOpHeld3
import org.rsmod.api.script.onOpHeld4
import org.rsmod.api.script.onOpHeld5
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Coin pouches (OSRS Wiki "Coin pouch"): pickpocketing a target that pays coins gives that target's
 * pouch instead; up to [Pouches.CAP] of one pouch can be held (more stops pickpocketing until
 * they're opened). "Open" / "Open-all" turns every pouch in the stack into its coins.
 */
class ThievingPouches @Inject constructor(private val objTypes: ObjTypeList) : PluginScript() {
    override fun ScriptContext.startup() {
        for ((name, _) in Pouches.coins) {
            val type = PouchObjs.byName.getValue(name)
            val ops = objTypes[type].iop
            for ((index, text) in ops.withIndex()) {
                val label = text?.lowercase() ?: continue
                if (label != "open" && label != "open-all") continue
                val op = index + 1
                if (isHeldOpBound(type, op)) continue
                when (op) {
                    1 -> onOpHeld1(type) { open(type, name) }
                    2 -> onOpHeld2(type) { open(type, name) }
                    3 -> onOpHeld3(type) { open(type, name) }
                    4 -> onOpHeld4(type) { open(type, name) }
                    5 -> onOpHeld5(type) { open(type, name) }
                }
            }
        }
    }

    private fun ProtectedAccess.open(type: ObjType, name: String) {
        val count = invTotal(inv, type)
        if (count == 0) return
        val (min, max) = Pouches.coins.getValue(name)
        var coins = 0L
        repeat(count) { coins += if (max > min) random.of(min, max) else min }
        invDel(inv, type, count)
        invAdd(inv, objs.coins, coins.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        mes(if (count == 1) "You open the coin pouch." else "You open all of the pouches.")
    }
}

/** Pickpocket target (thieving.tsv title) -> its Wiki coin pouch, and pouch -> coins inside. */
internal object Pouches {
    /** Wiki: 28 of one pouch (Ardougne diary raises it; not modelled). */
    const val CAP = 28

    val coins: Map<String, Pair<Int, Int>> =
        mapOf(
            "pickpocket_coin_pouch_citizen" to (3 to 3),
            "pickpocket_coin_pouch_farmer" to (9 to 9),
            "pickpocket_coin_pouch_ham" to (1 to 21),
            "pickpocket_coin_pouch_warrior" to (18 to 18),
            "pickpocket_coin_pouch_rogue" to (25 to 40),
            "pickpocket_coin_pouch_cavegoblin" to (10 to 50),
            "pickpocket_coin_pouch_guard" to (30 to 30),
            "pickpocket_coin_pouch_fremennik" to (40 to 40),
            "pickpocket_coin_pouch_bandit2" to (40 to 40),
            "pickpocket_coin_pouch_varlamore_wealthy" to (85 to 85),
            "pickpocket_coin_pouch_desertbandit" to (30 to 30),
            "pickpocket_coin_pouch_knight" to (50 to 50),
            "pickpocket_coin_pouch_bandit" to (50 to 50),
            "pickpocket_coin_pouch_watchman" to (60 to 60),
            "pickpocket_coin_pouch_menaphite" to (60 to 60),
            "pickpocket_coin_pouch_paladin" to (80 to 80),
            "pickpocket_coin_pouch_gnome" to (300 to 300),
            "pickpocket_coin_pouch_hero" to (200 to 300),
            "pickpocket_coin_pouch_elf" to (280 to 350),
        )

    private val byTitle: Map<String, String> =
        mapOf(
            "Man" to "citizen",
            "Man (East Ardougne)" to "citizen",
            "Woman" to "citizen",
            "Woman (East Ardougne)" to "citizen",
            "Tourist" to "citizen",
            "Salvager" to "citizen",
            "Citizen (Civitas illa Fortis)" to "citizen",
            "Citizen (Twilight's Promise)" to "citizen",
            "Farmer" to "farmer",
            "Farmer (Kastori)" to "farmer",
            "Farmer (Tal Teklan)" to "farmer",
            "Farmer (Varlamore)" to "farmer",
            "H.A.M. Member" to "ham",
            "Al Kharid warrior" to "warrior",
            "Warrior (Thieving)" to "warrior",
            "Rogue" to "rogue",
            "Guard" to "guard",
            "Guard (Aldarin)" to "guard",
            "Guard (Auburnvale)" to "guard",
            "Guard (Doric's hut)" to "guard",
            "Guard (Hosidius)" to "guard",
            "Guard (Port Sarim jail)" to "guard",
            "Guard (Tlati Rainforest)" to "guard",
            "Guard (Varlamore)" to "guard",
            "Bandit (Pollnivneach)" to "bandit2",
            "Wealthy citizen" to "varlamore_wealthy",
            "Bandit (Bandit Camp)" to "desertbandit",
            "Knight of Ardougne" to "knight",
            "Watchman" to "watchman",
            "Menaphite Thug" to "menaphite",
            "Paladin" to "paladin",
            "Gnome" to "gnome",
            "Gnome woman" to "gnome",
            "Hero" to "hero",
        )

    /** The pouch obj name a pickpocket target's coins come in, or null to keep plain coins. */
    fun pouchFor(title: String): String? = byTitle[title]?.let { "pickpocket_coin_pouch_$it" }
}

internal object PouchObjs : ObjReferences() {
    val byName: Map<String, ObjType> = Pouches.coins.keys.associateWith { find(it) }
}
