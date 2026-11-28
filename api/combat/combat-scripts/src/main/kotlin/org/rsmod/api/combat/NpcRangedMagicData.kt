package org.rsmod.api.combat

import org.rsmod.api.type.editors.npc.NpcEditor
import org.rsmod.api.type.refs.npc.NpcReferences
import org.rsmod.game.type.npc.NpcType

/**
 * Ranged and magic npc attackers (`npc_attacks.tsv`, generated from the OSRS Wiki infobox_monster
 * attack style / max hit / attack speed by tools/combat/npc_attacks_to_tsv.py). Npcs that also
 * melee keep their melee attack and are not listed.
 */
internal object NpcRangedMagicData {
    enum class Style {
        Ranged,
        Magic,
    }

    class Attack(val npc: String, val style: Style, val maxHit: Int, val speed: Int)

    val attacks: List<Attack> by lazy {
        val input =
            checkNotNull(NpcRangedMagicData::class.java.getResourceAsStream("npc_attacks.tsv")) {
                "npc_attacks.tsv not found"
            }
        input.bufferedReader().use { it.readLines() }
            .filter { it.startsWith("attack\t") }
            .map { line ->
                val p = line.split('\t')
                val style = if (p[2] == "magic") Style.Magic else Style.Ranged
                Attack(p[1], style, p[3].toInt(), p[4].toInt())
            }
    }

    /** OSRS npc attack ranges: most ranged attackers 7 tiles, casters 10. */
    const val RANGED_ATTACK_RANGE: Int = 7
    const val MAGIC_ATTACK_RANGE: Int = 10
}

internal object NpcRangedMagicNpcs : NpcReferences() {
    val byName: Map<String, NpcType> =
        NpcRangedMagicData.attacks.associate { it.npc to find(it.npc) }
}

/**
 * Gives ranged/magic attackers their attack range, so the npc combat mode approaches the target
 * to within range (see AiPlayerModeProcessor) instead of walking up to it. None of these npc
 * types is referenced with a hashed `find`, so their identity hash change is safe.
 */
internal object NpcRangedMagicEditor : NpcEditor() {
    init {
        for (attack in NpcRangedMagicData.attacks) {
            val type = NpcRangedMagicNpcs.byName.getValue(attack.npc)
            edit(type) {
                attackRange =
                    when (attack.style) {
                        NpcRangedMagicData.Style.Ranged -> NpcRangedMagicData.RANGED_ATTACK_RANGE
                        NpcRangedMagicData.Style.Magic -> NpcRangedMagicData.MAGIC_ATTACK_RANGE
                    }
            }
        }
    }
}
