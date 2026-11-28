package org.rsmod.content.quests.barbariantraining

import jakarta.inject.Inject
import org.rsmod.api.player.dialogue.Dialogue
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.type.refs.loc.LocReferences
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.api.type.refs.varbit.VarBitReferences
import org.rsmod.content.quests.framework.QuestTalk
import org.rsmod.content.quests.framework.onLocOpLabel
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Barbarian Training (OSRS Wiki miniquest), the non-Firemaking parts: Otto Godblessed's lessons
 * in Otto's Grotto. Dialogue is the Wiki transcript ("Transcript:Barbarian Training") except lines
 * marked `// Authored (not Wiki)`.
 *
 * Progress lives in the cache varbits of varp `brut_base_1` (which holds nothing but brut bits, so
 * no mirror varp is needed). Lesson states: 0 untaught, 1 taught, 2 task done (the skill script
 * sets it and says "You feel you have learned more of barbarian ways..."), 3 reported to Otto.
 * - `brut_fishing_r`: barbarian rod (rod from under his bed; a leaping catch, Fishing.kt).
 * - `brut_fishing_s`: barehanded harpoon fishing (a tuna/swordfish/shark caught by hand).
 * - `brut_smith_spear` / `brut_smith_hasta` (after both fishing lessons): a spear, then a hasta,
 *   on the barbarian anvil (BarbarianSmithing.kt).
 * - `brut_herb_potion` (after the rod lesson): 0, 1 Otto wants an attack mix, 2 handed over (every
 *   barbarian mix unlocked, Herblore.kt).
 * - `brut_miniquest` is set to 1 when the player agrees to train ("You think so?").
 *
 * Out of scope: Firemaking (bow lighting, pyre ships, Ancient Cavern) and Farming lessons, the
 * Zamorakian hasta conversion and the "my notes" book. Tai Bwo Wannai Trio is not required for the
 * spear lesson (the quest is not on this server). Firemaking options belong in [lessonOptions].
 */
class BarbarianTraining
@Inject
constructor(private val npcTypes: NpcTypeList, private val locTypes: LocTypeList) :
    PluginScript() {
    override fun ScriptContext.startup() {
        with(QuestTalk) { default(npcTypes, BarbarianNpcs.otto) { talk(it) } }
        onLocOpLabel(locTypes, BarbarianLocs.bed, "Search") { searchBed() }
    }

    private class Option(val text: String, val action: suspend Dialogue.() -> Unit)

    private suspend fun ProtectedAccess.talk(npc: Npc) {
        startDialogue(npc) {
            if (!hasMet()) {
                if (!introduction()) return@startDialogue
            } else {
                chatNpc(happy, "Good day to you, hearty warrior! May the spirits guide you.")
            }
            lessons()
        }
    }

    private fun Dialogue.bit(varbit: VarBitType): Int = player.vars[varbit]

    private fun Dialogue.setBit(varbit: VarBitType, value: Int) {
        VarPlayerIntMapSetter.set(player, varbit, value)
    }

    private fun Dialogue.hasMet(): Boolean =
        bit(BarbarianVarBits.miniquest) > 0 || BarbarianVarBits.lessons.any { bit(it) > 0 }

    /* First conversation */

    /** @return `true` once the player has agreed to train. */
    private suspend fun Dialogue.introduction(): Boolean {
        chatNpc(
            happy,
            "Good day! You seem a hearty warrior. Maybe even some barbarian blood in that body " +
                "of yours?",
        )
        var choice =
            choice4(
                "You think so?",
                1,
                "Who are you and why do you live all the way out here?",
                2,
                "I really don't think I'm related to any barbarian.",
                3,
                "Sorry, I'm too busy to talk about genealogy today.",
                4,
            )
        if (choice == 2) {
            chatPlayer(quiz, "Who are you and why do you live all the way out here?")
            chatNpc(
                neutral,
                "Me? I am Otto, known as the Godblessed. I follow the ways of our barbarian " +
                    "ancestors, communing with the spirits of the dead and teaching their ways to " +
                    "worthy disciples.",
            )
            chatNpc(
                neutral,
                "Perhaps you could be one of them. You look a likely sort to learn of these ways. " +
                    "As I say, you might even have a bit of barbarian blood hidden away in you.",
            )
            choice = 1
        }
        when (choice) {
            3 -> {
                chatPlayer(neutral, "I really don't think I'm related to any barbarian.")
                chatNpc(neutral, "Your scepticism will be your loss.")
                return false
            }
            4 -> {
                chatPlayer(neutral, "Sorry, I'm too busy to talk about genealogy today.")
                chatNpc(neutral, "Well then, we will talk when you learn to be less impetuous.")
                return false
            }
        }
        chatPlayer(quiz, "You think so?")
        chatNpc(
            neutral,
            "Who can tell? My forefathers weren't averse to travelling, so it is possible. They " +
                "tended to cause too much trouble in your so-called civilised lands, however, so " +
                "most returned to their ancestral homes.",
        )
        chatNpc(
            neutral,
            "In any case, given you seem the type, I think you are ready to learn our more secret " +
                "tribal feats for yourself.",
        )
        chatPlayer(happy, "Oh, that sounds interesting. What sort of skills would these be?")
        chatNpc(
            neutral,
            "To begin with, I can supply knowledge in the ways of firemaking, our special rod " +
                "fishing tricks, a selection of spear skills and our agricultural techniques.",
        )
        chatPlayer(quiz, "There are more advanced stages though, going by your description?")
        chatNpc(
            neutral,
            "Your perception is creditable. I can eventually teach of more advanced firemaking " +
                "techniques, and the rod fishing skills are but a preliminary to our special " +
                "potions and brews.",
        )
        chatNpc(neutral, "These secrets must, however, wait until you have mastered the basics.")
        if (bit(BarbarianVarBits.miniquest) == 0) setBit(BarbarianVarBits.miniquest, 1)
        return true
    }

    /* The lesson menu */

    private suspend fun Dialogue.lessons() {
        while (true) {
            val options = lessonOptions()
            if (options.isEmpty()) {
                chatNpc(
                    happy,
                    "You have learned all of the ways I can teach you for now. May the spirits " +
                        "guide you.",
                ) // Authored (not Wiki)
                return
            }
            val picked = menu(options.map { it.text } + GOODBYE)
            val option = options.getOrNull(picked)
            if (option == null) {
                chatPlayer(neutral, GOODBYE)
                return
            }
            option.action(this)
            val more =
                choice2("I seek more knowledge.", true, "I have no more questions at this time.", false)
            if (!more) {
                chatPlayer(neutral, "I have no more questions at this time.")
                chatNpc(neutral, "In that case, farewell.")
                return
            }
            chatPlayer(quiz, "I seek more knowledge.")
        }
    }

    /** The training options open to the player now, in the transcript's menu order. */
    private fun Dialogue.lessonOptions(): List<Option> {
        val barehanded = bit(BarbarianVarBits.fishing_s)
        val rod = bit(BarbarianVarBits.fishing_r)
        val spear = bit(BarbarianVarBits.smith_spear)
        val hasta = bit(BarbarianVarBits.smith_hasta)
        val herb = bit(BarbarianVarBits.herb_potion)
        return buildList {
            when (barehanded) {
                0,
                1 -> add(Option("Please teach me of your cunning with harpoons.") { harpoons(barehanded) })
                LESSON_DONE -> add(Option("I've fished with my hands!") { fishedWithHands() })
            }
            when (rod) {
                0,
                1 -> add(Option("What can I learn about the use of a fishing rod?") { fishingRod(rod) })
                LESSON_DONE -> add(Option("I've fished with a barbarian rod!") { fishedWithRod() })
            }
            if (barehanded == LESSON_REPORTED && rod == LESSON_REPORTED) {
                if (spear < LESSON_REPORTED) {
                    add(Option("Tell me more about the use of spears.") { spears(spear) })
                } else if (hasta < LESSON_REPORTED) {
                    add(Option("What of the one-handed spears of which you spoke?") { hastae(hasta) })
                }
            }
            if (rod == LESSON_REPORTED && herb < HERB_DONE) {
                if (herb == 1 && hasAttackMix()) {
                    add(Option("I've made a barbarian potion!") { handInMix() })
                } else {
                    add(Option("What was that secret knowledge of herblore we talked of?") { herblore(herb) })
                }
            }
        }
    }

    /** A choice of up to 5 options; longer lists page with "More...". Returns the index picked. */
    private suspend fun Dialogue.menu(labels: List<String>): Int {
        var start = 0
        while (true) {
            val rest = labels.size - start
            val page = if (rest <= MAX_CHOICES) labels.drop(start) else labels.subList(start, start + MAX_CHOICES - 1) + MORE
            val picked =
                when (page.size) {
                    1 -> 0
                    2 -> choice2(page[0], 0, page[1], 1)
                    3 -> choice3(page[0], 0, page[1], 1, page[2], 2)
                    4 -> choice4(page[0], 0, page[1], 1, page[2], 2, page[3], 3)
                    else -> choice5(page[0], 0, page[1], 1, page[2], 2, page[3], 3, page[4], 4)
                }
            if (page[picked] == MORE && rest > MAX_CHOICES) {
                start += MAX_CHOICES - 1
                continue
            }
            return start + picked
        }
    }

    /* Fishing */

    private suspend fun Dialogue.harpoons(state: Int) {
        chatPlayer(quiz, "Please teach me of your cunning with harpoons.")
        if (state == 0) {
            chatNpc(
                neutral,
                "To learn more of such as tool, you must first focus your studies on those fish " +
                    "that the tool is used to catch.",
            )
            chatPlayer(confused, "What do you mean?")
            chatNpc(
                neutral,
                "You must catch fish which are usually harpooned, without a harpoon. You will be " +
                    "using your skill and strength.",
            )
            chatPlayer(quiz, "How do you expect me to do that?")
            chatNpc(
                neutral,
                "Use your arm as bait. Wriggle your fingers as if they are a tasty snack and " +
                    "hungry tuna and swordfish will throng to be caught by you.",
            )
            chatPlayer(
                worried,
                "That sounds rather insanely dangerous. I'm glad you didn't mention shark too.",
            )
            chatNpc(
                neutral,
                "Oh, my mind slipped for a moment, this method does indeed work with shark - " +
                    "though in this case the action must be more of a frenzied thrashing of the " +
                    "arm than a wriggle.",
            )
            chatPlayer(sad, "... and I thought fishing was a safe way to pass the time.")
            setBit(BarbarianVarBits.fishing_s, LESSON_TAUGHT)
            return
        }
        chatNpc(neutral, "I see you need encouragement in learning the ways of fishing without a harpoon.")
        chatPlayer(worried, "It's not the most safe-sounding task I have ever been given.")
        chatNpc(
            neutral,
            "Neither is it the most deadly. Go now and return when you have drawn a fish from the " +
                "sea, with sheer strength and knowledge of our scaled prey.",
        )
    }

    private suspend fun Dialogue.fishedWithHands() {
        chatPlayer(happy, "I've fished with my hands!")
        chatNpc(neutral, "I sense you have more understanding of harpoons through your studies.")
        chatPlayer(quiz, "Well it was interesting, can I move on to more sane hobbies now?")
        chatNpc(
            neutral,
            "Nothing is sane when one is born knowing only certain death. The spirits approve of " +
                "you however, so death is on your side.",
        )
        chatPlayer(confused, "What is that supposed to mean?")
        chatNpc(
            neutral,
            "I mean that when you eventually die and find peace, at least the spirits you " +
                "encounter will be your friends. Alas for you adventurous sort, the natural ways " +
                "of passing are close to impossible.",
        )
        setBit(BarbarianVarBits.fishing_s, LESSON_REPORTED)
    }

    private suspend fun Dialogue.fishingRod(state: Int) {
        chatPlayer(quiz, "What can I learn about the use of a fishing rod?")
        if (state == 0) {
            chatNpc(
                neutral,
                "While you civilised folk use small, weak fishing rods, we barbarians are skilled " +
                    "with heavier tackle. We fish in the lake nearby.",
            )
            chatPlayer(quiz, "So can you teach me of your methods?")
            chatNpc(
                neutral,
                "Certainly. Take the rod from under my bed and fish in the lake. When you have " +
                    "caught a few fish, I am sure you will be ready to talk more with me.",
            )
            chatNpc(neutral, "You will know when you are ready, since inspiration will fill your mind.")
            chatPlayer(quiz, "So I can obtain new foods from these fishing spots?")
            chatNpc(
                neutral,
                "We do not use these fish quite as you might expect. When you return we can speak " +
                    "more of this matter.",
            )
            setBit(BarbarianVarBits.fishing_r, LESSON_TAUGHT)
            return
        }
        chatNpc(
            neutral,
            "Alas, I do not sense that you have been successful in your fishing yet. The look in " +
                "your eyes is not that of the osprey.",
        )
        chatPlayer(confused, "Osprey?")
        chatNpc(
            neutral,
            "Legendary birds, which the ignorant call eagles. They prey upon fish. Do as they do " +
                "to gain inspiration.",
        )
    }

    private suspend fun Dialogue.fishedWithRod() {
        chatPlayer(happy, "I've fished with a barbarian rod!")
        chatNpc(neutral, "Your mind is as clear as the waters you have fished. This is good.")
        chatPlayer(
            confused,
            "So what do I do with these fish? They are all bloated and so nasty looking that even " +
                "a starving vulture wouldn't eat one.",
        )
        chatNpc(
            neutral,
            "Patience young one. These are fish which are fat with eggs rather than fat of flesh. " +
                "It is these eggs that are the thing to make use of.",
        )
        setBit(BarbarianVarBits.fishing_r, LESSON_REPORTED)
    }

    /* Smithing */

    private suspend fun Dialogue.spears(state: Int) {
        chatPlayer(quiz, "Tell me more about the use of spears.")
        when (state) {
            0 -> {
                chatNpc(
                    neutral,
                    "Many warriors complain that spears are difficult to find, we barbarians thus " +
                        "forge our own.",
                )
                chatPlayer(
                    quiz,
                    "Can I just use some metal on an anvil, or is there something else involved?",
                )
                chatNpc(
                    neutral,
                    "If you use our special barbarian anvil here, you will find it ideal. Other " +
                        "anvils are not sturdy enough or shaped appropriately for the forging " +
                        "work involved. Make any of our spears and return.",
                )
                chatNpc(
                    neutral,
                    "Note well that you will require wood for the spear shafts; the quality of " +
                        "wood must be similar to that of the metal involved.",
                )
                setBit(BarbarianVarBits.smith_spear, LESSON_TAUGHT)
            }
            LESSON_TAUGHT -> {
                chatNpc(
                    neutral,
                    "You do not exude the presence of one who has poured his soul into " +
                        "manufacturing spears.",
                )
                chatPlayer(quiz, "The spirits told you?")
                chatNpc(neutral, "Indeed they did. There are no secrets from those who live eternally.")
            }
            else -> {
                chatNpc(
                    neutral,
                    "The manufacture of spears is now yours as a speciality. Use your skill well. " +
                        "In addition I am ready to reveal one-handed spear crafts.",
                )
                chatPlayer(happy, "This sounds interesting indeed.")
                setBit(BarbarianVarBits.smith_spear, LESSON_REPORTED)
            }
        }
    }

    private suspend fun Dialogue.hastae(state: Int) {
        chatPlayer(quiz, "What of the one handed spears of which you spoke?")
        when (state) {
            0 -> {
                chatNpc(
                    neutral,
                    "The next step is to manufacture a one-handed version of a spear, suitable " +
                        "for combat. Such a spear is known to us as a hasta.",
                )
                chatPlayer(quiz, "Can't I just pick one up and use it in one hand?")
                chatNpc(
                    neutral,
                    "As you might suspect, our ways require greater understanding than is gained " +
                        "by simply looking at a weapon. It is also the case that the process " +
                        "involves a differently",
                )
                chatNpc(
                    neutral,
                    "balanced spear. Before you may use such a weapon in anger, you must make an " +
                        "example. Only then will you fully understand the poise and techniques " +
                        "involved.",
                )
                chatPlayer(neutral, "So I must manufacture one? I see. This makes sense.")
                chatNpc(
                    neutral,
                    "Indeed. You may use our special anvil for this spear type too. The ways of " +
                        "black and dragon spears are beyond our knowledge, however; these spears " +
                        "may not be created.",
                )
                setBit(BarbarianVarBits.smith_hasta, LESSON_TAUGHT)
            }
            LESSON_TAUGHT -> {
                chatNpc(
                    neutral,
                    "Take some wood and metal and make a spear upon the nearby anvil, then you " +
                        "may return to me. As an example, you may use bronze bars with normal logs " +
                        "or iron bars with oak logs. If you perform this task I will",
                )
                chatNpc(neutral, "be freed from the spirits complaining of your lack of dedication.")
                chatPlayer(neutral, "I'll be back.")
            }
            else -> {
                chatNpc(
                    neutral,
                    "I see you have constructed your hasta, and are approaching readiness.",
                )
                chatPlayer(quiz, "If I may ask, readiness for what?")
                chatNpc(
                    neutral,
                    "To live life to it's fullest of course - that you may be a peaceful spirit " +
                        "when your time ends.",
                )
                setBit(BarbarianVarBits.smith_hasta, LESSON_REPORTED)
            }
        }
    }

    /* Herblore */

    private fun Dialogue.hasAttackMix(): Boolean =
        BarbarianObjs.attack_mixes.any { access.invTotal(access.inv, it) > 0 }

    private suspend fun Dialogue.herblore(state: Int) {
        chatPlayer(quiz, "What was that secret knowledge of herblore we talked of?")
        if (state == 0) {
            chatNpc(
                neutral,
                "The secret is linked to the fish in the lake nearby. If you use your knife upon " +
                    "them, several new fishy items will be produced. Fish parts can be used as " +
                    "bait, but the roe and caviar is more useful for us.",
            )
            chatPlayer(quiz, "Do I cook them?")
            chatNpc(
                neutral,
                "Certainly not. Mixing these items with two dose potions is what should be done. " +
                    "This results in a nutritious slop, perfect for healing as well as imparting " +
                    "the effect of the potion.",
            )
            chatNpc(
                neutral,
                "Roe can only be used for some of the more easily combined mixes, while caviar may " +
                    "be used for any of the mixes of which I am aware.",
            )
            chatPlayer(quiz, "What if I only have four dose potions?")
            chatNpc(
                neutral,
                "You are able to decant a four dose potion into an empty vial, thus giving two " +
                    "potions of two doses. This will aid you in the process.",
            )
            chatPlayer(quiz, "I guess you just want me to go away and make one of these potions?")
            chatNpc(
                neutral,
                "Have I become so predictable? But yes, I do indeed require a potion. It is of the " +
                    "highest importance that you bring me a lesser attack potion combined with " +
                    "fish roe.",
            )
            setBit(BarbarianVarBits.herb_potion, 1)
            return
        }
        chatNpc(neutral, "Do you have my potion?")
        chatPlayer(quiz, "What was it you needed again?")
        chatNpc(
            neutral,
            "Bring me a lesser attack potion combined with fish roe. There is more importance in " +
                "this than you will ever know.",
        )
    }

    private suspend fun Dialogue.handInMix() {
        val mix = BarbarianObjs.attack_mixes.firstOrNull { access.invTotal(access.inv, it) > 0 } ?: return
        chatPlayer(happy, "I've made a barbarian potion!")
        access.invDel(access.inv, mix)
        chatNpc(
            neutral,
            "I will take that off your hands now. I will say no more than that I am eternally " +
                "grateful.",
        )
        chatPlayer(
            confused,
            "I feel I am missing some vital information about your need for this potion, though " +
                "I often have this suspicion.",
        )
        chatNpc(
            neutral,
            "I will not reveal all of my private matters to you. Some secrets are best kept " +
                "rather than revealed.",
        )
        setBit(BarbarianVarBits.herb_potion, HERB_DONE)
    }

    /* Otto's bed */

    private fun ProtectedAccess.searchBed() {
        if (player.vars[BarbarianVarBits.fishing_r] < LESSON_TAUGHT) {
            mes("You search the bed but find nothing of interest.") // Authored (not Wiki)
            return
        }
        if (invTotal(inv, BarbarianObjs.barbarian_rod) > 0) {
            mes("You already have a barbarian rod.") // Authored (not Wiki)
            return
        }
        if (inv.freeSpace() == 0) {
            mes("You don't have enough inventory space to take the rod.") // Authored (not Wiki)
            return
        }
        invAdd(inv, BarbarianObjs.barbarian_rod)
        mes("You find a heavy fishing rod under the bed.") // Authored (not Wiki)
    }

    private companion object {
        const val LESSON_TAUGHT = 1
        const val LESSON_DONE = 2
        const val LESSON_REPORTED = 3
        const val HERB_DONE = 2
        const val MAX_CHOICES = 5
        const val GOODBYE = "I'd better get going."
        const val MORE = "More..." // Authored (not Wiki)
    }
}

internal object BarbarianNpcs : NpcReferences() {
    val otto = find("brut_otto")
}

internal object BarbarianLocs : LocReferences() {
    val bed = find("brut_barbarian_bed")
}

internal object BarbarianObjs : ObjReferences() {
    val barbarian_rod = find("brut_fishing_rod")
    val attack_mixes = listOf(find("brutal_2dose1attack"), find("brutal_1dose1attack"))
}

internal object BarbarianVarBits : VarBitReferences() {
    val fishing_s = find("brut_fishing_s")
    val fishing_r = find("brut_fishing_r")
    val herb_potion = find("brut_herb_potion")
    val smith_spear = find("brut_smith_spear")
    val smith_hasta = find("brut_smith_hasta")
    val miniquest = find("brut_miniquest")

    /** Every lesson varbit, Firemaking's included (any progress means Otto has met the player). */
    val lessons = listOf(fishing_s, fishing_r, find("brut_fire"), herb_potion, smith_spear, smith_hasta)
}
