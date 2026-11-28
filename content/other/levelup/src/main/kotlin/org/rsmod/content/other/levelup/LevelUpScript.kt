package org.rsmod.content.other.levelup

import jakarta.inject.Inject
import java.util.WeakHashMap
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.statBase
import org.rsmod.api.script.advanced.onAdvanceStat
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.jingle.JingleReferences
import org.rsmod.api.type.refs.spot.SpotanimReferences
import org.rsmod.game.entity.Player
import org.rsmod.game.type.comp.ComponentType
import org.rsmod.game.type.jingle.JingleType
import org.rsmod.game.type.stat.StatType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Level-up fanfare on a real base-level gain (`AdvanceStat` engine queue; boosts don't trigger
 * it): fireworks spotanim (99 variant at max level), the skill's level-up jingle (OSRS Wiki cache
 * ids), the game message, and the chatbox level-up display with the skill icon.
 *
 * TODO: "(Unlocks)" jingle variants and the combat level-up message.
 */
class LevelUpScript @Inject constructor() : PluginScript() {
    /**
     * Highest level already announced per player and stat. A multi-level jump queues one
     * `AdvanceStat` per level, but each reads the same (final) base level: only the first shows the
     * fanfare, as in OSRS (one dialog with the new level). Weak keys drop players on logout.
     */
    private val announced = WeakHashMap<Player, MutableMap<String, Int>>()

    override fun ScriptContext.startup() {
        onAdvanceStat { levelUp(it.args) }
    }

    private suspend fun ProtectedAccess.levelUp(stat: StatType) {
        val skill = stat.displayName
        val level = player.statBase(stat)
        val key = stat.internalName ?: skill
        val seen = announced.getOrPut(player) { HashMap() }
        if (level <= (seen[key] ?: 0)) {
            return
        }
        seen[key] = level
        val maxed = level >= stat.maxLevel

        spotanim(if (maxed) LevelUpSpots.levelup_99 else LevelUpSpots.levelup)
        jingleFor(stat.internalName, level)?.let(::midiJingle)

        val text1: String
        val text2: String
        if (maxed) {
            mes("Congratulations, you've reached the highest possible $skill level of $level.")
            text1 = "<col=000080>Congratulations, you've just reached the highest possible"
            text2 = "$skill level of $level!"
        } else {
            mes(
                "Congratulations, you've just advanced your $skill level. " +
                    "You are now level $level."
            )
            text1 = "<col=000080>Congratulations, you just advanced ${skill.withArticle()} level."
            text2 = "Your $skill level is now $level."
        }

        val icon = LevelUpIcons.forSkill(stat.internalName)
        levelUpBox(icon, LevelUpIcons.all, text1, text2)
    }

    private fun jingleFor(skill: String?, level: Int): JingleType? =
        when (skill) {
            "hitpoints" ->
                if (level < 50) LevelUpJingles.hitpoints_low else LevelUpJingles.hitpoints_high
            "strength" ->
                if (level < 50) LevelUpJingles.strength_low else LevelUpJingles.strength_high
            "hunter" ->
                if (level % 2 == 0) LevelUpJingles.hunter_even else LevelUpJingles.hunter_odd
            else -> LevelUpJingles.bySkill[skill]
        }

    private fun String.withArticle(): String {
        val article = if (first().lowercaseChar() in "aeiou") "an" else "a"
        return "$article $this"
    }
}

internal object LevelUpSpots : SpotanimReferences() {
    val levelup = find("levelup_anim")
    val levelup_99 = find("levelup_99_anim")
}

/** Level-up jingles; cache ids from the OSRS Wiki jingle pages (`cacheid`). */
internal object LevelUpJingles : JingleReferences() {
    val hitpoints_low = find("levelup_hitpoints_low")
    val hitpoints_high = find("levelup_hitpoints_high")
    val strength_low = find("levelup_strength_low")
    val strength_high = find("levelup_strength_high")
    val hunter_even = find("levelup_hunter_even")
    val hunter_odd = find("levelup_hunter_odd")

    /** Keyed by stat internal name. */
    val bySkill: Map<String, JingleType> =
        mapOf(
            "agility" to find("levelup_agility"),
            "attack" to find("levelup_attack"),
            "construction" to find("levelup_construction"),
            "cooking" to find("levelup_cooking"),
            "crafting" to find("levelup_crafting"),
            "defence" to find("levelup_defence"),
            "farming" to find("levelup_farming"),
            "firemaking" to find("levelup_firemaking"),
            "fishing" to find("levelup_fishing"),
            "fletching" to find("levelup_fletching"),
            "herblore" to find("levelup_herblore"),
            "magic" to find("levelup_magic"),
            "mining" to find("levelup_mining"),
            "prayer" to find("levelup_prayer"),
            "ranged" to find("levelup_ranged"),
            "runecrafting" to find("levelup_runecraft"),
            "sailing" to find("levelup_sailing"),
            "slayer" to find("levelup_slayer"),
            "smithing" to find("levelup_smithing"),
            "thieving" to find("levelup_thieving"),
            "woodcutting" to find("levelup_woodcutting"),
        )
}

/** Skill icon layers in the level-up display (hunter and sailing have no named layer). */
internal object LevelUpIcons : ComponentReferences() {
    private val bySkill: Map<String, ComponentType> =
        mapOf(
            "agility" to find("levelup_display:agility"),
            "attack" to find("levelup_display:attack"),
            "construction" to find("levelup_display:construction"),
            "cooking" to find("levelup_display:cooking"),
            "crafting" to find("levelup_display:crafting"),
            "defence" to find("levelup_display:defence"),
            "farming" to find("levelup_display:farming"),
            "firemaking" to find("levelup_display:firemaking"),
            "fishing" to find("levelup_display:fishing"),
            "fletching" to find("levelup_display:fletching"),
            "herblore" to find("levelup_display:herblore"),
            "hitpoints" to find("levelup_display:hitpoints"),
            "magic" to find("levelup_display:magic"),
            "mining" to find("levelup_display:mining"),
            "prayer" to find("levelup_display:prayer"),
            "ranged" to find("levelup_display:ranging"),
            "runecrafting" to find("levelup_display:runecrafting"),
            "slayer" to find("levelup_display:slayer"),
            "smithing" to find("levelup_display:smithing"),
            "strength" to find("levelup_display:strength"),
            "thieving" to find("levelup_display:thieving"),
            "woodcutting" to find("levelup_display:woodcutting"),
        )

    val all: List<ComponentType> = bySkill.values.toList() + find("levelup_display:combat")

    fun forSkill(skill: String?): ComponentType? = bySkill[skill]
}
