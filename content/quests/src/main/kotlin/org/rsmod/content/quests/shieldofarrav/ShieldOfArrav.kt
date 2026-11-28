package org.rsmod.content.quests.shieldofarrav

import jakarta.inject.Inject
import org.rsmod.api.death.NpcKilledEvent
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpNpcU
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.quests.framework.QuestDef
import org.rsmod.content.quests.framework.QuestDialogue
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.SimpleQuestHooks
import org.rsmod.content.quests.framework.carries
import org.rsmod.content.quests.framework.completeQuest
import org.rsmod.content.quests.framework.onHeldOpLabel
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.content.quests.framework.onNpcOpLabel
import org.rsmod.content.quests.framework.passThroughWall
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.varp.VarpType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Shield of Arrav (OSRS Wiki). The quest list reads varbit `shieldofarrav` (0, 1 started, 2
 * complete); the gangs use the cache varps `phoenixgang` / `blackarmgang` (the client's kudos check
 * counts the quest done at phoenixgang 10 or blackarmgang 4; Jonny the Beard turns attackable at
 * phoenixgang 8):
 * - shared (phoenixgang): 1 Reldo's quest, 2 read the book, 3 Reldo explained the gangs;
 * - Phoenix: 4 Baraek's directions (20 coins), 8 Straven's task (Jonny's intel report), 9 joined
 *   (weapon store key), 10 complete;
 * - Black Arm (blackarmgang): 1 Charlie sent you to Katrine, 2 her task (two Phoenix crossbows,
 *   dropped by the Weaponsmaster), 3 joined, 4 complete.
 * Your gang's shield half goes to Curator Haig Halen for two copies of your half certificate; the
 * full certificate (both halves) goes to King Roald: 600 coins, 1 QP. Reldo's Talk-to is in
 * `reldo/Reldo.kt` (shared with The Knight's Sword).
 *
 * Solo workaround (the quest needs a partner from the other gang): once you hold your certificate
 * halves, the other gang's contact (Straven / Katrine) sells you their half for [HALF_PRICE] coins;
 * Black Arm recruits can also buy Charlie the Tramp's copy of the Phoenix weapon store key.
 * Server varp `soa_progress`: Charlie paid, curator certificates given, other half bought.
 *
 * Not done: the antique lamp from King Roald (not in the rev 233 cache), the Charlie haggling
 * branches (always 10 coins), trading quest items to other players.
 */
class ShieldOfArrav
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val locRepo: LocRepository,
    private val objRepo: ObjRepository,
) : PluginScript() {
    private val dialogue by lazy { ShieldOfArravDialogue.dialogue }

    override fun ScriptContext.startup() {
        onLocOpLabel(locTypes, ArravLocs.bookcase, "Search") { searchBookcase() }
        onHeldOpLabel(objTypes, ArravObjs.book, "Read") { readBook() }

        onNpcOpLabel(npcTypes, ArravNpcs.baraek, "Talk-to") { talkBaraek(it) }
        onNpcOpLabel(npcTypes, ArravNpcs.straven, "Talk-to") { talkStraven(it) }
        onLocOpLabel(locTypes, ArravLocs.phoenix_door, "Open") { phoenixDoor(it) }
        onLocOpLabel(locTypes, ArravLocs.phoenix_chest, "Open") { phoenixChest(it) }
        onLocOpLabel(locTypes, ArravLocs.weapon_store_door, "Open") { weaponStoreDoor(it) }
        onNpcOpLabel(npcTypes, ArravNpcs.weaponsmaster, "Talk-to") { talkWeaponsmaster(it) }

        onNpcOpLabel(npcTypes, ArravNpcs.charlie, "Talk-to") { talkCharlie(it) }
        onNpcOpLabel(npcTypes, ArravNpcs.katrine, "Talk-to") { talkKatrine(it) }
        onOpNpcU(ArravNpcs.katrine, ArravObjs.crossbow) { talkKatrine(it.npc) }
        onLocOpLabel(locTypes, ArravLocs.blackarm_door, "Open") { blackArmDoor(it) }
        onLocOpLabel(locTypes, ArravLocs.cupboard, "Open") { blackArmCupboard(it) }

        onNpcOpLabel(npcTypes, ArravNpcs.curator, "Talk-to") { talkCurator(it) }
        for (half in ArravObjs.cert_halves) {
            onHeldOpLabel(objTypes, half, "Inspect") { inspectHalf() }
        }
        // King Roald's Talk-to is shared (QuestTalk): Arrav's while the certificate is pending.
        with(QuestTalk) {
            register(npcTypes, ArravNpcs.king, wants = { wantsKing() }) {
                talkKing(it)
            }
        }
        onEvent<NpcKilledEvent> { npcKilled(this) }
    }

    /* Vars */

    private fun ProtectedAccess.gang(varp: VarpType): Int = player.vars[varp]

    private fun ProtectedAccess.setGang(varp: VarpType, value: Int) {
        VarPlayerIntMapSetter.set(player, varp, value)
    }

    private val ProtectedAccess.phoenix: Int
        get() = gang(ArravVarps.phoenixgang)

    private val ProtectedAccess.blackArm: Int
        get() = gang(ArravVarps.blackarmgang)

    private fun ProtectedAccess.isPhoenix(): Boolean = phoenix >= PHOENIX_JOINED

    private fun ProtectedAccess.isBlackArm(): Boolean = blackArm >= BLACKARM_JOINED

    private fun ProtectedAccess.flag(bit: Int): Boolean =
        player.vars[ArravVarps.progress] and bit != 0

    private fun ProtectedAccess.setFlag(bit: Int) {
        val value = player.vars[ArravVarps.progress] or bit
        VarPlayerIntMapSetter.set(player, ArravVarps.progress, value)
    }

    /** The certificate half your gang's curator copies are of (Phoenix left, Black Arm right). */
    private fun ProtectedAccess.ownHalf(): ObjType? =
        when {
            isPhoenix() -> ArravObjs.cert_left
            isBlackArm() -> ArravObjs.cert_right
            else -> null
        }

    private fun ProtectedAccess.takeIfRoom(obj: ObjType): Boolean {
        if (inv.isFull()) {
            mes("You don't have enough space in your inventory.")
            return false
        }
        invAdd(inv, obj)
        return true
    }

    /* The book */

    private suspend fun ProtectedAccess.searchBookcase() {
        if (player.questState(SHIELD_OF_ARRAV) != 1 || phoenix != STARTED) {
            mes("You search the bookcase but find nothing of interest.")
            return
        }
        if (carries(ArravObjs.book)) {
            mes("You already have the book.")
            return
        }
        if (!inv.isFull()) {
            runQuestSection(null, dialogue, "Searching the right bookcase")
            invAdd(inv, ArravObjs.book)
        } else {
            mes("You don't have enough space in your inventory.")
        }
    }

    private suspend fun ProtectedAccess.readBook() {
        startDialogue { mesbox(BOOK_TEXT) }
        if (phoenix == STARTED) setGang(ArravVarps.phoenixgang, BOOK_READ)
    }

    /* Phoenix Gang */

    private suspend fun ProtectedAccess.talkBaraek(npc: Npc) {
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has 20 coins." to { carries(ArravObjs.coins, BARAEK_FEE) },
                        "If the player doesn't have 20 coins." to {
                            !carries(ArravObjs.coins, BARAEK_FEE)
                        },
                    ),
                options =
                    mapOf(
                        "Okay. Have 20 gold coins." to {
                            if (phoenix == RELDO_TOLD && carries(ArravObjs.coins, BARAEK_FEE)) {
                                invDel(inv, ArravObjs.coins, BARAEK_FEE)
                                setGang(ArravVarps.phoenixgang, BARAEK_TOLD)
                            }
                        },
                    ),
            )
        if (phoenix < RELDO_TOLD) {
            startDialogue(npc) { chatNpc(neutral, "Can I interest you in some furs?") }
            return
        }
        runQuestSection(npc, dialogue, "Talking to Baraek", hooks)
    }

    private suspend fun ProtectedAccess.talkStraven(npc: Npc?) {
        val report = ArravObjs.intel_report
        var gaveKey = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player doesn't have the intel report." to { !carries(report) },
                        "If the player has the intel report." to { carries(report) },
                        "If the intel report is in the bank." to { false },
                        "If the intel report is in the inventory." to { carries(report) },
                    ),
                options =
                    mapOf(
                        "I'd like to offer you my services." to {
                            if (phoenix == BARAEK_TOLD) {
                                setGang(ArravVarps.phoenixgang, STRAVEN_TASK)
                            }
                        },
                    ),
                boxes =
                    mapOf(
                        "Straven hands you a key." to {
                            gaveKey = true
                            false
                        },
                    ),
            )
        val section =
            when {
                isBlackArm() -> null
                isPhoenix() && !carries(ArravObjs.weapon_key) -> STRAVEN_LOST_KEY
                isPhoenix() -> "Talking to Straven after joining the Phoenix Gang"
                phoenix == STRAVEN_TASK -> STRAVEN_TASK_SECTION
                phoenix >= BARAEK_TOLD -> "Talking to Straven or attempting to open the door"
                else -> null
            }
        if (section == null) {
            if (isBlackArm() && npc != null) {
                sellOtherHalf(npc, ArravObjs.cert_left, "Straven")
            } else {
                mes("The door is securely locked.")
            }
            return
        }
        runQuestSection(npc, dialogue, section, hooks)
        if (gaveKey) {
            if (phoenix == STRAVEN_TASK && carries(report)) {
                invDel(inv, report)
                setGang(ArravVarps.phoenixgang, PHOENIX_JOINED)
            }
            invAddOrDrop(objRepo, ArravObjs.weapon_key)
        }
    }

    private suspend fun ProtectedAccess.phoenixDoor(door: BoundLocInfo) {
        if (isPhoenix()) {
            passThroughWall(door)
            return
        }
        talkStraven(null)
    }

    private suspend fun ProtectedAccess.phoenixChest(chest: BoundLocInfo) {
        if (!isPhoenix()) {
            mes("The chest is locked.")
            return
        }
        locRepo.change(chest, ArravLocs.phoenix_chest_open, CHEST_TICKS)
        val half = ArravObjs.shield_phoenix
        when {
            carries(half) || phoenix >= COMPLETE_PHOENIX || flag(CERTS_GIVEN) ->
                mes("You open the chest, but don't find anything of interest inside.")
            inv.isFull() -> {
                mes("You open the chest and find half of a shield inside, but you don't have")
                mes("enough room to take it.")
            }
            else -> {
                invAdd(inv, half)
                mes("You open the chest and find half of a shield inside.")
            }
        }
    }

    private suspend fun ProtectedAccess.weaponStoreDoor(door: BoundLocInfo) {
        if (!carries(ArravObjs.weapon_key)) {
            runQuestSection(null, dialogue, WEAPON_STORE_LOCKED)
            return
        }
        passThroughWall(door)
    }

    private suspend fun ProtectedAccess.talkWeaponsmaster(npc: Npc) {
        val section =
            if (isPhoenix()) {
                "Talking to the Weaponsmaster after joining the Phoenix Gang"
            } else {
                "Talking to the Weaponsmaster"
            }
        runQuestSection(npc, dialogue, section)
    }

    /** Jonny the Beard drops the intel report (Straven's task), the Weaponsmaster the crossbows. */
    private fun npcKilled(event: NpcKilledEvent) {
        val player = event.hero
        val id = event.npc.type.id
        val at = event.npc.coords
        val phoenix = player.vars[ArravVarps.phoenixgang]
        val blackArm = player.vars[ArravVarps.blackarmgang]
        when {
            id in ArravNpcs.jonnys.map { it.id } && phoenix == STRAVEN_TASK ->
                objRepo.add(ArravObjs.intel_report, at, DROP_TICKS, player)
            id in ArravNpcs.weaponsmasters.map { it.id } && blackArm == KATRINE_TASK -> {
                objRepo.add(ArravObjs.crossbow, at, DROP_TICKS, player)
                objRepo.add(ArravObjs.crossbow, at, DROP_TICKS, player)
            }
        }
    }

    /* Black Arm Gang */

    private suspend fun ProtectedAccess.talkCharlie(npc: Npc) {
        var paid = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If asking for the first time:" to { blackArm == 0 },
                        "Otherwise:" to { blackArm != 0 },
                        "If you haven't yet paid Charlie for the information:" to {
                            !flag(CHARLIE_PAID)
                        },
                        "Asking again after already paying Charlie for the information:" to {
                            flag(CHARLIE_PAID)
                        },
                    ),
                boxes =
                    mapOf(
                        "You give ten coins to Charlie." to {
                            paid = carries(ArravObjs.coins, CHARLIE_FEE)
                            if (paid) invDel(inv, ArravObjs.coins, CHARLIE_FEE)
                            false
                        },
                    ),
            )
        when {
            isPhoenix() -> runQuestSection(npc, dialogue, CHARLIE_PHOENIX, hooks)
            blackArm >= KATRINE_TASK && !isBlackArm() -> {
                runQuestSection(npc, dialogue, CHARLIE_AFTER_TASK, hooks)
                if (paid) setFlag(CHARLIE_PAID)
                if (flag(CHARLIE_PAID)) sellKeyCopy(npc)
            }
            phoenix >= RELDO_TOLD && !isBlackArm() -> {
                runQuestSection(npc, dialogue, "Talking to Charlie the Tramp", hooks)
                if (blackArm == 0) setGang(ArravVarps.blackarmgang, CHARLIE_TOLD)
            }
            else -> startDialogue(npc) { chatNpc(sad, "Spare some change, guv?") }
        }
    }

    /** Solo workaround: Charlie lifted a copy of the Phoenix weapon store key. */
    private suspend fun ProtectedAccess.sellKeyCopy(npc: Npc) {
        if (carries(ArravObjs.weapon_key)) return
        var buy = false
        startDialogue(npc) {
            chatNpc(
                neutral,
                "Oh, and you'll be needing a way into their weapon store, won't you? I lifted " +
                    "a copy of the key off one of them a while back. Yours for $KEY_COPY_PRICE " +
                    "coins.",
            )
            buy = choice2("Deal.", true, "No thanks.", false)
        }
        if (!buy) return
        if (!carries(ArravObjs.coins, KEY_COPY_PRICE)) {
            startDialogue(npc) { chatNpc(neutral, "No coins, no key.") }
            return
        }
        invDel(inv, ArravObjs.coins, KEY_COPY_PRICE)
        invAddOrDrop(objRepo, ArravObjs.weapon_key)
        startDialogue(npc) { chatNpc(happy, "Pleasure doing business. You never got it from me.") }
    }

    private suspend fun ProtectedAccess.talkKatrine(npc: Npc) {
        val bow = ArravObjs.crossbow
        var gave = false
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has no Phoenix crossbows:" to { !carries(bow) },
                        "If the player has one Phoenix crossbow:" to {
                            invTotal(inv, bow) == 1
                        },
                        "If the player has two Phoenix crossbows:" to { carries(bow, 2) },
                    ),
                options =
                    mapOf(
                        "Okay, I'll do it." to {
                            if (blackArm == CHARLIE_TOLD) {
                                setGang(ArravVarps.blackarmgang, KATRINE_TASK)
                            }
                        },
                    ),
                boxes =
                    mapOf(
                        "You give the crossbows to Katrine." to {
                            gave = carries(bow, 2)
                            false
                        },
                    ),
            )
        when {
            isPhoenix() -> {
                runQuestSection(npc, dialogue, "Talking to Katrine after joining the Phoenix Gang")
                sellOtherHalf(npc, ArravObjs.cert_right, "Katrine")
            }
            isBlackArm() -> runQuestSection(npc, dialogue, KATRINE_MEMBER, hooks)
            blackArm == KATRINE_TASK -> {
                runQuestSection(npc, dialogue, KATRINE_TASK_SECTION, hooks)
                if (gave) {
                    invDel(inv, bow, 2)
                    setGang(ArravVarps.blackarmgang, BLACKARM_JOINED)
                }
            }
            blackArm == CHARLIE_TOLD ->
                runQuestSection(npc, dialogue, "Talking to Katrine", hooks)
            else -> startDialogue(npc) { chatNpc(neutral, "What do you want?") }
        }
    }

    /**
     * Solo workaround: the other gang's contact sells their certificate half once you hold your
     * own (from the curator), in character for a gang that wants the reward split their way.
     */
    private suspend fun ProtectedAccess.sellOtherHalf(npc: Npc, half: ObjType, who: String) {
        val own = ownHalf() ?: return
        if (!carries(own) || carries(half) || flag(HALF_BOUGHT)) {
            startDialogue(npc) { chatNpc(angry, "Get lost before I lose my temper.") }
            return
        }
        var buy = false
        startDialogue(npc) {
            chatNpc(
                neutral,
                "I hear you've got your hands on a certificate for the Shield of Arrav. Ours is " +
                    "no use without yours, and yours is no use without ours.",
            )
            chatNpc(neutral, "I'll sell you our half for $HALF_PRICE coins. Take it or leave it.")
            buy = choice2("I'll take it.", true, "Not today.", false)
        }
        if (!buy) return
        if (!carries(ArravObjs.coins, HALF_PRICE)) {
            startDialogue(npc) { chatNpc(neutral, "Come back when you've got the coins.") }
            return
        }
        invDel(inv, ArravObjs.coins, HALF_PRICE)
        invAddOrDrop(objRepo, half)
        setFlag(HALF_BOUGHT)
        mes("$who sells you the other half of the certificate.")
    }

    private suspend fun ProtectedAccess.blackArmDoor(door: BoundLocInfo) {
        when {
            isBlackArm() -> passThroughWall(door)
            isPhoenix() -> runQuestSection(null, dialogue, BLACKARM_DOOR_PHOENIX)
            else -> runQuestSection(null, dialogue, BLACKARM_DOOR_OUTSIDER)
        }
    }

    private suspend fun ProtectedAccess.blackArmCupboard(cupboard: BoundLocInfo) {
        locRepo.change(cupboard, ArravLocs.cupboard_open, CHEST_TICKS)
        val half = ArravObjs.shield_blackarm
        when {
            !isBlackArm() || carries(half) || flag(CERTS_GIVEN) ->
                mes("You open the cupboard, but don't find anything of interest inside.")
            takeIfRoom(half) -> mes("You open the cupboard and find half of a shield inside.")
        }
    }

    /* Finishing up */

    private suspend fun ProtectedAccess.talkCurator(npc: Npc) {
        val shield =
            listOf(ArravObjs.shield_phoenix, ArravObjs.shield_blackarm).firstOrNull { carries(it) }
        val own = ownHalf()
        when {
            own != null && shield != null && !flag(CERTS_GIVEN) -> {
                runQuestSection(npc, dialogue, "Talking to Curator Haig Halen")
                invDel(inv, shield)
                invAddOrDrop(objRepo, own, 2)
                setFlag(CERTS_GIVEN)
            }
            own != null && flag(CERTS_GIVEN) && lostCertificates(own) -> {
                runQuestSection(npc, dialogue, CURATOR_AGAIN)
                invAddOrDrop(objRepo, own, 2)
            }
            else -> startDialogue(npc) { chatNpc(neutral, "Welcome to the Varrock Museum.") }
        }
    }

    private fun ProtectedAccess.lostCertificates(own: ObjType): Boolean =
        !carries(own) &&
            !carries(ArravObjs.certificate) &&
            player.questState(SHIELD_OF_ARRAV) == 1

    private suspend fun ProtectedAccess.inspectHalf() {
        val both = carries(ArravObjs.cert_left) && carries(ArravObjs.cert_right)
        if (!both) {
            mes("Half of a certificate. I'll need the other half to claim a reward.")
            return
        }
        var combine = false
        val hooks = SimpleQuestHooks(options = mapOf("Yes." to { combine = true }))
        runQuestSection(null, dialogue, BOTH_HALVES, hooks)
        if (combine && carries(ArravObjs.cert_left) && carries(ArravObjs.cert_right)) {
            invDel(inv, ArravObjs.cert_left)
            invDel(inv, ArravObjs.cert_right)
            invAdd(inv, ArravObjs.certificate)
        }
    }

    private fun ProtectedAccess.wantsKing(): Boolean = player.questState(SHIELD_OF_ARRAV) == 1

    private suspend fun ProtectedAccess.talkKing(npc: Npc) {
        if (player.questState(SHIELD_OF_ARRAV) != 1) {
            startDialogue(npc) { chatNpc(neutral, "Greetings, adventurer.") }
            return
        }
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player has a shield half:" to {
                            carries(ArravObjs.shield_phoenix) || carries(ArravObjs.shield_blackarm)
                        },
                        "If the player has a half-certificate:" to {
                            ArravObjs.cert_halves.any { carries(it) }
                        },
                    ),
            )
        if (!carries(ArravObjs.certificate)) {
            runQuestSection(npc, dialogue, KING_NO_CERT, hooks)
            return
        }
        var shown = false
        val reward =
            SimpleQuestHooks(
                boxes =
                    mapOf(
                        "You show the certificate to the king." to {
                            shown = true
                            false
                        },
                    ),
            )
        runQuestSection(npc, dialogue, "Talking to King Roald with a full certificate", reward)
        if (!shown || !carries(ArravObjs.certificate)) return
        invDel(inv, ArravObjs.certificate)
        invAddOrDrop(objRepo, ArravObjs.coins, REWARD_COINS)
        if (isPhoenix()) setGang(ArravVarps.phoenixgang, COMPLETE_PHOENIX)
        if (isBlackArm()) setGang(ArravVarps.blackarmgang, COMPLETE_BLACKARM)
        completeQuest(SHIELD_OF_ARRAV)
    }

    companion object {
        /* phoenixgang */
        const val STARTED = 1
        const val BOOK_READ = 2
        const val RELDO_TOLD = 3
        const val BARAEK_TOLD = 4
        const val STRAVEN_TASK = 8
        const val PHOENIX_JOINED = 9
        const val COMPLETE_PHOENIX = 10

        /* blackarmgang */
        const val CHARLIE_TOLD = 1
        const val KATRINE_TASK = 2
        const val BLACKARM_JOINED = 3
        const val COMPLETE_BLACKARM = 4

        /* soa_progress */
        private const val CHARLIE_PAID = 1
        private const val CERTS_GIVEN = 1 shl 1
        private const val HALF_BOUGHT = 1 shl 2

        private const val BARAEK_FEE = 20
        private const val CHARLIE_FEE = 10
        private const val KEY_COPY_PRICE = 100
        const val HALF_PRICE = 500
        private const val REWARD_COINS = 600
        private const val CHEST_TICKS = 100
        private const val DROP_TICKS = 200

        private const val BOOK_TEXT =
            "The Shield of Arrav, by A R Wright. The book tells of the great shield of the hero " +
                "Arrav, once kept in the Varrock museum, and how it was stolen and split between " +
                "the two rival gangs of Varrock: the Phoenix Gang and the Black Arm Gang."
        private const val WEAPON_STORE_LOCKED =
            "Attempting to enter the weapon supply depot without the key"
        private const val STRAVEN_LOST_KEY = "Talking to Straven after losing the weapon store key"
        private const val STRAVEN_TASK_SECTION =
            "Talking to Straven or opening the door after accepting his task"
        private const val CHARLIE_PHOENIX =
            "Learning the location of the Black Arm gang from Charlie the Tramp"
        private const val CHARLIE_AFTER_TASK =
            "Talking to Charlie the Tramp after accepting Katrine's task"
        private const val KATRINE_MEMBER = "Talking to Katrine after joining the Black Arm Gang"
        private const val KATRINE_TASK_SECTION = "Talking to Katrine again after accepting her task"
        private const val BLACKARM_DOOR_PHOENIX =
            "Attempting to enter deeper into the Black Arm Gang base as a Phoenix Gang member"
        private const val BLACKARM_DOOR_OUTSIDER =
            "Attempting to enter the Black Arm Gang hideout before joining"
        private const val BOTH_HALVES = "Inspecting a certificate half while having both pieces"
        private const val KING_NO_CERT = "Talking to King Roald without a full certificate"
        private const val CURATOR_AGAIN =
            "Talking to the Curator again after giving him the shield half"

        val SHIELD_OF_ARRAV =
            QuestDef(
                name = "Shield of Arrav",
                varbit = { ArravVarBits.shieldofarrav },
                complete = 2,
                questPoints = 1,
                rewards = listOf("600 coins"),
                scrollObj = { ArravObjs.certificate },
            )

        /** Called by Reldo (shared script) when the player accepts the quest. */
        fun start(player: Player) {
            player.setQuestState(SHIELD_OF_ARRAV, 1)
            VarPlayerIntMapSetter.set(player, ArravVarps.phoenixgang, STARTED)
        }
    }
}

/** The transcript, shared with Reldo's script. */
internal object ShieldOfArravDialogue {
    val dialogue: QuestDialogue by lazy {
        QuestDialogue.load(ShieldOfArrav::class.java, "shield_of_arrav")
    }
}

// Every cache ref Shield of Arrav uses (re-check after the rev 243 cutover).

internal object ArravNpcs : NpcReferences() {
    val baraek = find("baraek")
    val straven = find("straven")
    val katrine = find("katrine")
    val charlie = find("tramppg")
    val curator = find("curator")
    val king = find("king_roald")

    /* Children of the jonny_the_beard (on phoenixgang) / weaponsmaster multinpcs. */
    val jonnys = listOf(find("jonny_the_beard_1op"), find("jonny_the_beard_2op"))
    val weaponsmaster = find("weaponsmaster_vis")
    val weaponsmasters = listOf(weaponsmaster)
}

internal object ArravLocs : LocReferences() {
    val bookcase = find("questbookcase")
    val phoenix_door = find("phoenixdoor")
    val phoenix_chest = find("phoenixshutchest")
    val phoenix_chest_open = find("phoenixopenchest")
    val weapon_store_door = find("phoenixdoor2")
    val blackarm_door = find("blackarmdoor")
    val cupboard = find("blackarmcupboardshut")
    val cupboard_open = find("blackarmcupboardopen")
}

internal object ArravObjs : ObjReferences() {
    val book = find("the_shield_of_arrav")
    val intel_report = find("intelligence_report")
    val weapon_key = find("phoenixkey2")
    val crossbow = find("phoenix_crossbow")
    val shield_phoenix = find("arravshield1")
    val shield_blackarm = find("arravshield2")
    val cert_left = find("arravcertificate_lft")
    val cert_right = find("arravcertificate_rht")
    val certificate = find("arravcertificate")
    val coins = find("coins")

    val cert_halves = listOf(cert_left, cert_right)
}

internal object ArravVarps : VarpReferences() {
    val phoenixgang = find("phoenixgang")
    val blackarmgang = find("blackarmgang")

    /** Server varp (65774): Charlie paid, curator certificates given, other half bought. */
    val progress = find("soa_progress")
}

internal object ArravVarBits : VarBitReferences() {
    val shieldofarrav = find("shieldofarrav")
}

internal object ArravVarpBuilds : VarpBuilder() {
    init {
        build("soa_progress")
    }
}
