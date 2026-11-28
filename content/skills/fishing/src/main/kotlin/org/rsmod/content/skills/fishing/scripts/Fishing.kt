package org.rsmod.content.skills.fishing.scripts

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.synths
import org.rsmod.api.player.events.interact.NpcEvents
import org.rsmod.api.player.features.invAddGathered
import org.rsmod.api.player.features.invFullForGathering
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.righthand
import org.rsmod.api.player.stat.agilityLvl
import org.rsmod.api.player.stat.fishingLvl
import org.rsmod.api.player.stat.strengthLvl
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc2
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onOpNpc4
import org.rsmod.api.stats.levelmod.InvisibleLevels
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.content.skills.fishing.configs.fishing_objs
import org.rsmod.content.skills.fishing.configs.fishing_seqs
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.npc.UnpackedNpcType
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.seq.SeqType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

// Barbarian fishing (Use-rod: leaping trout/salmon/sturgeon, + Agility/Strength xp) and karambwan
// fishing (Fish at karambwan spots with a loaded vessel) use the same per-type op binding.
// Not yet: moving spots (skipped by decision), special members spots (anglerfish, dark crab,
// eels), big net junk items, angler outfit, clue bottles, heron pet, karambwanji net fishing.
// Barbarian Training (Otto) is skipped; the barbarian rod works without it.
class Fishing
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val objTypes: ObjTypeList,
    private val xpMods: XpModifiers,
    private val invisibleLvls: InvisibleLevels,
    private val mapClock: MapClock,
) : PluginScript() {
    private val logger = InlineLogger()

    override fun ScriptContext.startup() {
        // Every fishing spot is an npc named "Fishing spot"; each location has its own npc type.
        // The pair of ops a spot offers identifies what it yields (e.g. "Bait" gives sardine at a
        // Net/Bait sea spot but pike at a Lure/Bait river spot), so methods are resolved per type.
        var bound = 0
        for (type in npcTypes.values) {
            if (!type.name.equals("Fishing spot", ignoreCase = true)) {
                continue
            }
            // The Hemenster contest spots (0_41_53_*) belong to the Fishing Contest quest.
            if (type.internalName.orEmpty().startsWith(CONTEST_SPOT_PREFIX)) {
                continue
            }
            val ops = type.op.mapNotNull { it?.trim()?.lowercase() }.toSet()
            for ((index, text) in type.op.withIndex()) {
                val op = text?.trim()?.lowercase() ?: continue
                val method = FishingMethod.resolve(op, ops, type.internalName.orEmpty()) ?: continue
                registerOp(type, index + 1, method)
                bound++
            }
        }
        logger.info { "Fishing bound $bound spot ops." }
    }

    private fun ScriptContext.registerOp(type: UnpackedNpcType, op: Int, method: FishingMethod) {
        // Another script (a quest) may already own this spot's op; boot order must not matter.
        val event =
            when (op) {
                1 -> NpcEvents.Op1::class.java
                2 -> NpcEvents.Op2::class.java
                3 -> NpcEvents.Op3::class.java
                4 -> NpcEvents.Op4::class.java
                else -> return
            }
        if (eventBus.contains(event, type.id)) {
            return
        }
        when (op) {
            1 -> onOpNpc1(type) { fish(it.npc, op, method) }
            2 -> onOpNpc2(type) { fish(it.npc, op, method) }
            3 -> onOpNpc3(type) { fish(it.npc, op, method) }
            4 -> onOpNpc4(type) { fish(it.npc, op, method) }
            // ProtectedAccess has no opNpc5 to re-trigger the loop; no fishing op sits on op5.
        }
    }

    private fun ProtectedAccess.fish(spot: Npc, op: Int, method: FishingMethod) {
        if (!canFish(method)) {
            resetAnim()
            return
        }

        if (skillAnimDelay <= mapClock) {
            skillAnimDelay = mapClock + 5
            anim(method.anim)
        }

        if (actionDelay < mapClock) {
            // Fresh attempt, or the tick right after a roll. Rolls happen every 5 ticks.
            if (actionDelay < mapClock - 1) {
                spam(method.startMessage)
            }
            actionDelay = mapClock + 4
        } else if (actionDelay == mapClock) {
            rollCatch(method)
            if (!canFish(method)) {
                resetAnim()
                return
            }
        }

        reop(spot, op)
    }

    private fun ProtectedAccess.canFish(method: FishingMethod): Boolean {
        val lowest = method.catches.minOf { it.level }
        if (player.fishingLvl < lowest) {
            mes("You need a Fishing level of at least $lowest to ${method.verb} these fish.")
            return false
        }
        if (!hasTool(method)) {
            mes(method.missingToolMessage)
            return false
        }
        if (method.baits.isNotEmpty() && method.baits.none { it in inv }) {
            mes(method.missingBaitMessage)
            return false
        }
        val lowestBonus = method.catches.minOf { it.bonusLevel }
        if (lowestBonus > 0 && (player.agilityLvl < lowestBonus || player.strengthLvl < lowestBonus)) {
            mes("You need Agility and Strength levels of at least $lowestBonus to ${method.verb} these fish.")
            return false
        }
        if (invFullForGathering(objTypes, method.catches.map { it.fish })) {
            mes("You can't carry any more fish.")
            soundSynth(synths.pillory_wrong)
            return false
        }
        return true
    }

    private fun ProtectedAccess.hasTool(method: FishingMethod): Boolean {
        val wielded = player.righthand
        return method.tools.any { tool ->
            tool in inv || (wielded != null && objTypes[tool].id == wielded.id)
        }
    }

    private fun ProtectedAccess.rollCatch(method: FishingMethod) {
        // Catches cascade: the highest-level fish the player can catch is rolled first, then the
        // next one down, each with its own OSRS Wiki skilling-success curve.
        for (catch in method.catches) {
            if (player.fishingLvl < catch.level) {
                continue
            }
            if (player.agilityLvl < catch.bonusLevel || player.strengthLvl < catch.bonusLevel) {
                continue
            }
            if (!statRandom(stats.fishing, catch.low, catch.high, invisibleLvls)) {
                continue
            }
            // First bait in the list the player carries (e.g. feathers before fish offcuts).
            val bait = method.baits.firstOrNull { it in inv }
            if (bait != null) {
                invDel(inv, bait, count = 1)
            }
            val xp = catch.xp * xpMods.get(player, stats.fishing)
            spam(catch.message)
            statAdvance(stats.fishing, xp)
            if (catch.bonusXp > 0) {
                statAdvance(stats.agility, catch.bonusXp * xpMods.get(player, stats.agility))
                statAdvance(stats.strength, catch.bonusXp * xpMods.get(player, stats.strength))
            }
            invAddGathered(objTypes[catch.fish])
            return
        }
    }

    private fun ProtectedAccess.reop(spot: Npc, op: Int) {
        when (op) {
            1 -> opNpc1(spot)
            2 -> opNpc2(spot)
            3 -> opNpc3(spot)
            4 -> opNpc4(spot)
        }
    }

    private class Catch(
        val fish: ObjType,
        val level: Int,
        val xp: Double,
        val low: Int,
        val high: Int,
        val message: String,
        /** Barbarian fishing: Agility and Strength level needed, and xp given in each. */
        val bonusLevel: Int = 0,
        val bonusXp: Double = 0.0,
    )

    private enum class FishingMethod(
        val verb: String,
        val tools: List<ObjType>,
        /** Accepted baits in preference order (empty = none needed). */
        val baits: List<ObjType>,
        val anim: SeqType,
        val startMessage: String,
        val missingToolMessage: String,
        val missingBaitMessage: String,
        /** Highest level first: rolled in this order. */
        val catches: List<Catch>,
    ) {
        SmallNet(
            verb = "net",
            tools = listOf(fishing_objs.net),
            baits = emptyList(),
            anim = fishing_seqs.human_smallnet,
            startMessage = "You cast out your net...",
            missingToolMessage = "You need a small fishing net to catch these fish.",
            missingBaitMessage = "",
            catches =
                listOf(
                    Catch(fishing_objs.raw_anchovies, 15, 40.0, 24, 128, "You catch some anchovies."),
                    Catch(fishing_objs.raw_shrimps, 1, 10.0, 48, 256, "You catch some shrimps."),
                ),
        ),
        SeaBait(
            verb = "bait",
            tools = listOf(fishing_objs.fishing_rod),
            baits = listOf(fishing_objs.fishing_bait),
            anim = fishing_seqs.human_fishing_casting,
            startMessage = "You cast out your line...",
            missingToolMessage = "You need a fishing rod to catch these fish.",
            missingBaitMessage = "You don't have any bait left.",
            catches =
                listOf(
                    Catch(fishing_objs.raw_herring, 10, 30.0, 24, 128, "You catch a herring."),
                    Catch(fishing_objs.raw_sardine, 5, 20.0, 32, 192, "You catch a sardine."),
                ),
        ),
        RiverBait(
            verb = "bait",
            tools = listOf(fishing_objs.fishing_rod),
            baits = listOf(fishing_objs.fishing_bait),
            anim = fishing_seqs.human_fishing_casting,
            startMessage = "You cast out your line...",
            missingToolMessage = "You need a fishing rod to catch these fish.",
            missingBaitMessage = "You don't have any bait left.",
            catches = listOf(Catch(fishing_objs.raw_pike, 25, 60.0, 16, 96, "You catch a pike.")),
        ),
        Lure(
            verb = "lure",
            tools = listOf(fishing_objs.fly_fishing_rod),
            baits = listOf(fishing_objs.feather),
            anim = fishing_seqs.human_fishing_casting,
            startMessage = "You cast out your line...",
            missingToolMessage = "You need a fly fishing rod to catch these fish.",
            missingBaitMessage = "You don't have any feathers left.",
            catches =
                listOf(
                    Catch(fishing_objs.raw_salmon, 30, 70.0, 16, 96, "You catch a salmon."),
                    Catch(fishing_objs.raw_trout, 20, 50.0, 32, 192, "You catch a trout."),
                ),
        ),
        Cage(
            verb = "cage",
            tools = listOf(fishing_objs.lobster_pot),
            baits = emptyList(),
            anim = fishing_seqs.human_lobster,
            startMessage = "You attempt to catch a lobster.",
            missingToolMessage = "You need a lobster pot to catch lobsters.",
            missingBaitMessage = "",
            catches = listOf(Catch(fishing_objs.raw_lobster, 40, 90.0, 6, 95, "You catch a lobster.")),
        ),
        Harpoon(
            verb = "harpoon",
            tools = harpoons,
            baits = emptyList(),
            anim = fishing_seqs.human_harpoon,
            startMessage = "You start harpooning fish.",
            missingToolMessage = "You need a harpoon to catch these fish.",
            missingBaitMessage = "",
            catches =
                listOf(
                    Catch(fishing_objs.raw_swordfish, 50, 100.0, 4, 48, "You catch a swordfish."),
                    Catch(fishing_objs.raw_tuna, 35, 80.0, 8, 64, "You catch a tuna."),
                ),
        ),
        SharkHarpoon(
            verb = "harpoon",
            tools = harpoons,
            baits = emptyList(),
            anim = fishing_seqs.human_harpoon,
            startMessage = "You start harpooning fish.",
            missingToolMessage = "You need a harpoon to catch these fish.",
            missingBaitMessage = "",
            catches = listOf(Catch(fishing_objs.raw_shark, 76, 110.0, 3, 40, "You catch a shark!")),
        ),
        BigNet(
            verb = "net",
            tools = listOf(fishing_objs.big_net),
            baits = emptyList(),
            anim = fishing_seqs.human_largenet,
            startMessage = "You cast out your net...",
            missingToolMessage = "You need a big fishing net to catch these fish.",
            missingBaitMessage = "",
            catches =
                listOf(
                    Catch(fishing_objs.raw_bass, 46, 100.0, 3, 40, "You catch a bass."),
                    Catch(fishing_objs.raw_cod, 23, 45.0, 4, 55, "You catch a cod."),
                    Catch(fishing_objs.raw_mackerel, 16, 20.0, 5, 65, "You catch a mackerel."),
                ),
        ),
        MonkfishNet(
            verb = "net",
            tools = listOf(fishing_objs.net),
            baits = emptyList(),
            anim = fishing_seqs.human_smallnet,
            startMessage = "You cast out your net...",
            missingToolMessage = "You need a small fishing net to catch these fish.",
            missingBaitMessage = "",
            catches =
                listOf(Catch(fishing_objs.raw_monkfish, 62, 120.0, 48, 90, "You catch a monkfish.")),
        ),
        /** OSRS Wiki Barbarian Fishing: one cascaded chart, Agility/Strength reqs and xp. */
        BarbarianRod(
            verb = "fish",
            tools = listOf(fishing_objs.barbarian_rod),
            baits = listOf(fishing_objs.feather, fishing_objs.fish_offcuts, fishing_objs.roe, fishing_objs.caviar),
            anim = fishing_seqs.human_fishing_casting,
            startMessage = "You cast out your line...",
            missingToolMessage = "You need a barbarian rod to catch these fish.",
            missingBaitMessage = "You need feathers or fish offcuts to use as bait.",
            catches =
                listOf(
                    Catch(fishing_objs.leaping_sturgeon, 70, 80.0, 8, 64, "You catch a leaping sturgeon.", 45, 7.0),
                    Catch(fishing_objs.leaping_salmon, 58, 70.0, 16, 96, "You catch a leaping salmon.", 30, 6.0),
                    Catch(fishing_objs.leaping_trout, 48, 50.0, 32, 192, "You catch a leaping trout.", 15, 5.0),
                ),
        ),
        /** OSRS Wiki Raw karambwan: level 65, 50 xp, chart 5/160. */
        Karambwan(
            verb = "fish",
            tools = listOf(fishing_objs.karambwan_vessel_loaded),
            baits = listOf(fishing_objs.raw_karambwanji),
            anim = fishing_seqs.human_lobster,
            startMessage = "You start fishing for karambwan.",
            missingToolMessage = "You need a karambwan vessel loaded with karambwanji to catch karambwan.",
            missingBaitMessage = "You need raw karambwanji to bait your vessel.",
            catches = listOf(Catch(fishing_objs.raw_karambwan, 65, 50.0, 5, 160, "You catch a karambwan.")),
        );

        companion object {
            /**
             * Resolves a spot op into a method using the spot's full op set (OSRS spot kinds:
             * Net/Bait, Lure/Bait, Cage/Harpoon, Big Net/Harpoon, Net/Harpoon (Piscatorius)), plus
             * Use-rod (barbarian) and Fish on karambwan spots (by internal name; other "Fish"
             * spots such as Sailing's are left alone).
             */
            fun resolve(op: String, spotOps: Set<String>, internalName: String): FishingMethod? =
                when (op) {
                    "use-rod" -> BarbarianRod
                    "fish" -> if (internalName.contains("karambwan")) Karambwan else null
                    "net",
                    "small net" -> if ("harpoon" in spotOps) MonkfishNet else SmallNet
                    "bait" -> if ("lure" in spotOps) RiverBait else SeaBait
                    "lure" -> Lure
                    "cage" -> Cage
                    "harpoon" -> if ("big net" in spotOps) SharkHarpoon else Harpoon
                    "big net" -> BigNet
                    else -> null
                }
        }
    }

    private companion object {
        /** Fishing Contest's Hemenster spots (map square 41_53), owned by the quest. */
        const val CONTEST_SPOT_PREFIX = "0_41_53_"

        val harpoons: List<ObjType>
            get() =
                listOf(
                    fishing_objs.harpoon,
                    fishing_objs.dragon_harpoon,
                    fishing_objs.infernal_harpoon,
                    fishing_objs.crystal_harpoon,
                )
    }
}
