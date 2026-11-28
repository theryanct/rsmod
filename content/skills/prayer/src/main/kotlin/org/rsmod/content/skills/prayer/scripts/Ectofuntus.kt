package org.rsmod.content.skills.prayer.scripts

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLocU
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.skills.prayer.configs.BoneXp
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The Ectofuntus (OSRS Wiki), north of Port Phasmatys:
 * - use bones on the bone grinder's loader (upstairs) with empty pots: each bone is loaded,
 *   ground and emptied into a pot as that bone's bonemeal, for every bone/pot pair carried;
 * - use buckets on the Pool of Slime (in the cellar under the trapdoor) for buckets of slime;
 * - worship the Ectofuntus with a bonemeal and a bucket of slime for 4x the bone's burying xp (the
 *   server Prayer rate applies in `statAdvance`), per pair carried; each worship earns 5
 *   ecto-tokens, collected from a ghost disciple while wearing a ghostspeak amulet.
 *
 * Server varp `ecto_tokens_owed` (65784) holds the uncollected tokens. Port Phasmatys's barrier,
 * trapdoor and stairs are in the quests module (content/quests/.../phasmatys/PortPhasmatys.kt).
 */
class Ectofuntus @Inject constructor(private val objTypes: ObjTypeList) : PluginScript() {
    override fun ScriptContext.startup() {
        for ((bone, _) in EctoObjs.meals) {
            onOpLocU(EctoLocs.loader, bone) { grindAll() }
        }
        onOpLoc1(EctoLocs.grinder_handle) { grindAll() }
        onOpLoc1(EctoLocs.grinder) { grindAll() }
        onOpLoc1(EctoLocs.bin) { grindAll() }
        for (pool in EctoLocs.pools) {
            onOpLocU(pool, EctoObjs.bucket) { fillSlime() }
        }
        onOpLoc1(EctoLocs.ectofuntus) { worship() }
        onOpNpc1(EctoNpcs.disciple) { collectTokens() }
    }

    /** Bones carried that have a bonemeal, in inventory order. */
    private fun ProtectedAccess.nextBone(): Pair<ObjType, ObjType>? =
        EctoObjs.meals.firstOrNull { (bone, _) -> invTotal(inv, bone) > 0 }

    private suspend fun ProtectedAccess.grindAll() {
        var made = 0
        while (true) {
            val (bone, meal) = nextBone() ?: break
            if (invTotal(inv, EctoObjs.pot) == 0) {
                if (made == 0) mes("You need an empty pot to collect the bonemeal.")
                break
            }
            invDel(inv, bone)
            mes("You put some ${objTypes[bone].name.lowercase()} in the hopper.")
            delay(GRIND_TICKS)
            mes("You wind the handle and the bones are ground into the bin.")
            delay(GRIND_TICKS)
            invDel(inv, EctoObjs.pot)
            invAdd(inv, meal)
            mes("You fill a pot with bonemeal.")
            made++
        }
        if (made == 0 && nextBone() == null) mes("You need some bones to grind.")
    }

    private suspend fun ProtectedAccess.fillSlime() {
        var filled = 0
        while (invTotal(inv, EctoObjs.bucket) > 0) {
            invDel(inv, EctoObjs.bucket)
            invAdd(inv, EctoObjs.slime)
            filled++
            delay(1)
        }
        if (filled > 0) mes("You fill the bucket${if (filled > 1) "s" else ""} with slime.")
    }

    private suspend fun ProtectedAccess.worship() {
        var worshipped = 0
        while (invTotal(inv, EctoObjs.slime) > 0) {
            val (bone, meal) =
                EctoObjs.meals.firstOrNull { (_, meal) -> invTotal(inv, meal) > 0 } ?: break
            val xp = BoneXp.buryXp(objTypes[bone]) ?: break
            invDel(inv, meal)
            invDel(inv, EctoObjs.slime)
            invAdd(inv, EctoObjs.pot)
            invAdd(inv, EctoObjs.bucket)
            statAdvance(stats.prayer, xp * WORSHIP_MULTIPLIER)
            worshipped++
            delay(WORSHIP_TICKS)
        }
        if (worshipped == 0) {
            mes("You need a bucket of slime and some bonemeal to worship the Ectofuntus.")
            return
        }
        val owed = player.vars[EctoVarps.tokens_owed] + worshipped * TOKENS_PER_WORSHIP
        VarPlayerIntMapSetter.set(player, EctoVarps.tokens_owed, owed)
        mes("You pray to the Ectofuntus. You can collect your ecto-tokens from a ghost disciple.")
    }

    private suspend fun ProtectedAccess.collectTokens() {
        val wearing =
            invTotal(worn, EctoObjs.ghostspeak) > 0 || invTotal(worn, EctoObjs.ghostspeak_e) > 0
        if (!wearing) {
            mes("The ghost disciple wails at you, but you can't understand a word.")
            return
        }
        val owed = player.vars[EctoVarps.tokens_owed]
        if (owed == 0) {
            mes("The ghost disciple says you have no ecto-tokens waiting.")
            return
        }
        invAdd(inv, EctoObjs.ecto_token, owed)
        VarPlayerIntMapSetter.set(player, EctoVarps.tokens_owed, 0)
        mes("The ghost disciple gives you $owed ecto-tokens.")
    }

    private companion object {
        const val WORSHIP_MULTIPLIER = 4.0
        const val TOKENS_PER_WORSHIP = 5
        const val GRIND_TICKS = 2
        const val WORSHIP_TICKS = 3
    }
}

internal object EctoLocs : LocReferences() {
    val loader = find("ahoy_grinder_loader")
    val grinder = find("ahoy_grinder")
    val grinder_handle = find("ahoy_grinder_handle")
    val bin = find("ahoy_grinder_chute")
    val pools: List<LocType> =
        listOf(
            find("ahoy_gloopbubbles"),
            find("ahoy_gloopbubbles_large"),
            find("ahoy_gloopbubbles_larger"),
            find("ahoy_new_green_floor"),
        )
    val ectofuntus = find("ahoy_ectofuntus")
}

internal object EctoNpcs : NpcReferences() {
    val disciple = find("ahoy_disciple")
}

internal object EctoObjs : ObjReferences() {
    val pot = find("pot_empty")
    val bucket = find("bucket_empty")
    val slime = find("bucket_ectoplasm")
    val ecto_token = find("ectotoken")
    val ghostspeak = find("amulet_of_ghostspeak")
    val ghostspeak_e = find("amulet_of_ghostspeak_enchanted")

    /** Bone -> its bonemeal. */
    val meals: List<Pair<ObjType, ObjType>> =
        listOf(
            find("bones") to find("pot_bonemeal"),
            find("bones_burnt") to find("pot_bonemeal_burnt"),
            find("wolf_bones") to find("pot_bonemeal_wolf"),
            find("bat_bones") to find("pot_bonemeal_bat"),
            find("big_bones") to find("pot_bonemeal_big"),
            find("tbwt_jogre_bones") to find("pot_bonemeal_jogre"),
            find("tbwt_burnt_jogre_bones") to find("pot_bonemeal_burnt_jogre"),
            find("zogre_bones") to find("pot_bonemeal_zogre"),
            find("babydragon_bones") to find("pot_bonemeal_babydragon"),
            find("babywyrm_bones") to find("pot_bonemeal_babywyrm"),
            find("wyrm_bones") to find("pot_bonemeal_wyrm"),
            find("dragon_bones") to find("pot_bonemeal_dragon"),
            find("wyvern_bones") to find("pot_bonemeal_wyvern"),
            find("drake_bones") to find("pot_bonemeal_drake"),
            find("lava_dragon_bones") to find("pot_bonemeal_lavadragon"),
            find("hydra_bones") to find("pot_bonemeal_hydra"),
            find("dragon_bones_superior") to find("pot_bonemeal_dragon_superior"),
            find("dagannoth_king_bones") to find("pot_bonemeal_dagannoth"),
        )
}

internal object EctoVarps : VarpReferences() {
    /** Server varp (65784): ecto-tokens earned but not yet collected. */
    val tokens_owed = find("ecto_tokens_owed")
}

internal object EctoVarpBuilds : VarpBuilder() {
    init {
        build("ecto_tokens_owed")
    }
}
