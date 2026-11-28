package org.rsmod.content.other.xplamps

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.events.interact.HeldObjEvents
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.onIfModalButton
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onOpHeld2
import org.rsmod.api.script.onOpHeld3
import org.rsmod.api.script.onOpHeld4
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.interf.InterfaceReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.game.type.comp.ComponentType
import org.rsmod.game.type.interf.IfEvent
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.stat.StatType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * XP lamps and books: their "Rub" / "Read" op opens the real skill picker (`xpreward`, interface
 * 240). Its onload (cs2 `xpreward_init`) draws the skills; the picked skill is varp `if1` (cs2
 * `xpreward_update` reads it through enum 681, keys 1..23 -> stats, see [LampComponents], and
 * redraws on its var-transmit), so a skill click sets `if1` to its key and Confirm grants the xp.
 *
 * Lamp XP (OSRS Wiki): genie lamp 10 x the skill's level, book of knowledge 15 x level, X Marks the
 * Spot's antique lamp a flat 300. Add more lamps to [LAMPS]. The xp ignores personal xp rates.
 */
class XpLampScript @Inject constructor(private val objTypes: ObjTypeList) : PluginScript() {
    /** The lamp each player is choosing a skill for. */
    private val pending = HashMap<Int, Lamp>()

    override fun ScriptContext.startup() {
        for (lamp in LAMPS) {
            val type = lamp.obj
            val op =
                objTypes[type].iop.indexOfFirst {
                    it.equals("Rub", ignoreCase = true) || it.equals("Read", ignoreCase = true)
                } + 1
            val event =
                when (op) {
                    1 -> HeldObjEvents.Op1::class.java
                    2 -> HeldObjEvents.Op2::class.java
                    3 -> HeldObjEvents.Op3::class.java
                    4 -> HeldObjEvents.Op4::class.java
                    else -> continue
                }
            if (eventBus.contains(event, type.id)) continue
            when (op) {
                1 -> onOpHeld1(type) { open(lamp) }
                2 -> onOpHeld2(type) { open(lamp) }
                3 -> onOpHeld3(type) { open(lamp) }
                else -> onOpHeld4(type) { open(lamp) }
            }
        }
        for (skill in LampComponents.skills) {
            onIfModalButton(skill.button) { select(skill.key) }
        }
        onIfModalButton(LampComponents.confirm) { confirm() }
    }

    private fun ProtectedAccess.open(lamp: Lamp) {
        pending[player.uid.packed] = lamp
        VarPlayerIntMapSetter.set(player, LampVarps.if1, 0)
        ifOpenMainModal(LampInterfaces.xpreward)
        for (skill in LampComponents.skills) {
            ifSetEvents(skill.button, -1..-1, IfEvent.Op1)
        }
        ifSetEvents(LampComponents.confirm, -1..-1, IfEvent.Op1)
    }

    private fun ProtectedAccess.select(key: Int) {
        VarPlayerIntMapSetter.set(player, LampVarps.if1, key)
    }

    private fun ProtectedAccess.confirm() {
        val lamp = pending[player.uid.packed] ?: return
        val key = player.vars[LampVarps.if1]
        val skill = LampComponents.skills.firstOrNull { it.key == key } ?: return
        val stat = skill.stat
        ifClose()
        pending.remove(player.uid.packed)
        if (invTotal(inv, lamp.obj) == 0) return
        val level = player.statMap.getBaseLevel(stat).toInt()
        val xp = lamp.xp(level)
        invDel(inv, lamp.obj)
        statAdvance(stat, xp, rate = 1.0, globalRate = 1.0)
        val amount = if (xp % 1.0 == 0.0) "%,d".format(xp.toInt()) else "%,.1f".format(xp)
        mes("You gain $amount ${skill.label} experience.")
    }

    /** A lamp / book: its obj and xp for the chosen skill at base [level]. */
    private class Lamp(val obj: ObjType, val xp: (level: Int) -> Double)

    private companion object {
        val LAMPS =
            listOf(
                Lamp(LampObjs.genie_lamp) { level -> 10.0 * level },
                Lamp(LampObjs.book_of_knowledge) { level -> 15.0 * level },
                Lamp(LampObjs.cluequest_lamp) { 300.0 },
            )
    }
}

internal object LampInterfaces : InterfaceReferences() {
    val xpreward = find("xpreward")
}

internal object LampComponents : ComponentReferences() {
    val confirm = find("xpreward:confirm")

    /** Each skill button, its stat and its enum 681 key (the value cs2 reads from `if1`). */
    val skills =
        listOf(
            LampSkill(find("xpreward:attack"), stats.attack, 1, "Attack"),
            LampSkill(find("xpreward:strength"), stats.strength, 2, "Strength"),
            LampSkill(find("xpreward:ranged"), stats.ranged, 3, "Ranged"),
            LampSkill(find("xpreward:magic"), stats.magic, 4, "Magic"),
            LampSkill(find("xpreward:defence"), stats.defence, 5, "Defence"),
            LampSkill(find("xpreward:hitpoints"), stats.hitpoints, 6, "Hitpoints"),
            LampSkill(find("xpreward:prayer"), stats.prayer, 7, "Prayer"),
            LampSkill(find("xpreward:agility"), stats.agility, 8, "Agility"),
            LampSkill(find("xpreward:herblore"), stats.herblore, 9, "Herblore"),
            LampSkill(find("xpreward:thieving"), stats.thieving, 10, "Thieving"),
            LampSkill(find("xpreward:crafting"), stats.crafting, 11, "Crafting"),
            LampSkill(find("xpreward:runecraft"), stats.runecrafting, 12, "Runecraft"),
            LampSkill(find("xpreward:slayer"), stats.slayer, 20, "Slayer"),
            LampSkill(find("xpreward:farming"), stats.farming, 21, "Farming"),
            LampSkill(find("xpreward:mining"), stats.mining, 13, "Mining"),
            LampSkill(find("xpreward:smithing"), stats.smithing, 14, "Smithing"),
            LampSkill(find("xpreward:fishing"), stats.fishing, 15, "Fishing"),
            LampSkill(find("xpreward:cooking"), stats.cooking, 16, "Cooking"),
            LampSkill(find("xpreward:firemaking"), stats.firemaking, 17, "Firemaking"),
            LampSkill(find("xpreward:woodcutting"), stats.woodcutting, 18, "Woodcutting"),
            LampSkill(find("xpreward:fletching"), stats.fletching, 19, "Fletching"),
            LampSkill(find("xpreward:construction"), stats.construction, 22, "Construction"),
            LampSkill(find("xpreward:hunter"), stats.hunter, 23, "Hunter"),
        )
}

internal class LampSkill(
    val button: ComponentType,
    val stat: StatType,
    val key: Int,
    val label: String,
)

internal object LampObjs : ObjReferences() {
    val genie_lamp = find("macro_genilamp")
    val book_of_knowledge = find("pattern_reward")
    val cluequest_lamp = find("cluequest_lamp")
}

internal object LampVarps : VarpReferences() {
    /** Generic interface varp; `xpreward` keeps the selected skill key (enum 681) here. */
    val if1 = find("if1")
}
