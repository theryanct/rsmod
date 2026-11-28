package org.rsmod.content.quests.framework

import jakarta.inject.Inject
import org.rsmod.api.config.refs.modlevels
import org.rsmod.api.config.refs.stats
import org.rsmod.api.invtx.invAddOrDrop
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.stat.statAdvance
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onCommand
import org.rsmod.api.type.refs.obj.ObjReferences
import org.rsmod.game.cheat.Cheat
import org.rsmod.game.entity.Player
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.stat.StatType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Admin quest commands (Ryan's request):
 * - `::questcomplete <quest|all> [norewards]` sets each matched [BuiltQuests] quest to its end
 *   state, adds its quest points and (unless `norewards`) grants the rewards its [QuestDef.rewards]
 *   lines describe: "N <Skill> XP", "N Coins" and items named by their cache name ("2 Gold bars",
 *   "Gnome amulet"). Lines that aren't one of those (unlocks, kitten colours, ...) are listed. A
 *   single quest then shows the real quest complete scroll and chat line; `all` only a summary.
 * - `::questreset <quest>` sets it back to unstarted (taking its quest points back if it was
 *   complete). Quest-specific server progress varps are left as they are.
 * - `::queststate <quest>` shows the current state.
 *
 * Names match loosely: case, spaces and punctuation are ignored, and a unique prefix or
 * substring is enough ("cooks", "dragonslayer", "rune myst"). Only built quests can be set: the
 * cache's other quest rows don't say which var holds their progress. The quest tab updates from
 * the varps as they change.
 */
public class QuestCommands
@Inject
constructor(
    private val objTypes: ObjTypeList,
    private val objRepo: ObjRepository,
    private val protectedAccess: ProtectedAccessLauncher,
) : PluginScript() {
    override fun ScriptContext.startup() {
        command("questcomplete", "Complete quests: ::questcomplete name|all [norewards]") {
            complete(this)
        }
        command("questreset", "Reset a quest to unstarted: ::questreset name") { reset(this) }
        command("queststate", "Show a quest's state: ::queststate name") { state(this) }
    }

    private fun ScriptContext.command(name: String, desc: String, action: Cheat.() -> Unit) {
        onCommand(name) {
            this.modLevel = modlevels.admin
            this.desc = desc
            this.cheat(action)
        }
    }

    private fun complete(cheat: Cheat) {
        val player = cheat.player
        val args = cheat.args.filter { !it.equals(NO_REWARDS, ignoreCase = true) }
        val rewards = cheat.args.none { it.equals(NO_REWARDS, ignoreCase = true) }
        val query = args.joinToString(" ")
        val quests =
            if (query.equals("all", ignoreCase = true)) {
                BuiltQuests.all
            } else {
                listOfNotNull(find(player, query))
            }
        var done = 0
        for (quest in quests) {
            if (!player.grantQuest(quest)) {
                if (quests.size == 1) player.mes("${quest.name} is already complete.")
                continue
            }
            done++
            if (rewards) grantRewards(player, quest)
            if (quests.size == 1) {
                // A single quest shows the real scroll and chat line; `all` stays a summary.
                val shown = protectedAccess.launch(player) { showQuestComplete(quest) }
                if (!shown) player.mes("Completed ${quest.name} (scroll skipped: you're busy).")
                return
            }
        }
        if (quests.isNotEmpty()) {
            player.mes("Completed $done quest(s)${if (rewards) "" else " (no rewards)"}.")
        }
    }

    private fun reset(cheat: Cheat) {
        val player = cheat.player
        val quest = find(player, cheat.args.joinToString(" ")) ?: return
        player.setQuestState(quest, 0)
        player.syncQuestPoints()
        player.mes("${quest.name} has been reset.")
    }

    private fun state(cheat: Cheat) {
        val player = cheat.player
        val quest = find(player, cheat.args.joinToString(" ")) ?: return
        val state = player.questState(quest)
        val status =
            when {
                state == 0 -> "not started"
                state >= quest.complete -> "complete"
                else -> "in progress"
            }
        player.mes("${quest.name}: state $state of ${quest.complete} ($status).")
    }

    /** The one built quest [query] names, or null (and a message listing the candidates). */
    private fun find(player: Player, query: String): QuestDef? {
        val key = normalise(query)
        if (key.isEmpty()) {
            player.mes("Name a quest, e.g. ::queststate cooks assistant")
            return null
        }
        val all = BuiltQuests.all
        val exact = all.filter { normalise(it.name) == key }
        val matches =
            exact.ifEmpty { all.filter { normalise(it.name).startsWith(key) } }
                .ifEmpty { all.filter { key in normalise(it.name) } }
        return when (matches.size) {
            1 -> matches.single()
            0 -> {
                player.mes("No built quest matches \"$query\".")
                null
            }
            else -> {
                player.mes("\"$query\" matches: ${matches.joinToString { it.name }}")
                null
            }
        }
    }

    private fun grantRewards(player: Player, quest: QuestDef) {
        val skipped = mutableListOf<String>()
        for (line in quest.rewards) {
            if (!grantLine(player, line)) skipped += line
        }
        if (skipped.isNotEmpty()) {
            player.mes("${quest.name}: not granted: ${skipped.joinToString()}")
        }
    }

    private fun grantLine(player: Player, line: String): Boolean {
        XP_LINE.matchEntire(line)?.let { m ->
            val stat = STATS[m.groupValues[2].lowercase()] ?: return false
            player.statAdvance(stat(), amount(m.groupValues[1]).toDouble())
            return true
        }
        COUNT_LINE.matchEntire(line)?.let { m ->
            val count = if (m.groupValues[1].isEmpty()) 1 else amount(m.groupValues[1])
            val name = m.groupValues[2]
            if (name.equals("Coins", ignoreCase = true)) {
                player.invAddOrDrop(objRepo, QuestCmdObjs.coins, count)
                return true
            }
            val obj = objByName(name) ?: return false
            player.invAddOrDrop(objRepo, obj, count)
            return true
        }
        return false
    }

    private fun objByName(name: String): ObjType? {
        val wanted =
            listOf(name, name.removeSuffix("s"), name.removeSuffix("es")).map { it.lowercase() }
        for (candidate in wanted) {
            val match =
                objTypes.values.firstOrNull {
                    it.lowercaseName == candidate && !it.isCert && !it.isPlaceholder
                }
            if (match != null) return match
        }
        return null
    }

    private fun amount(text: String): Int = text.replace(",", "").toInt()

    private fun normalise(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    private companion object {
        const val NO_REWARDS = "norewards"
        val XP_LINE = Regex("""([\d,]+) ([A-Za-z]+) XP""")
        val COUNT_LINE = Regex("""(?:([\d,]+) )?([A-Za-z'][A-Za-z' ()-]*)""")

        val STATS: Map<String, () -> StatType> =
            mapOf(
                "attack" to { stats.attack },
                "defence" to { stats.defence },
                "strength" to { stats.strength },
                "hitpoints" to { stats.hitpoints },
                "ranged" to { stats.ranged },
                "prayer" to { stats.prayer },
                "magic" to { stats.magic },
                "cooking" to { stats.cooking },
                "woodcutting" to { stats.woodcutting },
                "fletching" to { stats.fletching },
                "fishing" to { stats.fishing },
                "firemaking" to { stats.firemaking },
                "crafting" to { stats.crafting },
                "smithing" to { stats.smithing },
                "mining" to { stats.mining },
                "herblore" to { stats.herblore },
                "agility" to { stats.agility },
                "thieving" to { stats.thieving },
                "slayer" to { stats.slayer },
                "farming" to { stats.farming },
                "runecraft" to { stats.runecrafting },
                "runecrafting" to { stats.runecrafting },
                "hunter" to { stats.hunter },
                "construction" to { stats.construction },
            )
    }
}

internal object QuestCmdObjs : ObjReferences() {
    val coins = find("coins")
}
