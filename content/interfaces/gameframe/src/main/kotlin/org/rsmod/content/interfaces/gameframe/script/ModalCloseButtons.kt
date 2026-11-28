package org.rsmod.content.interfaces.gameframe.script

import org.rsmod.api.script.onIfModalButton
import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Close buttons of the modals the server opens that transmit their click instead of closing in the
 * client (Ryan: the quest journal's X did nothing; log `UNHANDLED modal-button=questjournal:close`).
 * Each just closes the modal. A modal whose script handles its own close button binds it there and
 * leaves it out of this list (one handler per button).
 */
class ModalCloseButtons : PluginScript() {
    override fun ScriptContext.startup() {
        for (button in ModalCloseComponents.buttons) {
            onIfModalButton(button) { ifClose() }
        }
    }
}

internal object ModalCloseComponents : ComponentReferences() {
    val buttons =
        listOf(
            "questjournal:close",
            "questscroll:close_button",
            "canoe_map_lum:close",
            "glidermap:close",
            "cws_doomsayer:close_button",
            "tgod_translations:close",
            "tgod_garden_1_lake_map:close",
            "tgod_garden_1_plant_diagram:close",
            "tgod_garden_2_forest_map:close",
            "tgod_garden_2_poison_diagram:close",
            "tgod_garden_2_rune_diagram:close",
            "tgod_garden_3_bucket_diagram:close",
            "tgod_garden_3_carving_diagram:close",
            "tgod_garden_3_package_diagram:close",
            "tgod_garden_3_transfer_diagram:close",
            "tgod_garden_4_creature_diagram:close",
            "tgod_garden_4_delivery_diagram:close",
            "tgod_garden_4_light_diagram:close",
            "tgod_garden_4_recycling_diagram:close",
            "tgod_garden_4_rune_diagram:close",
            "wotw_certificate_hosidius:close",
            "wotw_letter_hosidius:close",
            "wotw_letter_hosidius:close_button",
            "wotw_letter_lands_end:close",
            "wotw_letter_lands_end:close_button",
            "wotw_news_lands_end:close",
            "wotw_news_lands_end:close_button",
        ).map { find(it) }
}
