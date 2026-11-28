package org.rsmod.content.minigames.teamrewards

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.isNpcOpBound
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc2
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onOpNpc4
import org.rsmod.api.script.onOpNpc5
import org.rsmod.api.shops.Shops
import org.rsmod.api.type.builders.inv.InvBuilder
import org.rsmod.api.type.refs.inv.InvReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.game.type.inv.InvScope
import org.rsmod.game.type.inv.InvStackType
import org.rsmod.game.type.inv.InvType
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

private val logger = InlineLogger()

/**
 * Team minigame rewards, free (Ryan's rule: team minigames aren't played here, so their reward
 * NPCs hand out the rewards at no cost). Castle Wars (Lanthus), Soul Wars (Nomad), Trouble Brewing
 * (Honest Jimmy), Pest Control (Void Knight), Barbarian Assault (Commander Connad), Fishing Trawler
 * (Murphy: the angler outfit), Last Man Standing (Justine). Clan Wars has no rewards.
 *
 * Each opens a free shop (sell percentage 0, see StandardGpShopOperations) on the NPC's reward
 * op, stocked with every fixed reward item on the OSRS Wiki page (rewards.tsv). Not included:
 * random rolls (BA gambles, Trouble Brewing's clue), imbues (Soul Wars), PC's herb/seed/mineral
 * packs and LMS's animation unlocks. Later polish: each minigame's own reward interface.
 */
class TeamRewardsScript
@Inject
constructor(private val shops: Shops, private val npcTypes: NpcTypeList) : PluginScript() {
    override fun ScriptContext.startup() {
        var bound = 0
        for (shop in TeamRewardsData.shops) {
            val npc = TeamRewardsNpcs.byName.getValue(shop.npc)
            val ops = npcTypes[npc].op.map { it?.trim() }
            val op = ops.indexOfFirst { it.equals(shop.op, ignoreCase = true) } + 1
            if (op <= 0) {
                logger.warn { "Team rewards: ${shop.npc} has no '${shop.op}' op (${ops}); skipped." }
                continue
            }
            if (isNpcOpBound(npc, op)) {
                logger.warn { "Team rewards: ${shop.npc} op $op is already bound; skipped." }
                continue
            }
            bindOp(npc, op) { open(shop) }
            bound++
        }
        logger.info { "Team rewards: bound $bound free reward shops." }
    }

    private fun ScriptContext.bindOp(npc: NpcType, op: Int, action: ProtectedAccess.() -> Unit) {
        when (op) {
            1 -> onOpNpc1(npc) { action() }
            2 -> onOpNpc2(npc) { action() }
            3 -> onOpNpc3(npc) { action() }
            4 -> onOpNpc4(npc) { action() }
            5 -> onOpNpc5(npc) { action() }
        }
    }

    private fun ProtectedAccess.open(shop: TeamRewardsData.ShopDef) {
        shops.open(
            player = player,
            title = shop.title,
            shopInv = TeamRewardsInvs.byName.getValue(shop.key),
            buyPercentage = 0.0,
            sellPercentage = 0.0,
            changePercentage = 0.0,
            subtext = "Free (team minigame rewards)",
        )
    }
}

internal object TeamRewardsData {
    class ShopDef(val key: String, val npc: String, val op: String, val title: String) {
        val items = ArrayList<String>()
    }

    val shops: List<ShopDef> by lazy {
        val input =
            checkNotNull(TeamRewardsData::class.java.getResourceAsStream("rewards.tsv")) {
                "rewards.tsv not found"
            }
        val out = ArrayList<ShopDef>()
        for (line in input.bufferedReader().use { it.readLines() }) {
            val parts = line.split('\t')
            when (parts[0]) {
                "shop" -> out += ShopDef(parts[1], parts[2], parts[3], parts[4])
                "item" -> out.last().items += parts[1]
            }
        }
        out
    }

    fun invName(key: String): String = "minigame_rewards_$key"
}

internal object TeamRewardsInvs : InvReferences() {
    val byName: Map<String, InvType> =
        TeamRewardsData.shops.associate { it.key to find(TeamRewardsData.invName(it.key)) }
}

internal object TeamRewardsObjs : ObjReferences() {
    val byName: Map<String, ObjType> =
        TeamRewardsData.shops.flatMap { it.items }.distinct().associateWith { find(it) }
}

internal object TeamRewardsNpcs : NpcReferences() {
    val byName: Map<String, NpcType> =
        TeamRewardsData.shops.map { it.npc }.distinct().associateWith { find(it) }
}

/** Custom shop invs (ui range 65301..65307), one per minigame. */
internal object TeamRewardsInvBuilder : InvBuilder() {
    init {
        for (shop in TeamRewardsData.shops) {
            build(TeamRewardsData.invName(shop.key)) {
                scope = InvScope.Shared
                stack = InvStackType.Always
                size = shop.items.size.coerceAtLeast(1)
                restock = true
                allStock = false
                for (item in shop.items) {
                    stock.add(stock(TeamRewardsObjs.byName.getValue(item), count = STOCK, restockCycles = 1))
                }
            }
        }
    }

    private const val STOCK = 100
}
