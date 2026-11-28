package org.rsmod.content.interfaces.collection

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.interfaces
import org.rsmod.api.config.refs.varps
import org.rsmod.api.death.NpcDropsEvent
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.player.events.CollectionLogObtained
import org.rsmod.api.player.output.UpdateInventory
import org.rsmod.api.player.output.VarpSync
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.output.runClientScript
import org.rsmod.api.player.ui.ifSetEvents
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.player.vars.resyncVar
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onIfOpen
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.Inventory
import org.rsmod.game.type.enums.EnumTypeList
import org.rsmod.game.type.interf.IfButtonOp
import org.rsmod.game.type.interf.IfEvent
import org.rsmod.game.type.inv.InvTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.struct.StructTypeList
import org.rsmod.game.type.util.UncheckedType
import org.rsmod.game.type.varbit.VarBitTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

private val logger = InlineLogger()

/**
 * The collection log (rev 243 `collection`, opened from the journal's Summary tab).
 *
 * - Recording: npc drops ([NpcDropsEvent]) and other sources ([CollectionLogObtained]) add to
 *   [Player.collectionLog]; the first of an obj gives the "New item added..." message. Kills
 *   ([NpcKilledEvent]) count by npc name.
 * - Drawing: tab and category clicks send the page's obtained objs in inv `collection_transmit`,
 *   its kill-count lines in `collection_category_count{,2,3}` (each the sum of its npcs' kills,
 *   `kill_counts.tsv`: Barrows = the six brothers), then run client script collection_draw (7797)
 *   with the tab's list components, the tab struct and the selected category.
 * - Counts: the totals varps (`collection_count*`, also shown on the Summary tab) and each
 *   category's "completed" varbit are recomputed from the log; they are not saved separately.
 *
 * Not recorded yet: clue caskets (no clues), minigame rewards, quest rewards (no current quest
 * reward is a logged obj), search and the overview interface.
 */
class CollectionLogScript
@Inject
constructor(
    private val enumTypes: EnumTypeList,
    private val structTypes: StructTypeList,
    private val objTypes: ObjTypeList,
    private val invTypes: InvTypeList,
    private val varBitTypes: VarBitTypeList,
) : PluginScript() {
    // Lazy, not built in startup(): other scripts may inject their own (never started) instance.
    private val index by lazy { CollectionLogIndex.load(enumTypes, structTypes) }

    /** Category struct -> kill-count varp -> npc names (lowercase) whose kills are summed. */
    private val killCountLines by lazy { loadKillCountLines() }

    private val totals =
        listOf(
            varps.collection_count_bosses to varps.collection_count_bosses_max,
            varps.collection_count_raids to varps.collection_count_raids_max,
            varps.collection_count_clues to varps.collection_count_clues_max,
            varps.collection_count_minigames to varps.collection_count_minigames_max,
            varps.collection_count_other to varps.collection_count_other_max,
        )

    override fun ScriptContext.startup() {
        logger.info {
            "Collection log: ${index.tabs.sumOf { it.categories.size }} categories, " +
                "${index.allItems.size} items."
        }

        onPlayerLogin { player.syncCounts() }
        onIfOpen(interfaces.collection) { player.openLog() }

        for ((slot, button) in CollectionLogComponents.tabs.withIndex()) {
            onIfOverlayButton(button) {
                if (op == IfButtonOp.Op1) {
                    player.drawPage(slot, 0)
                }
            }
        }
        for ((slot, list) in CollectionLogComponents.lists.withIndex()) {
            onIfOverlayButton(list) {
                if (op == IfButtonOp.Op1) {
                    player.drawPage(slot, comsub)
                }
            }
        }

        onEvent<NpcDropsEvent> {
            for ((type, count) in drops) {
                hero.record(objTypes.uncert(type).id, count)
            }
        }
        onEvent<CollectionLogObtained> { player.record(obj, count) }
        onEvent<NpcKilledEvent> {
            val name = npc.name.lowercase()
            val kills = hero.collectionLog.kills
            kills[name] = (kills[name] ?: 0) + 1
        }
    }

    private fun Player.record(obj: Int, count: Int) {
        val id = index.countedId[obj] ?: return
        val obtained = collectionLog.obtained
        val previous = obtained[id] ?: 0
        obtained[id] = previous + count
        if (previous == 0) {
            val name = objTypes[id]?.name ?: return
            mes("New item added to your collection log: <col=ef1020>$name</col>")
            syncCounts()
        }
    }

    private fun Player.openLog() {
        syncCounts()
        resyncCounts()
        for ((slot, list) in CollectionLogComponents.lists.withIndex()) {
            val size = index.tabs.getOrNull(slot)?.categories?.size ?: continue
            if (size > 0) {
                ifSetEvents(list, 0 until size, IfEvent.Op1)
            }
        }
        drawPage(0, 0)
    }

    @OptIn(UncheckedType::class)
    private fun Player.drawPage(slot: Int, categoryIndex: Int) {
        val tab = index.tabs.getOrNull(slot) ?: return
        val category = tab.categories.getOrNull(categoryIndex) ?: return

        val inv = Inventory.create(invTypes[CollectionLogInvs.transmit])
        val obtained = collectionLog.obtained
        var invSlot = 0
        for (id in category.items) {
            val count = obtained[id] ?: continue
            if (invSlot >= inv.size) {
                break
            }
            inv[invSlot++] = InvObj(id, count)
        }
        UpdateInventory.updateInvFull(this, inv)

        // Written straight to the client: collection_draw reads them as soon as it runs.
        val lines = killCountLines[category.struct].orEmpty()
        val kills = collectionLog.kills
        for (varp in CollectionLogVarps.kill_count_lines) {
            val count = lines[varp.id].orEmpty().sumOf { kills[it] ?: 0 }
            VarpSync.writeVarp(this, varp, count)
        }

        val (layer, background, text, scrollbar) = tab.components
        runClientScript(
            COLLECTION_DRAW,
            tab.slot,
            layer,
            background,
            text,
            scrollbar,
            tab.struct,
            categoryIndex,
        )
    }

    /** Recomputes the totals varps and the category "completed" varbits from the log. */
    private fun Player.syncCounts() {
        val obtained = collectionLog.obtained
        fun Set<Int>.obtainedCount(): Int = count { (obtained[it] ?: 0) > 0 }

        VarPlayerIntMapSetter.set(this, varps.collection_count, index.allItems.obtainedCount())
        VarPlayerIntMapSetter.set(this, varps.collection_count_max, index.allItems.size)
        for ((slot, varpPair) in totals.withIndex()) {
            val tab = index.tabs.getOrNull(slot) ?: continue
            VarPlayerIntMapSetter.set(this, varpPair.first, tab.items.obtainedCount())
            VarPlayerIntMapSetter.set(this, varpPair.second, tab.items.size)
        }
        for (tab in index.tabs) {
            for (category in tab.categories) {
                val varbit = varBitTypes[category.completedVarbit ?: continue] ?: continue
                val complete = category.items.all { (obtained[it] ?: 0) > 0 }
                VarPlayerIntMapSetter.set(this, varbit, if (complete) 1 else 0)
            }
        }
    }

    /** Login sets vars before the client is ready; re-send them when the log opens. */
    private fun Player.resyncCounts() {
        resyncVar(varps.collection_count)
        resyncVar(varps.collection_count_max)
        for ((count, max) in totals) {
            resyncVar(count)
            resyncVar(max)
        }
        val completedVarps =
            index.tabs
                .flatMap { it.categories }
                .mapNotNull { category -> category.completedVarbit?.let { varBitTypes[it] } }
                .map { it.baseVar }
                .distinct()
        for (varp in completedVarps) {
            resyncVar(varp)
        }
    }

    private fun loadKillCountLines(): Map<Int, Map<Int, List<String>>> {
        val input =
            checkNotNull(CollectionLogScript::class.java.getResourceAsStream("kill_counts.tsv")) {
                "kill_counts.tsv not found"
            }
        val lines = HashMap<Int, MutableMap<Int, List<String>>>()
        input.bufferedReader().use { reader ->
            for (line in reader.readLines()) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue
                }
                val (struct, varp, npcs) = line.split(TAB)
                lines.getOrPut(struct.toInt()) { HashMap() }[varp.toInt()] = npcs.split(',')
            }
        }
        return lines
    }

    private companion object {
        /** Client script `collection_draw` (tab, layer, background, text, scrollbar, tab, page). */
        private const val COLLECTION_DRAW = 7797

        private const val TAB = '\u0009'
    }
}
