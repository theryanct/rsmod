package org.rsmod.content.quests.fremenniktrials

import org.rsmod.api.config.refs.params
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onOpLocU
import org.rsmod.content.interfaces.bank.BankTab
import org.rsmod.content.interfaces.bank.bankCapacity
import org.rsmod.content.quests.framework.QuestDialogueRunner
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.onHeldOpLabel
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.obj.ObjType
import org.rsmod.objtx.TransactionResult
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Peer the Seer's trial (Wiki): enter his house (south-west of the market) carrying and wearing
 * nothing - his spell banks everything - by the front door's riddle lock, and leave by the back
 * door. Upstairs: a bucket (5) in the cupboard, a jug (3) in the east chest, a wooden and an old red
 * disk in the bull's and unicorn's eyes, a red herring behind the bookcase; 4 litres on the
 * scale-locked chest give the vase; the herring on the range gives the goop that paints the wooden
 * disk; both red disks in the mural below give the lid; the sealed vase of water on the frozen table
 * gives the frozen key, which the range thaws. Leaving by the front door forfeits what you found.
 *
 * Simplified: the riddle's answer is typed (OSRS turns four letter wheels); when a bucket and a
 * jug are used on each other the water goes from a full bucket or into an empty jug first, else
 * from the jug into the bucket (both orders are one event here); the closed chests, cupboard and
 * trapdoor are searched/opened in one click; the magnet/pick/ship/thread are decoys only.
 */
internal class FtPeer(private val ft: FtContext) {
    private val speakers by lazy { mapOf("Peer the Seer" to FtNpcs.peer) }

    fun ScriptContext.bind() {
        ft.bound("seers_door_front Open") { onLocOpLabel(ft.locTypes, FtLocs.seers_door_front, "Open") { frontDoor(it) } }
        ft.bound("seers_door_back Open") { onLocOpLabel(ft.locTypes, FtLocs.seers_door_back, "Open") { backDoor(it) } }
        ft.bound("seer_up_ladder Climb-up") { onLocOpLabel(ft.locTypes, FtLocs.seer_up_ladder, "Climb-up") { upstairs(it.coords.x) } }
        ft.bound("seer_down_ladder Climb-up") { onLocOpLabel(ft.locTypes, FtLocs.seer_down_ladder, "Climb-up") { upstairs(it.coords.x) } }
        ft.bound("trapdoor_open Climb-down") { onLocOpLabel(ft.locTypes, FtLocs.trapdoor_open, "Climb-down") { downstairs(it.coords.x) } }
        ft.bound("trapdoor_closed Open") { onLocOpLabel(ft.locTypes, FtLocs.trapdoor_closed, "Open") { ft.locRepo.change(it, FtLocs.trapdoor_open, TRAPDOOR_TICKS) } }

        // Searching the room.
        ft.bound("chest Open") { onLocOpLabel(ft.locTypes, FtLocs.chest, "Open") { search(it, "chest") } }
        ft.bound("cupboard Open") { onLocOpLabel(ft.locTypes, FtLocs.cupboard, "Open") { search(it, "cupboard") } }
        ft.bound("bookcase Search") { onLocOpLabel(ft.locTypes, FtLocs.bookcase, "Search") { search(it, "bookcase") } }
        ft.bound("boxes Search") { onLocOpLabel(ft.locTypes, FtLocs.boxes, "Search") { search(it, "boxes") } }
        ft.bound("boxes_east Search") { onLocOpLabel(ft.locTypes, FtLocs.boxes_east, "Search") { search(it, "boxes") } }
        ft.bound("crate Search") { onLocOpLabel(ft.locTypes, FtLocs.crate, "Search") { search(it, "boxes") } }
        ft.bound("crate_old Search") { onLocOpLabel(ft.locTypes, FtLocs.crate_old, "Search") { search(it, "boxes") } }
        ft.bound("bull_head Study") { onLocOpLabel(ft.locTypes, FtLocs.bull_head, "Study") { studyHead(bull = true) } }
        ft.bound("unicorn_head Study") { onLocOpLabel(ft.locTypes, FtLocs.unicorn_head, "Study") { studyHead(bull = false) } }
        onLocOpLabel(ft.locTypes, FtLocs.chest_scales, "Open") {
            mesbox("This chest is securely locked shut. There is some kind of balance attached to the lock, and a number four is painted just above it.")
        }

        // Water.
        for ((litres, bucket) in FtObjs.buckets.withIndex()) {
            onOpLocU(FtLocs.tap, bucket) { fill(bucket, FtObjs.buckets.last(), "bucket") }
            if (litres > 0) onOpLocU(FtLocs.drain, bucket) { drain(bucket, FtObjs.buckets[0], "bucket") }
            onOpLocU(FtLocs.chest_scales, bucket) { weigh(litres) }
            onOpLocU(FtLocs.frozen_table, bucket) { chill(litres > 0, bucket, FtObjs.bucket_frozen, "bucket") }
            for ((jugLitres, jug) in FtObjs.jugs.withIndex()) {
                onOpHeldU(bucket, jug) { pour(litres, jugLitres) }
            }
        }
        for ((litres, jug) in FtObjs.jugs.withIndex()) {
            onOpLocU(FtLocs.tap, jug) { fill(jug, FtObjs.jugs.last(), "jug") }
            if (litres > 0) onOpLocU(FtLocs.drain, jug) { drain(jug, FtObjs.jugs[0], "jug") }
            onOpLocU(FtLocs.chest_scales, jug) { weigh(litres, jug = true) }
            onOpLocU(FtLocs.frozen_table, jug) { chill(litres > 0, jug, FtObjs.jug_frozen, "jug") }
        }
        onOpLocU(FtLocs.range, FtObjs.bucket_frozen) { thaw(FtObjs.bucket_frozen, FtObjs.buckets.last()) }
        onOpLocU(FtLocs.range, FtObjs.jug_frozen) { thaw(FtObjs.jug_frozen, FtObjs.jugs.last()) }

        // The vase.
        onOpLocU(FtLocs.tap, FtObjs.vase) {
            invReplace(inv, FtObjs.vase, 1, FtObjs.vase_water)
            mes("You fill the strange looking vase with water.")
        }
        onOpLocU(FtLocs.drain, FtObjs.vase_water) {
            invReplace(inv, FtObjs.vase_water, 1, FtObjs.vase)
            mes("You empty the strange looking vase of water.")
        }
        onOpHeldU(FtObjs.vase_lid, FtObjs.vase) { seal(FtObjs.vase, FtObjs.vase_sealed) }
        onOpHeldU(FtObjs.vase_lid, FtObjs.vase_water) { seal(FtObjs.vase_water, FtObjs.vase_sealed_water) }
        onOpHeldU(FtObjs.vase_lid, FtObjs.vase_frozen) { mes("Nothing interesting happens.") }
        for ((sealed, open) in
            listOf(
                FtObjs.vase_sealed to FtObjs.vase,
                FtObjs.vase_sealed_water to FtObjs.vase_water,
                FtObjs.vase_sealed_frozen to FtObjs.vase_frozen,
            )) {
            onHeldOpLabel(ft.objTypes, sealed, "Remove-lid") {
                invReplace(inv, sealed, 1, open)
                invAddOrDrop(ft.objRepo, FtObjs.vase_lid)
            }
        }
        onHeldOpLabel(ft.objTypes, FtObjs.vase, "Shake") {
            mesbox("You shake the strangely shaped vase. From the sound of it there is something metallic inside, but the neck of the vase is too narrow for it to come out.")
        }
        onHeldOpLabel(ft.objTypes, FtObjs.vase_water, "Shake") {
            mesbox("You shake the strangely shaped vase. The water inside it sloshes around a little. Some spills out of the neck of the vase.")
        }
        onHeldOpLabel(ft.objTypes, FtObjs.vase_frozen, "Shake") {
            mesbox("You shake the strangely shaped Vase. Whatever is at the bottom of it is packed with ice and does not rattle.")
        }
        onOpLocU(FtLocs.frozen_table, FtObjs.vase_sealed_water) {
            invDel(inv, FtObjs.vase_sealed_water)
            invAdd(inv, FtObjs.frozen_key)
            mes("The water expands as it freezes, and shatters the vase.")
            mes("You are left with a key encased in ice.")
        }
        onOpLocU(FtLocs.frozen_table, FtObjs.vase_water) {
            invReplace(inv, FtObjs.vase_water, 1, FtObjs.vase_frozen)
            mes("The icy table immediately freezes the water in your vase.") // Authored (Wiki: jug/bucket line).
        }
        onOpLocU(FtLocs.range, FtObjs.vase_frozen) { thaw(FtObjs.vase_frozen, FtObjs.vase_water) }
        onOpLocU(FtLocs.range, FtObjs.frozen_key) {
            invReplace(inv, FtObjs.frozen_key, 1, FtObjs.seers_key)
            mes("The heat of the range melts the ice around the key.")
        }

        // The disks and the mural.
        onOpLocU(FtLocs.range, FtObjs.red_herring) {
            mesbox("As you cook the herring on the stove, the colouring on it peels off separately as a red sticky goop...")
            invReplace(inv, FtObjs.red_herring, 1, FtObjs.herring)
            invAddOrDrop(ft.objRepo, FtObjs.red_goop)
        }
        onOpHeldU(FtObjs.red_goop, FtObjs.wooden_disk) {
            invDel(inv, FtObjs.red_goop)
            invReplace(inv, FtObjs.wooden_disk, 1, FtObjs.red_disk)
            mes("You coat the wooden coin with the sticky red goop.")
        }
        ft.bound("mural Study") { onLocOpLabel(ft.locTypes, FtLocs.mural, "Study") { studyMural() } }
        ft.bound("mural_done Study") { onLocOpLabel(ft.locTypes, FtLocs.mural_done, "Study") { studyMural() } }
        onOpLocU(FtLocs.mural, FtObjs.old_red_disk) { placeDisk(FtObjs.old_red_disk, OLD_DISK_PLACED) }
        onOpLocU(FtLocs.mural, FtObjs.red_disk) { placeDisk(FtObjs.red_disk, RED_DISK_PLACED) }
        onOpLocU(FtLocs.mural, FtObjs.wooden_disk) {
            mes("The wooden disk doesn't match the colour of the mural.") // Authored (not Wiki).
        }

        // Red herrings (Wiki "'Red herring' interactions").
        onOpLocU(FtLocs.chest_scales, FtObjs.magnet) { mes("You use the magnet on the chest. The scale wobbles a bit.") }
        onOpLocU(FtLocs.chest_scales, FtObjs.small_pick) {
            mes("You might be able to break the chest open using this pick...")
            mes("...if you stood here tapping at it for twenty years or so.")
        }
        onOpHeldU(FtObjs.small_pick, FtObjs.vase) { mes("This pick wouldn't be strong enough to break the vase open.") }
        onOpHeldU(FtObjs.toy_ship, FtObjs.vase) { mes("It wouldn't fit in there!") }
        onOpHeldU(FtObjs.magnet, FtObjs.vase) {
            mes("You use the magnet on the vase. The metallic object inside moves.")
            mes("The neck of the vase is too thin for the object to come out of the vase.")
        }
    }

    /* Peer */

    suspend fun ProtectedAccess.talk(npc: Npc) {
        when {
            !player.accepted(Trial.Peer) -> {
                // "Yes" twice: accept the trial, then accept his deposit spell.
                var yes = 0
                runQuestSection(npc, ft.dialogue, "Peer the Seer's Trial", SimpleQuestHooks(options = mapOf("Yes" to { yes++ })))
                if (yes >= 1) accept()
                if (yes >= 2) spell()
            }
            player.voted(Trial.Peer) -> runQuestSection(npc, ft.dialogue, "Talking to Peer the Seer again (4)")
            else -> {
                val carrying = !inv.isEmpty() || !worn.isEmpty()
                var deposit = false
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player has items in their inventory and/or wearing any armour:" to { carrying },
                                "If the player does not have any items in their inventory or in their worn slots:" to { !carrying },
                            ),
                        options = mapOf("Yes" to { deposit = true }),
                    )
                runQuestSection(npc, ft.dialogue, "Talking to Peer the Seer again (3)", hooks)
                if (deposit) spell()
            }
        }
    }

    /** "Ask about depositing your equipment" (Thorvald's trial sends you to him). */
    suspend fun ProtectedAccess.depositOffer(npc: Npc) {
        val ok = canDeposit()
        var deposit = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player's bank is full or if the player has unbankable items (e.g. a Lamp or experience tome):" to { !ok },
                        "If the player's bank is not full:" to { ok },
                    ),
                options = mapOf("Bank your equipment" to { deposit = true }),
            )
        with(ft) { branch(npc, "Peer the Seer", "Ask about depositing your equipment", hooks) }
        if (deposit && ok) spell()
    }

    /** Runs the spell after the dialogue has closed (unequipping is an interface action). */
    private fun ProtectedAccess.spell() {
        if (!depositAll()) {
            mes("Peer's spell fails to send some of your belongings to the bank.") // Authored (not Wiki).
        }
    }

    private fun ProtectedAccess.canDeposit(): Boolean =
        (inv.asSequence() + worn.asSequence()).filterNotNull().none { ft.objTypes[it].param(params.no_bank) != 0 }

    private fun ProtectedAccess.accept() {
        player.accept(Trial.Peer)
        player.ftSetField(FtVarps.peer, 0, 3, ft.random.of(1, RIDDLES.size))
    }

    /**
     * Peer's spell: everything carried and worn goes to the bank's main tab (as the bank's own
     * deposit-all does). Fails, banking nothing, if anything is unbankable or the bank is full.
     */
    private fun ProtectedAccess.depositAll(): Boolean {
        if (!canDeposit()) return false
        if (!depositInventory()) return false
        for (slot in worn.indices) {
            if (worn[slot] == null) continue
            wornUnequip(slot)
        }
        return depositInventory()
    }

    private fun ProtectedAccess.depositInventory(): Boolean {
        if (inv.isEmpty()) return true
        val before = bank.lastOccupiedSlot()
        val transaction =
            invMoveInv(
                from = inv,
                into = bank,
                untransform = true,
                intoStartSlot = BankTab.Main.firstSlot(this),
                intoCapacity = bankCapacity,
            )
        if (transaction[0] == TransactionResult.NotEnoughSpace) return false
        val grown = bank.lastOccupiedSlot() - before
        if (grown > 0) BankTab.Main.increaseSize(this, grown)
        return inv.isEmpty()
    }

    /* The doors and the riddle */

    private suspend fun ProtectedAccess.frontDoor(door: BoundLocInfo) {
        val inside = player.coords.z < door.coords.z
        if (inside) {
            leaveByFront(door)
            return
        }
        when {
            player.voted(Trial.Peer) -> passThroughWall(door)
            !player.onTrial(Trial.Peer) -> mes("This door is locked tightly shut.")
            !inv.isEmpty() || !worn.isEmpty() ->
                // Authored line (not Wiki): Peer's rule, nothing may come in.
                mes("You may not take anything into Peer's house. He can send your belongings to your bank.")
            player.ftHas(FtVarps.peer, RIDDLE_SOLVED) -> passThroughWall(door)
            else -> riddle(door)
        }
    }

    private suspend fun ProtectedAccess.riddle(door: BoundLocInfo) {
        var choice = 0
        startDialogue {
            mesbox("There is a combination lock on this door. Above the lock you can see that there is a metal plaque with a riddle on it.")
            choice = choice3("Read the riddle", 1, "Solve the riddle", 2, "Forget it", 3, title = "What would you like to do?")
        }
        val riddle = player.ftField(FtVarps.peer, 0, 3).coerceIn(1, RIDDLES.size)
        when (choice) {
            1 -> {
                val nodes = ft.dialogue.branch("The Riddle", "Riddle $riddle")
                startDialogue { QuestDialogueRunner(SimpleQuestHooks()).run(this, nodes) }
            }
            2 -> {
                val answer = stringDialog("Enter the four letter answer:")
                if (answer.trim().equals(RIDDLES[riddle - 1], ignoreCase = true)) {
                    mes("You have solved the riddle!")
                    player.ftSet(FtVarps.peer, RIDDLE_SOLVED)
                    passThroughWall(door)
                } else {
                    mes("You have failed to solve the riddle.")
                }
            }
            else -> mes("You leave the riddle for later.")
        }
    }

    private suspend fun ProtectedAccess.leaveByFront(door: BoundLocInfo) {
        if (!player.onTrial(Trial.Peer)) {
            passThroughWall(door)
            return
        }
        var leave = false
        startDialogue {
            mesbox("If you leave by this door you will fail this trial. Press continue to go through the door anyway.")
            // Authored menu (OSRS: continue, or click away to stay).
            leave = choice2("Go through the door anyway.", true, "Stay in the house.", false)
        }
        if (!leave) return
        clearPuzzle()
        passThroughWall(door)
    }

    private suspend fun ProtectedAccess.backDoor(door: BoundLocInfo) {
        val inside = player.coords.z < door.coords.z
        when {
            player.voted(Trial.Peer) -> passThroughWall(door)
            !inside || !carries(FtObjs.seers_key) -> mes("This door is locked tightly shut.")
            else -> {
                passThroughWall(door)
                clearPuzzle()
                runQuestSection(null, ft.dialogue, "Unlocking the door", speakers = speakers)
                player.giveVote(Trial.Peer)
                mes("Congratulations! You have completed the Seer's trial!") // Authored (not Wiki).
            }
        }
    }

    /** Takes every puzzle item back and resets the room (the riddle stays solved). */
    private fun ProtectedAccess.clearPuzzle() {
        for (obj in PUZZLE_OBJS) {
            val count = invTotal(inv, obj)
            if (count > 0) invDel(inv, obj, count)
        }
        val solved = player.ftHas(FtVarps.peer, RIDDLE_SOLVED)
        player.ftSetField(FtVarps.peer, 3, 29, 0)
        if (solved) player.ftSet(FtVarps.peer, RIDDLE_SOLVED)
    }

    /* Ladders and trapdoors (the upper floor's map level is resolved from the loc) */

    private suspend fun ProtectedAccess.upstairs(x: Int) {
        val level = ft.levelOf(WEST_X, HATCH_Z, FtLocs.trapdoor_open, 1..3)
        delay(1)
        telejump(ft.tile(x, HATCH_Z + 1, level))
    }

    private suspend fun ProtectedAccess.downstairs(x: Int) {
        delay(1)
        telejump(ft.tile(x, HATCH_Z + 1, 0))
    }

    /* Searching */

    private suspend fun ProtectedAccess.search(loc: BoundLocInfo, what: String) {
        val spot = SPOTS.firstOrNull { it.x == loc.coords.x && it.z == loc.coords.z }
        mes("You search the $what...")
        if (spot == null || !player.onTrial(Trial.Peer) || player.ftHas(FtVarps.peer, spot.bit) || inv.isFull()) {
            mes("You find nothing of interest.")
            return
        }
        val obj = spot.obj()
        player.ftSet(FtVarps.peer, spot.bit)
        invAdd(inv, obj)
        mes(spot.found)
    }

    private suspend fun ProtectedAccess.studyHead(bull: Boolean) {
        val bit = if (bull) TAKEN_WOODEN_DISK else TAKEN_OLD_RED_DISK
        if (bull) {
            mesbox("You notice there is something unusual about the right eye of this bulls' head...")
        } else {
            mesbox("You notice there is something unusual about the left eye of this unicorn head...")
        }
        if (player.ftHas(FtVarps.peer, bit) || !player.onTrial(Trial.Peer)) {
            mes(if (bull) "It's unusual because you've already taken it!" else "It's unusual because you've removed it already!")
            return
        }
        if (bull) {
            mesbox("It is not an eye at all, but some kind of disk made of wood. You take it from the head.")
        } else {
            mesbox("It is not an eye at all, but some kind of red coloured disk. You take it from the head.")
        }
        player.ftSet(FtVarps.peer, bit)
        invAddOrDrop(ft.objRepo, if (bull) FtObjs.wooden_disk else FtObjs.old_red_disk)
    }

    /* Water */

    private fun ProtectedAccess.fill(held: ObjType, full: ObjType, name: String) {
        if (held.id == full.id) {
            mes("Nothing interesting happens.")
            return
        }
        invReplace(inv, held, 1, full)
        mes("You fill the $name from the tap.")
    }

    private fun ProtectedAccess.drain(held: ObjType, empty: ObjType, name: String) {
        invReplace(inv, held, 1, empty)
        mes("You empty the $name down the drain.")
    }

    private fun ProtectedAccess.pour(bucket: Int, jug: Int) {
        val intoJug = bucket == BUCKET_FULL || jug == 0
        val (newBucket, newJug) =
            if (intoJug) {
                val moved = minOf(bucket, JUG_FULL - jug)
                (bucket - moved) to (jug + moved)
            } else {
                val moved = minOf(jug, BUCKET_FULL - bucket)
                (bucket + moved) to (jug - moved)
            }
        if (newBucket == bucket) {
            mes("Nothing interesting happens.")
            return
        }
        invReplace(inv, FtObjs.buckets[bucket], 1, FtObjs.buckets[newBucket])
        invReplace(inv, FtObjs.jugs[jug], 1, FtObjs.jugs[newJug])
        mes(
            when {
                intoJug && newBucket == 0 -> "You empty the bucket into the jug."
                intoJug -> "You fill the jug to the brim."
                newJug == 0 -> "You empty the jug into the bucket."
                else -> "You fill the bucket to the brim."
            }
        )
    }

    private fun ProtectedAccess.weigh(litres: Int, jug: Boolean = false) {
        val name = if (jug) "jug" else "bucket"
        mes("You place the $name on the scale.")
        when {
            jug || litres < SCALE_LITRES -> mes("It is too light to balance it properly.")
            litres > SCALE_LITRES -> mes("It is too heavy to balance it properly.")
            player.ftHas(FtVarps.peer, VASE_TAKEN) -> mes("It is a perfect counterweight and balances precisely.")
            else -> {
                mes("It is a perfect counterweight and balances precisely.")
                mes("You take a strange looking vase out of the chest.")
                player.ftSet(FtVarps.peer, VASE_TAKEN)
                invAddOrDrop(ft.objRepo, FtObjs.vase)
            }
        }
    }

    private fun ProtectedAccess.chill(full: Boolean, held: ObjType, frozen: ObjType, name: String) {
        if (!full) {
            mes("Your empty $name gets very cold on the icy table.")
            return
        }
        invReplace(inv, held, 1, frozen)
        mes("The icy table immediately freezes the water in your $name.")
    }

    private fun ProtectedAccess.thaw(held: ObjType, into: ObjType) {
        invReplace(inv, held, 1, into)
        mes("The heat of the range melts the ice.") // Authored (Wiki gives no message).
    }

    private fun ProtectedAccess.seal(open: ObjType, sealed: ObjType) {
        invDel(inv, FtObjs.vase_lid)
        invReplace(inv, open, 1, sealed)
        mes("You put the lid on the vase.") // Authored (Wiki gives no message).
    }

    /* The mural */

    private fun ProtectedAccess.studyMural() {
        // Authored line (not Wiki).
        mes(
            if (player.ftHas(FtVarps.peer, MURAL_DONE)) {
                "The mural is complete."
            } else {
                "A very unusual piece of artwork. Two round holes in it look like they are missing something red."
            }
        )
    }

    private fun ProtectedAccess.placeDisk(disk: ObjType, bit: Int) {
        if (player.ftHas(FtVarps.peer, MURAL_DONE) || player.ftHas(FtVarps.peer, bit)) {
            mes("Nothing interesting happens.")
            return
        }
        invDel(inv, disk)
        player.ftSet(FtVarps.peer, bit)
        mes("You place the red disk in the mural.") // Authored (Wiki gives no message).
        if (player.ftHas(FtVarps.peer, OLD_DISK_PLACED) && player.ftHas(FtVarps.peer, RED_DISK_PLACED)) {
            player.ftSet(FtVarps.peer, MURAL_DONE)
            invAddOrDrop(ft.objRepo, FtObjs.vase_lid)
            mes("The mural is complete. A small compartment opens, holding a lid.") // Authored.
        }
    }

    private class Spot(val x: Int, val z: Int, val bit: Int, val obj: () -> ObjType, val found: String)

    private companion object {
        const val RIDDLE_SOLVED = 1 shl 3
        const val TAKEN_JUG = 1 shl 4
        const val TAKEN_WOODEN_DISK = 1 shl 5
        const val TAKEN_OLD_RED_DISK = 1 shl 6
        const val TAKEN_BUCKET = 1 shl 7
        const val TAKEN_HERRING = 1 shl 8
        const val TAKEN_MAGNET = 1 shl 9
        const val TAKEN_SHIP = 1 shl 10
        const val TAKEN_PICK = 1 shl 11
        const val TAKEN_THREAD = 1 shl 12
        const val VASE_TAKEN = 1 shl 14
        const val MURAL_DONE = 1 shl 15
        const val OLD_DISK_PLACED = 1 shl 17
        const val RED_DISK_PLACED = 1 shl 18

        const val BUCKET_FULL = 5
        const val JUG_FULL = 3
        const val SCALE_LITRES = 4
        const val TRAPDOOR_TICKS = 200

        /** The house's two hatches: the west one over the front half, the east one over the back. */
        const val WEST_X = 2631
        const val HATCH_Z = 3663

        /** Wiki riddle order (transcript "Riddle 1" ... "Riddle 6"). */
        val RIDDLES = listOf("LIFE", "TIME", "MIND", "TREE", "FIRE", "WIND")

        val SPOTS =
            listOf(
                Spot(2638, 3662, TAKEN_JUG, { FtObjs.jugs[0] }, "You find a jug with a number three painted on it."),
                Spot(2629, 3660, TAKEN_BUCKET, { FtObjs.buckets[0] }, "You find a bucket with a number five painted on it."),
                Spot(2634, 3665, TAKEN_HERRING, { FtObjs.red_herring }, "Hidden behind some old books, you find a red herring."),
                Spot(2633, 3660, TAKEN_MAGNET, { FtObjs.magnet }, "You find a magnet hidden inside."),
                Spot(2637, 3660, TAKEN_SHIP, { FtObjs.toy_ship }, "You find a toy ship hidden inside."),
                Spot(2638, 3660, TAKEN_PICK, { FtObjs.small_pick }, "You find a small pick hidden inside."),
                Spot(2638, 3663, TAKEN_THREAD, { FtObjs.thread }, "You find some thread hidden inside."),
            )

        val PUZZLE_OBJS: List<ObjType> by lazy {
            FtObjs.buckets + FtObjs.jugs +
                listOf(
                    FtObjs.bucket_frozen,
                    FtObjs.jug_frozen,
                    FtObjs.vase,
                    FtObjs.vase_water,
                    FtObjs.vase_frozen,
                    FtObjs.vase_lid,
                    FtObjs.vase_sealed,
                    FtObjs.vase_sealed_water,
                    FtObjs.vase_sealed_frozen,
                    FtObjs.frozen_key,
                    FtObjs.seers_key,
                    FtObjs.red_herring,
                    FtObjs.red_goop,
                    FtObjs.wooden_disk,
                    FtObjs.red_disk,
                    FtObjs.old_red_disk,
                    FtObjs.magnet,
                    FtObjs.thread,
                    FtObjs.small_pick,
                    FtObjs.toy_ship,
                    FtObjs.herring,
                )
        }
    }
}
