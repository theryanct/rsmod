package org.rsmod.content.other.pets

import jakarta.inject.Inject
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.player.events.CollectionLogObtained
import org.rsmod.api.player.events.StatAdvanced
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.statBase
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.script.isHeldOpBound
import org.rsmod.api.script.isNpcOpBound
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpHeld5
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc2
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.script.onOpNpc4
import org.rsmod.api.script.onOpNpc5
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.api.script.onPlayerQueueWithArgs
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.queue.QueueReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.stat.StatType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Pets (OSRS Wiki "Pet"; data in `pets.tsv` from tools/pets/pets.py):
 * - Followers: "Drop" a pet item to let it out (one at a time); it follows its owner (the npc
 *   PlayerFollow mode, which also teleports it along). "Pick-up" puts it back in the inventory.
 *   The active pet is saved and comes back at login; it leaves at logout.
 * - Skilling pets roll on every xp gain in their skill ([StatAdvanced]): 1/(base - 25 x level)
 *   per typical action (base level, capped at 99), scaled by the xp of the gain.
 * - Boss pets roll on the hero's kill of the boss ([NpcKilledEvent]) at the Wiki rate.
 * - Getting one: no pet out -> "You have a funny feeling like you're being followed." (it
 *   follows); else into the inventory ("...something weird sneaking into your backpack."); a pet
 *   you already own (out, carried or banked) gives "...you would have been followed..." instead.
 *   Lost pets can simply be obtained again (no insurance). Every pet obtained is recorded in the
 *   collection log ([CollectionLogObtained]).
 * Not included: metamorphosis, Probita, the 200M xp bonus, per-activity skilling rates.
 */
class Pets
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val npcRepo: NpcRepository,
    private val random: GameRandom,
    private val eventBus: EventBus,
) : PluginScript() {
    /** Active follower per player uid. */
    private val followers = HashMap<Int, Npc>()

    private val petsByItem by lazy { PetData.pets.associateBy { PetObjs.byName.getValue(it.item).id } }
    private val petsByFollower by lazy { PetData.pets.associateBy { PetNpcs.byName.getValue(it.npc).id } }
    private val bossPets by lazy {
        PetData.pets.filter { it.boss }.flatMap { pet -> pet.bosses.map { PetNpcs.byName.getValue(it).id to pet } }.toMap()
    }

    override fun ScriptContext.startup() {
        for (pet in PetData.pets) {
            val item = PetObjs.byName.getValue(pet.item)
            if (!isHeldOpBound(item, 5)) onOpHeld5(item) { letOut(pet, item) }
            val follower = PetNpcs.byName.getValue(pet.npc)
            val op = npcTypes[follower].op.indexOfFirst { it.equals("Pick-up", ignoreCase = true) } + 1
            if (op > 0 && !isNpcOpBound(follower, op)) {
                when (op) {
                    1 -> onOpNpc1(follower) { pickUp(it.npc) }
                    2 -> onOpNpc2(follower) { pickUp(it.npc) }
                    3 -> onOpNpc3(follower) { pickUp(it.npc) }
                    4 -> onOpNpc4(follower) { pickUp(it.npc) }
                    5 -> onOpNpc5(follower) { pickUp(it.npc) }
                }
            }
        }
        onEvent<StatAdvanced> { rollSkillPet(player, stat, xp) }
        onEvent<NpcKilledEvent> { bossPets[npc.id]?.let { roll(hero, it, it.rate.toDouble()) } }
        onPlayerQueueWithArgs<String>(PetQueues.award) { award(it.args) }
        onPlayerLogin { restoreFollower(player) }
        onPlayerLogout { removeFollower(player) }
    }

    /* Rolls */

    private fun rollSkillPet(player: Player, stat: StatType, xp: Double) {
        val pet = PetData.skillPets[stat.internalName ?: return] ?: return
        val level = minOf(player.statBase(stat), MAX_LEVEL)
        val perAction = (pet.base - SKILL_LEVEL_FACTOR * level).coerceAtLeast(1)
        // A gain of `xp` counts as xp / actionXp typical actions.
        val odds = perAction * pet.actionXp / xp
        roll(player, pet, odds)
    }

    private fun roll(player: Player, pet: PetData.Pet, odds: Double) {
        val chance = if (odds <= 1.0) 0 else random.of(odds.toInt().coerceAtLeast(1))
        if (chance != 0) return
        player.queue(PetQueues.award, 1, pet.key)
    }

    /* Awarding */

    private fun ProtectedAccess.award(key: String) {
        val pet = PetData.byKey[key] ?: return
        val item = PetObjs.byName.getValue(pet.item)
        markObtained(player, pet)
        eventBus.publish(CollectionLogObtained(player, item.id, 1))
        if (owns(pet, item)) {
            mes("You have a funny feeling like you would have been followed...")
            return
        }
        if (followers[player.uid.packed] == null) {
            spawnFollower(player, pet)
            setActive(player, pet)
            mes("You have a funny feeling like you're being followed.")
        } else if (!inv.isFull()) {
            invAdd(inv, item)
            mes("You feel something weird sneaking into your backpack.")
        } else {
            mes("You have a funny feeling like you would have been followed...")
        }
    }

    private fun ProtectedAccess.owns(pet: PetData.Pet, item: ObjType): Boolean {
        val out = followers[player.uid.packed]?.let { petsByFollower[it.id] } == pet
        return out || invTotal(inv, item) > 0 || invTotal(bank, item) > 0 || item in player.worn
    }

    private fun markObtained(player: Player, pet: PetData.Pet) {
        val index = PetData.pets.indexOf(pet)
        val varp = PetVarps.obtained[index / 31]
        val bit = 1 shl (index % 31)
        VarPlayerIntMapSetter.set(player, varp, player.vars[varp] or bit)
    }

    /* Followers */

    private fun ProtectedAccess.letOut(pet: PetData.Pet, item: ObjType) {
        if (followers[player.uid.packed] != null) {
            mes("You already have a follower.")
            return
        }
        invDel(inv, item, 1)
        spawnFollower(player, pet)
        setActive(player, pet)
    }

    private fun ProtectedAccess.pickUp(npc: Npc) {
        val owned = followers[player.uid.packed]
        if (owned !== npc) {
            mes("This is not your pet.")
            return
        }
        val pet = petsByFollower[npc.id] ?: return
        if (inv.isFull()) {
            mes("You don't have enough inventory space.")
            return
        }
        removeFollower(player)
        setActive(player, null)
        invAdd(inv, PetObjs.byName.getValue(pet.item))
        mes("You pick up your pet.")
    }

    private fun spawnFollower(player: Player, pet: PetData.Pet) {
        val type = npcTypes[PetNpcs.byName.getValue(pet.npc)]
        val npc = Npc(type, player.coords)
        npcRepo.add(npc, FOLLOWER_LIFETIME)
        npc.resetMovement()
        npc.mode = NpcMode.PlayerFollow
        npc.facePlayer(player)
        followers[player.uid.packed] = npc
    }

    private fun removeFollower(player: Player) {
        val npc = followers.remove(player.uid.packed) ?: return
        npcRepo.del(npc, Int.MAX_VALUE)
    }

    private fun restoreFollower(player: Player) {
        val index = player.vars[PetVarps.active] - 1
        val pet = PetData.pets.getOrNull(index) ?: return
        if (followers[player.uid.packed] == null) spawnFollower(player, pet)
    }

    private fun setActive(player: Player, pet: PetData.Pet?) {
        val value = if (pet == null) 0 else PetData.pets.indexOf(pet) + 1
        VarPlayerIntMapSetter.set(player, PetVarps.active, value)
    }

    private companion object {
        const val MAX_LEVEL = 99
        const val SKILL_LEVEL_FACTOR = 25
        /** Not Int.MAX_VALUE: that marks an npc as a respawning map spawn. */
        const val FOLLOWER_LIFETIME = Int.MAX_VALUE - 1
    }
}

internal object PetData {
    class Pet(
        val key: String,
        val item: String,
        val npc: String,
        val boss: Boolean,
        val stat: String?,
        val base: Int,
        val actionXp: Double,
        val rate: Int,
        val bosses: List<String>,
    )

    /** pets.tsv order is the saved index (active pet, obtained bits): append only. */
    val pets: List<Pet> by lazy {
        val input =
            PetData::class.java.getResourceAsStream("pets.tsv") ?: error("Pets resource not found: pets.tsv")
        input.bufferedReader().use { it.readLines() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .map { p ->
                if (p[0] == "skill") {
                    Pet(p[1], p[2], p[3], false, p[4], p[5].toInt(), p[6].toDouble(), 0, emptyList())
                } else {
                    Pet(p[1], p[2], p[3], true, null, 0, 0.0, p[4].toInt(), p[5].split(','))
                }
            }
    }

    val byKey: Map<String, Pet> by lazy { pets.associateBy { it.key } }

    /** Skilling pet by stat internal name (e.g. "fishing"). */
    val skillPets: Map<String, Pet> by lazy { pets.filter { !it.boss }.associateBy { it.stat!! } }
}

internal object PetObjs : ObjReferences() {
    val byName: Map<String, ObjType> = PetData.pets.map { it.item }.distinct().associateWith { find(it) }
}

internal object PetNpcs : NpcReferences() {
    val byName: Map<String, NpcType> =
        PetData.pets.flatMap { listOf(it.npc) + it.bosses }.distinct().associateWith { find(it) }
}

internal object PetQueues : QueueReferences() {
    /** Custom queue (65505, skilling range): award a pet outside the xp/kill event. */
    val award = find("pets_award")
}

/** Saved per character (skilling symbol range 65674-65676). */
internal object PetVarpBuilds : VarpBuilder() {
    init {
        build("pets_active")
        build("pets_obtained_1")
        build("pets_obtained_2")
    }
}

internal object PetVarps : VarpReferences() {
    /** Index + 1 in [PetData.pets] of the pet following the player (0 = none). */
    val active = find("pets_active")
    /** 31 obtained flags per varp, in [PetData.pets] order. */
    val obtained = listOf(find("pets_obtained_1"), find("pets_obtained_2"))
}
