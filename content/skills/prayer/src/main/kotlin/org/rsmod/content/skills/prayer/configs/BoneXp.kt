package org.rsmod.content.skills.prayer.configs

import org.rsmod.game.type.obj.UnpackedObjType

internal object BoneXp {
    /**
     * Gilded-altar style multiplier for offering bones on any altar. The per-skill Prayer rate
     * (feature toggles, 10x by default) is applied on top in `statAdvance`.
     */
    const val ALTAR_OFFER_MULTIPLIER: Double = 3.5

    fun buryXp(type: UnpackedObjType): Double? = bury[type.name.lowercase()]

    fun scatterXp(type: UnpackedObjType): Double? = scatter[type.name.lowercase()]

    /** Prayer xp per demonic ash when scattered (OSRS Wiki, Nov 2026). Not offerable at altars. */
    private val scatter: Map<String, Double> =
        mapOf(
            "fiendish ashes" to 10.0,
            "vile ashes" to 25.0,
            "malicious ashes" to 65.0,
            "abyssal ashes" to 85.0,
            "infernal ashes" to 110.0,
        )

    /** Prayer xp per bone, keyed by cache display name (OSRS Wiki, Nov 2026). */
    private val bury: Map<String, Double> =
        mapOf(
            "bones" to 4.5,
            "wolf bones" to 4.5,
            "burnt bones" to 4.5,
            "bleached bones" to 4.5,
            "monkey bones" to 5.0,
            "small zombie monkey bones" to 5.0,
            "large zombie monkey bones" to 5.0,
            "bat bones" to 5.3,
            "big bones" to 15.0,
            "jogre bones" to 15.0,
            "burnt jogre bones" to 16.0,
            "small ninja monkey bones" to 16.0,
            "medium ninja monkey bones" to 18.0,
            "gorilla bones" to 18.0,
            "bearded gorilla bones" to 20.0,
            "wyrmling bones" to 21.0,
            "zogre bones" to 22.5,
            "shaikahan bones" to 25.0,
            "babydragon bones" to 30.0,
            "monodon bones" to 35.0,
            "wyrm bones" to 50.0,
            "strykewyrm bones" to 60.0,
            "dragon bones" to 72.0,
            "wyvern bones" to 72.0,
            "drake bones" to 80.0,
            "fayrg bones" to 84.0,
            "lava dragon bones" to 85.0,
            "raurg bones" to 96.0,
            "frost dragon bones" to 100.0,
            "hydra bones" to 110.0,
            "dagannoth bones" to 125.0,
            "ourg bones" to 140.0,
            "superior dragon bones" to 150.0,
        )
}
