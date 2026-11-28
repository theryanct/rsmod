package org.rsmod.content.quests.barcrawl

import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.type.builders.varp.VarpBuilder
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
import org.rsmod.content.quests.framework.questState
import org.rsmod.content.quests.framework.runQuestSection
import org.rsmod.content.quests.framework.setQuestState
import org.rsmod.game.entity.Npc
import org.rsmod.game.hit.HitType
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.stat.StatType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Alfred Grimhand's Barcrawl (OSRS Wiki miniquest). Quest-list varbit `barcrawl_progress`, end
 * state 2; the Barbarian Outpost guard's multinpc reads varp `barcrawl` (2 = post-crawl), so both
 * are kept in step. The ten signed bars are server varp `bcw_progress` (bit per [Bar]).
 * - 1 the Barbarian guard hands over a Barcrawl card (needs a free slot). Each bartender, told
 *   "I'm doing Alfred Grimhand's Barcrawl", sells their strongest drink (with its stat drops and
 *   damage) and signs the card.
 * - 2 the full card back to the guard: entry to the outpost's agility course, Scorpion Catcher.
 *
 * Simplified: the drinks' stat drops and damage are approximations of the Wiki's descriptions;
 * a card that isn't carried counts as lost (the guard hands a new one over, signatures kept);
 * the guard's vial-smashing offer is just dialogue.
 */
class Barcrawl
@Inject
constructor(private val npcTypes: NpcTypeList, private val objRepo: ObjRepository) :
    PluginScript() {
    private val dialogue by lazy { QuestDialogue.load(Barcrawl::class.java, "barcrawl") }

    override fun ScriptContext.startup() {
        // The guard is a multinpc with no ops of its own, so its Talk-to is bound directly.
        onOpNpc1(BarNpcs.guard) { talkGuard(it.npc) }
        onOpHeld1(BarObjs.card) { readCard() }
        with(QuestTalk) {
            for (bar in BARS) {
                register(npcTypes, bar.npc(), BARCRAWL, wants = { wantsBar(bar) }) { drink(it, bar) }
            }
        }
    }

    private val ProtectedAccess.state: Int
        get() = player.questState(BARCRAWL)

    private fun ProtectedAccess.signed(): Int = player.vars[BarVarps.progress]

    private fun ProtectedAccess.wantsBar(bar: Bar): Boolean =
        state == STARTED && (signed() and bar.bit) == 0 && carries(BarObjs.card)

    private fun ProtectedAccess.setState(value: Int) {
        player.setQuestState(BARCRAWL, value)
        VarPlayerIntMapSetter.set(player, BarVarps.guard_state, value)
    }

    /* The guard */

    private suspend fun ProtectedAccess.talkGuard(npc: Npc) {
        when {
            state == 0 -> {
                var given = false
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player does not have enough inventory space:" to { inv.isFull() },
                                "If the player has sufficient inventory space:" to { !inv.isFull() },
                            ),
                        boxes =
                            mapOf(
                                "The guard hands you a Barcrawl card." to {
                                    invAddOrDrop(objRepo, BarObjs.card)
                                    given = true
                                    false
                                }
                            ),
                    )
                runQuestSection(npc, dialogue, "Talking to the barbarian guard", hooks)
                if (given) setState(STARTED)
            }
            state == STARTED && carries(BarObjs.card) && signed() and ALL_BARS == ALL_BARS -> {
                val hooks =
                    SimpleQuestHooks(
                        boxes =
                            mapOf(
                                "You give the card to the barbarian." to {
                                    invDel(inv, BarObjs.card)
                                    false
                                }
                            )
                    )
                runQuestSection(npc, dialogue, "Talking to the barbarian guard (2)", hooks)
                // completeQuest sets the quest varbit (setting it first would skip the scroll).
                VarPlayerIntMapSetter.set(player, BarVarps.guard_state, COMPLETE)
                completeQuest(BARCRAWL)
            }
            state == STARTED -> {
                val has = carries(BarObjs.card)
                val hooks =
                    SimpleQuestHooks(
                        conditions =
                            mapOf(
                                "If the player has their barcrawl card in their inventory:" to { has },
                                "If the player has lost their barcrawl card:" to { !has },
                            ),
                        boxes =
                            mapOf(
                                "The guard hands you a Barcrawl card." to {
                                    invAddOrDrop(objRepo, BarObjs.card)
                                    false
                                }
                            ),
                    )
                runQuestSection(npc, dialogue, "Talking to the barbarian guard again", hooks)
            }
            else ->
                // Authored (not Wiki).
                startDialogue(npc) { chatNpc(happy, "Welcome back, barbarian. Go on through.") }
        }
    }

    /** Authored (not Wiki: the card is an interface there): which bars have signed. */
    private suspend fun ProtectedAccess.readCard() {
        val done = BARS.filter { signed() and it.bit != 0 }.joinToString(", ") { it.name }
        val left = BARS.filter { signed() and it.bit == 0 }.joinToString(", ") { it.name }
        val signedText = done.ifEmpty { "none yet" }
        val leftText = left.ifEmpty { "none - back to the Barbarian Outpost!" }
        mesbox("The official Alfred Grimhand Barcrawl. Signed: $signedText. Still to visit: $leftText.")
    }

    /* The bars */

    private suspend fun ProtectedAccess.drink(npc: Npc, bar: Bar) {
        var ordered = false
        val price = bar.price
        val hooks =
            SimpleQuestHooks(
                conditions =
                    mapOf(
                        "If the player does not have $price coins:" to { !carries(BarObjs.coins, price) },
                        "If the player has $price coins:" to { carries(BarObjs.coins, price) },
                    ),
                options =
                    mapOf(
                        "I'm doing Alfred Grimhand's Barcrawl." to { ordered = true },
                        "I'm doing Alfred Grimhand's barcrawl." to { ordered = true },
                        "I'm doing Alfred Grimhands Barcrawl." to { ordered = true },
                    ),
                // Zembo's and Tina's sections open with empty Wiki boxes (pictures).
                boxes = mapOf("" to { true }),
            )
        runQuestSection(npc, dialogue, bar.section, hooks, mapOf(bar.speaker to bar.npc()))
        if (!(ordered || bar.noMenu) || !carries(BarObjs.coins, price)) return
        invDel(inv, BarObjs.coins, price)
        for (line in bar.messages) {
            mes(line)
            delay(1)
        }
        for (stat in bar.drains()) statSub(stat, DRAIN_CONSTANT, DRAIN_PERCENT)
        if (bar.damagePercent > 0) {
            val hp = stat(stats.hitpoints)
            val damage = (statBase(stats.hitpoints) * bar.damagePercent / 100).coerceIn(1, maxOf(1, hp - 1))
            if (hp > 1) player.queueHit(delay = 0, type = HitType.Typeless, damage = damage)
        }
        VarPlayerIntMapSetter.set(player, BarVarps.progress, signed() or bar.bit)
    }

    /** One bar: who serves, the Wiki section, price, messages, stat drops and damage. */
    private class Bar(
        val name: String,
        val bit: Int,
        val npc: () -> NpcType,
        val speaker: String,
        val section: String,
        val price: Int,
        val messages: List<String>,
        val drains: () -> List<StatType> = { emptyList() },
        val damagePercent: Int = 0,
        val noMenu: Boolean = false,
    )

    companion object {
        const val STARTED = 1
        const val COMPLETE = 2

        private const val DRAIN_CONSTANT = 2
        private const val DRAIN_PERCENT = 5

        private val BARS =
            listOf(
                Bar(
                    "Blue Moon Inn",
                    1 shl 0,
                    { BarNpcs.bluemoon },
                    "Bartender",
                    "Uncle Humphrey's Gutrot",
                    50,
                    listOf(
                        "You buy some Uncle Humphrey's Gutrot.",
                        "You drink the Uncle Humphrey's Gutrot.",
                        "Your insides feel terrible.",
                        "The bartender signs your card.",
                    ),
                    { listOf(stats.attack, stats.defence, stats.strength, stats.smithing) },
                    damagePercent = 10,
                ),
                Bar(
                    "Blurberry Bar",
                    1 shl 1,
                    { BarNpcs.blurberry },
                    "Blurberry",
                    "Fire Toad Blast",
                    10,
                    listOf(
                        "You buy a Fire Toad Blast.",
                        "Your mouth and throat burn as you gulp it down.",
                        "Blurberry signs your card.",
                    ),
                    damagePercent = 10,
                    noMenu = true,
                ),
                Bar(
                    "Dead Man's Chest",
                    1 shl 2,
                    { BarNpcs.deadmans },
                    "Bartender",
                    "Supergrog",
                    15,
                    listOf(
                        "The bartender serves you a glass of strange thick dark liquid.",
                        "You wince and drink it.",
                        "You stagger backwards.",
                        "You think you see 2 bartenders signing 2 barcrawl cards.",
                    ),
                    { listOf(stats.attack, stats.defence, stats.herblore, stats.cooking, stats.prayer) },
                ),
                Bar(
                    "Dragon Inn",
                    1 shl 3,
                    { BarNpcs.dragon },
                    "Bartender",
                    "Fire Brandy",
                    12,
                    listOf(
                        "The bartender hands you a small glass and sets light to the contents.",
                        "You blow out the flame and drink it.",
                        "Your vision blurs and you stagger slightly.",
                        "You can just about make out the bartender signing your barcrawl card.",
                    ),
                    { listOf(stats.attack, stats.defence) },
                ),
                Bar(
                    "Flying Horse Inn",
                    1 shl 4,
                    { BarNpcs.flyinghorse },
                    "Bartender",
                    "Heart Stopper",
                    8,
                    listOf(
                        "The bartender hands you a shot of Heart Stopper.",
                        "You grimace and drink it.",
                        "You clutch your chest.",
                        "Through your tears you see the bartender...",
                        "signing your barcrawl card",
                    ),
                    damagePercent = 25,
                ),
                Bar(
                    "Forester's Arms",
                    1 shl 5,
                    { BarNpcs.foresters },
                    "Bartender",
                    "Liverbane Ale",
                    18,
                    listOf(
                        "The bartender gives you a glass of Liverbane Ale.",
                        "You gulp it down.",
                        "The room seems to be swaying.",
                        "The bartender scrawls his signature on your card.",
                    ),
                    { listOf(stats.attack, stats.defence, stats.fletching, stats.firemaking, stats.woodcutting) },
                ),
                Bar(
                    "Jolly Boar Inn",
                    1 shl 6,
                    { BarNpcs.jollyboar },
                    "Bartender",
                    "Olde Suspiciouse",
                    10,
                    listOf(
                        "You buy a pint of Olde Suspiciouse.",
                        "You gulp it down.",
                        "Your head is spinning.",
                        "The bartender signs your card.",
                    ),
                    { listOf(stats.attack, stats.defence, stats.mining, stats.crafting, stats.magic) },
                ),
                Bar(
                    "Karamja Spirits Bar",
                    1 shl 7,
                    { BarNpcs.zembo },
                    "Zembo",
                    "Ape Bite Liqueur",
                    7,
                    listOf(
                        "You buy some Ape Bite Liqueur.",
                        "You swirl it around and swallow it.",
                        "Zembo signs your card.",
                    ),
                ),
                Bar(
                    "Rising Sun Inn",
                    1 shl 8,
                    { BarNpcs.tina },
                    "Tina",
                    "Hand of Death Cocktail",
                    70,
                    listOf(
                        "You buy a Hand of Death cocktail.",
                        "You drink the cocktail.",
                        "You stumble around the room.",
                        "Tina giggles and signs your card.",
                    ),
                    { listOf(stats.attack, stats.defence, stats.ranged, stats.fishing) },
                    damagePercent = 10,
                ),
                Bar(
                    "Rusty Anchor",
                    1 shl 9,
                    { BarNpcs.rustyanchor },
                    "Bartender",
                    "Black Skull Ale",
                    8,
                    listOf(
                        "You buy a Black Skull Ale.",
                        "You drink your Black Skull Ale...",
                        "Your vision blurs.",
                        "The bartender signs your card.",
                    ),
                ),
            )

        private val ALL_BARS = (1 shl BARS.size) - 1

        val BARCRAWL =
            QuestDef(
                name = "Alfred Grimhand's Barcrawl",
                varbit = { BarVarBits.progress },
                complete = COMPLETE,
                questPoints = 0,
                rewards = listOf("Entry to the Barbarian Outpost agility course", "Barbarian vial smashing"),
                scrollObj = { BarObjs.card },
                listRow = "miniquest_barcrawl",
            )
    }
}

// Every cache ref Alfred Grimhand's Barcrawl uses (re-check after cache updates).

internal object BarNpcs : NpcReferences() {
    /** Multinpc on varp `barcrawl`: pre-crawl at 0/1/3, post-crawl at 2. */
    val guard = find("barbguard1")
    val bluemoon = find("bluemoon_bartender")
    val blurberry = find("blurberry")
    val deadmans = find("deadmans_bartender")
    val dragon = find("dragon_bartender")
    val flyinghorse = find("flyinghorse_bartender")
    val foresters = find("foresters_bartender")
    val jollyboar = find("jollyboar_bartender")
    val zembo = find("zembo")
    val tina = find("risingsun_barmaid3")
    val rustyanchor = find("rustyanchor_bartender")
}

internal object BarObjs : ObjReferences() {
    val card = find("barcrawl_card")
    val coins = find("coins")
}

internal object BarVarps : VarpReferences() {
    val guard_state = find("barcrawl")

    /** Server varp (65798): bits 0-9 the signed bars (see [Barcrawl]). */
    val progress = find("bcw_progress")
}

internal object BarVarBits : VarBitReferences() {
    val progress = find("barcrawl_progress")
}

internal object BarVarpBuilds : VarpBuilder() {
    init {
        build("bcw_progress")
    }
}
