package org.rsmod.content.interfaces.bank.sort

/**
 * Sort categories, in bank tab order: [Combat] is bank tab 1 ... [Misc] is tab 9, and [Main] is
 * the main tab (coins + anything unmatched and tradeable).
 */
enum class SortTab(val key: String) {
    Combat("combat"),
    Tools("tools"),
    Ammo("ammo"),
    Consumables("consumables"),
    Herblore("herblore"),
    Raw("raw"),
    Processed("processed"),
    Farming("farming"),
    Misc("misc"),
    Main("main");

    companion object {
        private val byKey = entries.associateBy(SortTab::key)

        operator fun get(key: String): SortTab =
            byKey[key] ?: throw IllegalArgumentException("Unknown sort tab: $key")
    }
}

/** What the classifier needs to know about an obj (kept free of cache types for unit tests). */
data class SortItem(
    val name: String,
    /** Primary wear slot (cache wearpos1), or -1. */
    val wearpos: Int = -1,
    val stackable: Boolean = false,
    val iops: List<String> = emptyList(),
    /** Can go on the Grand Exchange. */
    val tradeable: Boolean = true,
    /** Highest wear/wield level requirement, 0 if none. */
    val level: Int = 0,
    /** Grand Exchange item set this is a piece of (e.g. "Dharok's armour set"), if any. */
    val setName: String? = null,
) {
    val lowerName: String = name.lowercase()

    /** Name split into lowercase words, for whole-word matching. */
    val words: List<String> = lowerName.split(' ').filter(String::isNotEmpty)
}

/** Parsed `bank_sort_rules.tsv`; see the file header for the format. */
class BankSortRules(val rules: List<Rule>, tiers: List<Tier>) {
    data class Rule(val tab: SortTab, val kind: String, val value: String) {
        private val lowerValue = value.lowercase()
        private val valueWords = lowerValue.split(' ').filter(String::isNotEmpty)

        init {
            require(kind in KINDS) { "Unknown sort rule kind '$kind' (value '$value')." }
        }

        fun matches(item: SortItem): Boolean =
            when (kind) {
                "exact" -> item.lowerName == lowerValue
                "prefix" -> item.lowerName.startsWith(lowerValue)
                "suffix" -> item.lowerName.endsWith(lowerValue)
                "word" -> item.words.containsSequence(valueWords)
                "iop" -> item.iops.any { it.equals(value, ignoreCase = true) }
                "wearpos" -> item.wearpos == WEARPOS_NAMES[lowerValue]
                "stackable-weapon" -> item.stackable && item.wearpos == WEARPOS_WEAPON
                "equipable" -> item.wearpos != -1
                "untradeable" -> !item.tradeable
                "any" -> true
                else -> false
            }
    }

    data class Tier(val level: Int, val words: List<String>)

    /** Longest word sequences first, so "black d'hide" wins over "black". */
    private val tiers = tiers.sortedByDescending { it.words.size }

    data class Match(val tab: SortTab, val ruleIndex: Int)

    data class TierMatch(val level: Int, val key: String)

    fun classify(item: SortItem): Match {
        for ((index, rule) in rules.withIndex()) {
            if (rule.matches(item)) {
                return Match(rule.tab, index)
            }
        }
        return Match(SortTab.Main, rules.size)
    }

    /**
     * @param prefixOnly only match tier words at the start of the name (gear sets: "Rune
     *   platebody" is in the rune set, "Amulet of magic" is not in the magic set).
     */
    fun tier(item: SortItem, prefixOnly: Boolean = false): TierMatch? {
        val tier =
            tiers.firstOrNull {
                if (prefixOnly) {
                    item.words.startsWith(it.words)
                } else {
                    item.words.containsSequence(it.words)
                }
            } ?: return null
        return TierMatch(tier.level, tier.words.joinToString(" "))
    }

    companion object {
        private const val WEARPOS_WEAPON = 3

        val WEARPOS_NAMES: Map<String, Int> =
            mapOf(
                "head" to 0,
                "back" to 1,
                "neck" to 2,
                "weapon" to 3,
                "body" to 4,
                "shield" to 5,
                "legs" to 7,
                "hands" to 9,
                "feet" to 10,
                "ring" to 12,
                "ammo" to 13,
            )

        private val KINDS =
            setOf(
                "exact",
                "prefix",
                "suffix",
                "word",
                "iop",
                "wearpos",
                "stackable-weapon",
                "equipable",
                "untradeable",
                "any",
            )

        fun parse(lines: List<String>): BankSortRules {
            val rules = mutableListOf<Rule>()
            val tiers = mutableListOf<Tier>()
            for ((lineIndex, line) in lines.withIndex()) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue
                }
                val parts = line.split('\t')
                try {
                    when (parts[0]) {
                        "rule" -> {
                            val value = parts.getOrElse(3) { "" }
                            rules += Rule(SortTab[parts[1]], parts[2], value)
                        }
                        "tier" -> {
                            val words = parts[2].lowercase().split(' ').filter(String::isNotEmpty)
                            tiers += Tier(parts[1].toInt(), words)
                        }
                        else -> error("expected 'rule' or 'tier'")
                    }
                } catch (e: RuntimeException) {
                    throw IllegalArgumentException(
                        "bank_sort_rules.tsv line ${lineIndex + 1}: '$line' (${e.message})",
                        e,
                    )
                }
            }
            return BankSortRules(rules, tiers)
        }

        fun load(): BankSortRules {
            val input =
                checkNotNull(BankSortRules::class.java.getResourceAsStream("bank_sort_rules.tsv")) {
                    "bank_sort_rules.tsv not found"
                }
            return parse(input.bufferedReader().use { it.readLines() })
        }

        private fun List<String>.startsWith(seq: List<String>): Boolean =
            seq.isNotEmpty() && seq.size <= size && seq.indices.all { this[it] == seq[it] }

        private fun List<String>.containsSequence(seq: List<String>): Boolean {
            if (seq.isEmpty() || seq.size > size) {
                return false
            }
            for (start in 0..size - seq.size) {
                if (seq.indices.all { this[start + it] == seq[it] }) {
                    return true
                }
            }
            return false
        }
    }
}
