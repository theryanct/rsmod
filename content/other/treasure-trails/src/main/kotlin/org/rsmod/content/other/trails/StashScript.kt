package org.rsmod.content.other.trails

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.constructionLvl
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.isLocOpBound
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.type.symbols.name.NameMapping
import org.rsmod.game.type.dbrow.DbRowTypeList
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.varbit.VarBitTypeList
import org.rsmod.game.type.varp.VarpType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * STASH units, the POH-free version (they are world objects anyway): "Build" with the tier's
 * Construction level, a hammer, a saw, 2 planks and 10 nails of one metal; "Search" then stores
 * or takes back exactly the outfit of the emote clue that uses that unit (OSRS Wiki, STASH).
 *
 * Built: the unit's own `hh_constructed_*` varbit. Stored outfit: one bit per unit in
 * [TrailVarps.stash_beginner] / [TrailVarps.stash_easy].
 */
class StashScript
@Inject
constructor(
    private val dbRows: DbRowTypeList,
    private val names: NameMapping,
    private val objTypes: ObjTypeList,
    private val locTypes: LocTypeList,
    private val varBitTypes: VarBitTypeList,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private class Stash(
        val loc: Int,
        val tier: ClueTier,
        val bit: Int,
        val outfit: List<Int>,
        val builtVarbit: Int?,
    )

    private val stashes: List<Stash> by lazy { loadStashes() }

    override fun ScriptContext.startup() {
        for (stash in stashes) {
            val type = locTypes[stash.loc] ?: continue
            if (!isLocOpBound(type, 1)) {
                onOpLoc1(type) { build(stash) }
            }
            if (!isLocOpBound(type, 2)) {
                onOpLoc2(type) { search(stash) }
            }
        }
    }

    private fun loadStashes(): List<Stash> {
        val steps = ClueSteps.load(dbRows, names.dbTables)
        val locNames = names.locs.entries.associate { (name, id) -> id to name }
        val out = ArrayList<Stash>()
        for (tier in listOf(ClueTier.Beginner, ClueTier.Easy)) {
            val byLoc =
                steps.byRow.values
                    .filter { it.tier == tier && it.kind == ClueKind.Emote && it.stashLoc != null }
                    .associateBy { checkNotNull(it.stashLoc) }
            for ((bit, loc) in byLoc.keys.sorted().withIndex()) {
                if (bit >= Int.SIZE_BITS) {
                    break
                }
                // hh_easy001 -> hh_constructed_easy001
                val varbit =
                    locNames[loc]?.removePrefix("hh_")?.let { names.varbits["hh_constructed_$it"] }
                out += Stash(loc, tier, bit, byLoc.getValue(loc).outfit, varbit)
            }
        }
        return out
    }

    private fun storedVarp(tier: ClueTier): VarpType =
        if (tier == ClueTier.Beginner) TrailVarps.stash_beginner else TrailVarps.stash_easy

    private fun level(tier: ClueTier): Int = if (tier == ClueTier.Beginner) 12 else 27

    private fun ProtectedAccess.isBuilt(stash: Stash): Boolean {
        val varbit = varBitTypes[stash.builtVarbit ?: return true] ?: return true
        return vars[varbit] != 0
    }

    private fun ProtectedAccess.isStored(stash: Stash): Boolean =
        vars[storedVarp(stash.tier)] and (1 shl stash.bit) != 0

    private fun ProtectedAccess.setStored(stash: Stash, stored: Boolean) {
        val varp = storedVarp(stash.tier)
        val bits = vars[varp]
        val value = if (stored) bits or (1 shl stash.bit) else bits and (1 shl stash.bit).inv()
        VarPlayerIntMapSetter.set(player, varp, value)
    }

    private suspend fun ProtectedAccess.build(stash: Stash) {
        if (isBuilt(stash)) {
            mes("This STASH unit has already been built.")
            return
        }
        val level = level(stash.tier)
        if (player.constructionLvl < level) {
            mes("You need a Construction level of $level to build this STASH unit.")
            return
        }
        if (invTotal(inv, TrailObjs.hammer) == 0 || invTotal(inv, TrailObjs.saw) == 0) {
            mes("You need a hammer and a saw to build this STASH unit.")
            return
        }
        val nails = TrailObjs.nails.firstOrNull { invTotal(inv, it) >= NAILS }
        if (invTotal(inv, TrailObjs.plank) < PLANKS || nails == null) {
            mes("You need $PLANKS planks and $NAILS nails of the same metal to build this.")
            return
        }
        anim(TrailSeqs.build)
        delay(2)
        invDel(inv, TrailObjs.plank, PLANKS)
        invDel(inv, nails, NAILS)
        val varbit = stash.builtVarbit?.let { varBitTypes[it] }
        if (varbit != null) {
            VarPlayerIntMapSetter.set(player, varbit, 1)
        }
        statAdvance(stats.construction, XP * xpMods.get(player, stats.construction))
        mes("You build a STASH unit.")
    }

    private fun ProtectedAccess.search(stash: Stash) {
        if (!isBuilt(stash)) {
            mes("You need to build this STASH unit first.")
            return
        }
        if (stash.outfit.isEmpty()) {
            mes("There is nothing to store in this STASH unit.")
            return
        }
        if (isStored(stash)) {
            if (inv.freeSpace() < stash.outfit.size) {
                mes("You don't have enough inventory space to take the items out.")
                return
            }
            for (obj in stash.outfit) {
                invAdd(inv, objTypes[obj] ?: continue)
            }
            setStored(stash, false)
            mes("You take the items out of the STASH unit.")
            return
        }
        val missing = stash.outfit.filter { obj -> inv.none { it?.id == obj } }
        if (missing.isNotEmpty()) {
            val needed = missing.mapNotNull { objTypes[it]?.name?.lowercase() }.joinToString(", ")
            mes("This STASH unit is for: $needed.")
            return
        }
        for (obj in stash.outfit) {
            invDel(inv, objTypes[obj] ?: continue, 1)
        }
        setStored(stash, true)
        mes("You store the items in the STASH unit.")
    }

    private companion object {
        private const val PLANKS = 2
        private const val NAILS = 10
        private const val XP = 150.0
    }
}
