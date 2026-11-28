package org.rsmod.content.quests.touristtrap

import org.rsmod.api.invtx.invDel
import org.rsmod.api.player.dialogue.Dialogue
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestDialogueHooks
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.content.quests.touristtrap.TouristTrap.Companion.GUARD_FED
import org.rsmod.content.quests.touristtrap.TouristTrap.Companion.TOURIST_TRAP
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.util.PathingEntityCommon
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.obj.ObjType
import org.rsmod.map.CoordGrid
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

/* Shared by the Tourist Trap scripts. */

/**
 * Hooks for the Tourist Trap transcript: like SimpleQuestHooks, but the transcript's {{mes}} lines
 * ([TT_MES]) go to the chatbox as game messages, and `{{trandom}}` branches ("Random Dialogue N")
 * pick one at random.
 */
internal class TtHooks(
    private val access: ProtectedAccess,
    private val conditions: Map<String, () -> Boolean> = emptyMap(),
    private val options: Map<String, suspend () -> Unit> = emptyMap(),
    private val boxes: Map<String, suspend () -> Boolean> = emptyMap(),
    private val actions: Map<String, suspend (Dialogue) -> Unit> = emptyMap(),
    randomCount: Int = 5,
) : QuestDialogueHooks {
    private val randomPick = 1 + access.random.of(randomCount)

    override fun condition(text: String): Boolean {
        if (text.startsWith(RANDOM)) return text.removePrefix(RANDOM).toIntOrNull() == randomPick
        return conditions[text]?.invoke() ?: false
    }

    override suspend fun Dialogue.onOption(label: String) {
        options[label]?.invoke()
    }

    override suspend fun Dialogue.onBox(text: String): Boolean {
        val handler = boxes[text]
        if (handler != null && handler()) return true
        if (text in TT_MES) {
            access.mes(rewrite(text)) // Wiki {{mes}}
            return true
        }
        return false
    }

    override suspend fun Dialogue.onAction(tag: String) {
        actions[tag]?.invoke(this)
    }

    override fun rewrite(text: String): String = text.replace("[him/her/them]", "them")

    private companion object {
        const val RANDOM = "Random Dialogue "
    }
}

internal val ttSpeakers: Map<String, NpcType> by lazy {
    mapOf(
        "Irena" to TtNpcs.irena,
        "Ana" to TtNpcs.ana,
        "Ana (in a Barrel)" to TtNpcs.ana,
        "Ana (in-a-barrel)," to TtNpcs.ana,
        "Ana-in-barrel" to TtNpcs.ana,
        "Guard" to TtNpcs.guards.first(),
        "Mercenary" to TtNpcs.mercenaries.first(),
        "Mercenary Captain" to TtNpcs.captain,
        "Captain Siad" to TtNpcs.siad_vis,
        "Male slave" to TtNpcs.male_slave,
        "Escaping slave" to TtNpcs.escaped_slave,
        "Al Shabim" to TtNpcs.al_shabim,
        "Bedabin Nomad Guard" to TtNpcs.nomad_guard,
        "Mine cart driver" to TtNpcs.driver,
    )
}

internal suspend fun ProtectedAccess.ttSection(
    dialogue: QuestDialogue,
    npc: Npc?,
    section: String,
    hooks: QuestDialogueHooks = TtHooks(this),
) {
    runQuestSection(npc, dialogue, section, hooks, ttSpeakers)
}

/* Quest state */

internal val Player.ttState: Int
    get() = questState(TOURIST_TRAP)

internal fun Player.setTtState(value: Int) = setQuestState(TOURIST_TRAP, value)

internal fun Player.ttBits(shift: Int, mask: Int): Int = (vars[TtVarps.progress] shr shift) and mask

internal fun Player.setTtBits(shift: Int, mask: Int, value: Int) {
    val old = vars[TtVarps.progress]
    val new = (old and (mask shl shift).inv()) or ((value and mask) shl shift)
    VarPlayerIntMapSetter.set(this, TtVarps.progress, new)
}

internal fun Player.setTtVarBit(varbit: org.rsmod.game.type.varbit.VarBitType, value: Int) =
    VarPlayerIntMapSetter.set(this, varbit, value)

internal fun ProtectedAccess.ttOwns(obj: ObjType): Boolean =
    invTotal(inv, obj) > 0 || invTotal(worn, obj) > 0 || invTotal(bank, obj) > 0

internal fun Player.hasInInv(obj: ObjType): Boolean = inv.any { it?.id == obj.id }

/** Removes every [obj] in the inventory (non-stackable quest items). */
internal fun Player.delAll(obj: ObjType) {
    val count = inv.count { it?.id == obj.id }
    if (count > 0) invDel(inv, obj, count)
}

/* Clothes and the guards' rules */

private const val SLOT_AMULET = 2
private const val SLOT_RING = 12
private const val SLOT_AMMO = 13
private const val SLOT_BODY = 4
private const val SLOT_LEGS = 7
private const val SLOT_FEET = 10

/** Weapons or armour worn: anything but jewellery, ammo and desert or slave clothes (Wiki). */
internal fun Player.wearsGear(): Boolean {
    for ((slot, obj) in worn.objs.withIndex()) {
        if (obj == null || slot == SLOT_AMULET || slot == SLOT_RING || slot == SLOT_AMMO) continue
        val clothes =
            when (slot) {
                SLOT_BODY -> listOf(TtObjs.desert_shirt, TtObjs.slave_shirt)
                SLOT_LEGS -> listOf(TtObjs.desert_robe, TtObjs.slave_robe)
                SLOT_FEET -> listOf(TtObjs.desert_boots, TtObjs.slave_boots)
                else -> emptyList()
            }
        if (clothes.none { it.id == obj.id }) return true
    }
    return false
}

internal fun Player.wearsSlaveRobes(): Boolean =
    worn.any { it?.id == TtObjs.slave_shirt.id } &&
        worn.any { it?.id == TtObjs.slave_robe.id } &&
        worn.any { it?.id == TtObjs.slave_boots.id }

internal fun Player.wearsAnySlaveClothes(): Boolean =
    worn.any { it != null && (it.id == TtObjs.slave_shirt.id || it.id == TtObjs.slave_robe.id || it.id == TtObjs.slave_boots.id) }

/* Areas */

/** Inside the camp's walls (level 0) and Captain Siad's quarters upstairs. */
internal fun CoordGrid.inCamp(): Boolean =
    x in 3274..3306 && z in 3013..3040 && level in 0..1

/** The cell on the ground floor of the camp's headquarters. */
internal fun CoordGrid.inCell(): Boolean = x in 3284..3287 && z in 3033..3036 && level == 0

/** The camp's mine (below the camp). */
internal fun CoordGrid.inMine(): Boolean = x in 3264..3327 && z in 9400..9471 && level == 0

/** The punishment mine behind the locked gate. */
internal fun CoordGrid.inPrisonMine(): Boolean = x in 3283..3292 && z in 9426..9454 && level == 0

internal val CELL = CoordGrid(3285, 3034, 0)
internal val PRISON_MINE = CoordGrid(3288, 9440, 0)
private val DESERT_DUMPS = listOf(CoordGrid(3250, 3050, 0), CoordGrid(3240, 3062, 0), CoordGrid(3232, 3044, 0))

/** The free tile nearest [target] (the ladder fallback's search), else [target] itself. */
internal fun CollisionFlagMap.nearestFree(target: CoordGrid): CoordGrid {
    for (radius in 0..FREE_RADIUS) {
        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz)) != radius) continue
                val tile = target.translate(dx, dz)
                if (!isZoneAllocated(tile.x, tile.z, tile.level)) continue
                if (this[tile.x, tile.z, tile.level] and BLOCKED == 0) return tile
            }
        }
    }
    return target
}

private const val FREE_RADIUS = 4
private const val BLOCKED = CollisionFlag.BLOCK_WALK or CollisionFlag.LOC

internal fun Player.jumpTo(collision: CollisionFlagMap, target: CoordGrid) {
    PathingEntityCommon.telejump(this, collision, collision.nearestFree(target))
}

/* Getting caught */

/**
 * Ana goes back to the mine if the player had her (inside the camp): the barrel is taken, the
 * multis reset and the quest drops back to the guard having had his pineapple.
 */
internal fun Player.loseAna() {
    if (ttState !in TouristTrap.ANA_BARREL until TouristTrap.ESCAPED) return
    delAll(TtObjs.ana_barrel)
    setTtVarBit(TtVarBits.ana_state, 0)
    setTtVarBit(TtVarBits.winch_barrel, 0)
    setTtVarBit(TtVarBits.cart_ana, 0)
    setTtState(GUARD_FED)
}

/**
 * Thrown in the cell (surface) or the punishment mine ([underground]); the guards may find the gate
 * and cell keys (Wiki: they "occasionally" confiscate them; ours is a coin flip each).
 */
internal fun Player.jail(collision: CollisionFlagMap, underground: Boolean, findKeys: () -> Boolean) {
    loseAna()
    mes("The guards search you!")
    if (hasInInv(TtObjs.metal_key) && findKeys()) {
        delAll(TtObjs.metal_key)
        mes("The guards find the main gate key and remove it!")
    }
    if (hasInInv(TtObjs.cell_key) && findKeys()) {
        delAll(TtObjs.cell_key)
        mes("The guards find the cell door key and remove it!")
    }
    if (underground) {
        // Authored (not Wiki): the mine's punishment area instead of the cell.
        mes("You are roughed up by the guards and thrown into the punishment mine.")
        jumpTo(collision, PRISON_MINE)
    } else {
        mes("You are roughed up by the guards and manhandled into a cell.")
        jumpTo(collision, CELL)
    }
}

/** Dumped in the desert north-west of the camp (Wiki), filled waterskins emptied. */
internal fun ProtectedAccess.dumpInDesert(collision: CollisionFlagMap) {
    player.loseAna()
    for (skin in TtObjs.waterskins) {
        val count = invTotal(inv, skin)
        if (count == 0) continue
        invDel(inv, skin, count)
        invAdd(inv, TtObjs.waterskin_empty, count)
    }
    player.jumpTo(collision, random.pick(DESERT_DUMPS))
}

internal fun ProtectedAccess.hasFilledWaterskin(): Boolean = TtObjs.waterskins.any { invTotal(inv, it) > 0 }

/** The transcript's {{mes}} lines (game messages, not message boxes). */
internal val TT_MES: Set<String> =
    setOf(
        "You see some footsteps in the sand.",
        "This gate needs a key in order to be opened.",
        "Guard: You won't be needing that water anymore!",
        "The guards throw your water away.",
        "A guard approaches you and looks very angry, he slaps you across the face.",
        "An angry guard approaches you and whips out his sword.",
        "The guards grab you and rough you up a bit.",
        "You're grabbed and manhandled onto a cart.",
        "Sometime later you're dumped in the middle of the desert.",
        "The guards move off leaving you stranded in the desert.",
        "You kill the captain!",
        "After killing the captain, you remember that you stored a mining gate key in your bank.",
        "The camel turns its head and glares at you.",
        "The guards search you thoroughly as you go through the gates.",
        "Guard: Hey - you with the weapon!",
        "Guard: You're not allowed in here!",
        "Guard: Quick, men, get [him/her/them]!!",
        "Guard: Hey, they're interesting clothes!",
        "Guard: You're no slave.",
        "Guard: What are you doing in here?",
        "The guards search you!",
        "The guards find the main gate key and remove it!",
        "The guards find the cell door key and remove it!",
        "You are roughed up by the guards and manhandled into a cell.",
        "The door seems to be pretty locked.",
        "You bend the bars back.",
        "You prepare to squeeze through the bars.",
        "You scrape your hands and knees as you climb up.",
        "You fail!",
        "Slave: Oh oh!",
        "Guard: Oi, what are you two doing?",
        "The doors open with some effort!",
        "This guard looks as if he's been down here a while.",
        "The guard cuffs you around head.",
        "The captain looks up from his work as you address him.",
        "The Captain goes back to his work.",
        "The Captain goes back to his desk.",
        "You search the captains desk while he's not looking...",
        "...but you find nothing of interest.",
        "The captain spots you before you manage to open the chest...",
        "This chest needs a key to unlock it.",
        "You walk into the tent.",
        "You walk back out of the tent.",
        "To forge items use the metal you wish to work with the anvil.",
        "You walk into the dark of the cavern...",
        "And emerge in a different part of this huge underground complex.",
        "You walk into the darkness of the cavern...",
        "... and emerge in a different part of this huge underground complex.",
        "You cannot carry another barrel while you're carrying Ana.",
        "You can only manage one of these at a time.",
        "This guard looks as if he's been in the sun for a while.",
        "You fail to fit yourself into the cart in time before it starts its journey.",
        "You bang your head on the cart as you try to jump in.",
        "You jump onto the edge of the cart, but fall off.",
        "You decide not to get into the dangerous looking mine cart.",
        "You search the gates... yep, they're locked.",
        "A nearby guard looks you over. He doesn't seem too impressed.",
        "This gate seems to be locked.",
        "The nearby guard doesn't want to let you in.",
        "Ana's outburst attracts the guards, they come running over.",
        "Guard: Hey! What's going on here then?",
        "The guards drag Ana away and then throw you into a cell.",
        "You can see plenty of barrels, but not the one with Ana in it.",
        "You pick up Ana in a Barrel.",
        "Ana is in a barrel on the lift underground, she's not here!",
        "You try to operate the winch.",
        "The cart driver goes back to his work.",
        "The cart driver chuckles to himself.",
        "The Cart driver goes back to his work.",
        "You quickly slope away and hide from the guards.",
        "The cart driver is busy loading the cart up ...",
        "You decide not to get onto the cart.",
        "Ana spots Irena and waves...",
        "Irena seems happy now that her daughter has returned home.",
        "Irena goes back to work.",
    )
