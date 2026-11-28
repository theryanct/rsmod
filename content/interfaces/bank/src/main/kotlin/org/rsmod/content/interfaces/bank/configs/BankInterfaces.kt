package org.rsmod.content.interfaces.bank.configs

import org.rsmod.api.type.refs.comp.ComponentReferences
import org.rsmod.api.type.refs.interf.InterfaceReferences

internal typealias bank_interfaces = BankInterfaces

internal typealias bank_components = BankComponents

internal typealias bank_comsubs = BankSubComponents

object BankInterfaces : InterfaceReferences() {
    val tutorial_overlay = find("screenhighlight", 1206696351)
}

object BankComponents : ComponentReferences() {
    val tutorial_button = find("bankmain:bank_tut", 7360148623500133599)
    val capacity_container = find("bankmain:capacity_layer", 5691092119508665688)
    val capacity_text = find("bankmain:capacity", 71329117100551007)
    val main_inventory = find("bankmain:items", 7094555693885599186)
    val tabs = find("bankmain:tabs", 3676966183117485051)
    val incinerator_confirm = find("bankmain:incinerator_confirm", 6103145201068339557)
    val potionstore_items = find("bankmain:potionstore_items", 6360048316318850108)
    val worn_off_stab = find("bankmain:stabatt", 7916396834116848760)
    val worn_off_slash = find("bankmain:slashatt", 2508796343972092671)
    val worn_off_crush = find("bankmain:crushatt", 6324567890682112390)
    val worn_off_magic = find("bankmain:magicatt", 449875981571378208)
    val worn_off_range = find("bankmain:rangeatt", 4265647528281397927)
    val worn_speed_base = find("bankmain:attackspeedbase", 5139901700064036062)
    val worn_speed = find("bankmain:attackspeedactual", 3080981337663321598)
    val worn_def_stab = find("bankmain:stabdef", 5436702211297041935)
    val worn_def_slash = find("bankmain:slashdef", 29101721152285846)
    val worn_def_crush = find("bankmain:crushdef", 3844873267862305565)
    val worn_def_range = find("bankmain:rangedef", 1785952905461591102)
    val worn_def_magic = find("bankmain:magicdef", 7193553395606347191)
    val worn_melee_str = find("bankmain:meleestrength", 2957007588477235110)
    val worn_ranged_str = find("bankmain:rangestrength", 6772779135187254829)
    val worn_magic_dmg = find("bankmain:magicdamage", 1365178645042498740)
    val worn_prayer = find("bankmain:prayer", 5180950191752518459)
    val worn_undead = find("bankmain:typemultiplier", 4293084512367448002)
    val worn_slayer = find("bankmain:slayermultiplier", 8108856059077467721)
    val tutorial_overlay_target = find("bankmain:bank_highlight", 5345750290142623042)
    val confirmation_overlay_target = find("bankmain:popup", 7725483948440596967)
    val tooltip = find("bankmain:tooltip", 4370591860360031630)

    // Rev 243: one Swap/Insert toggle, and Note is a toggle (no separate Item button).
    val rearrange_mode_toggle = find("bankmain:swap_insert")
    val withdraw_mode_note = find("bankmain:note")
    val always_placehold = find("bankmain:placeholder", 1314776946635575895)
    val deposit_inventory = find("bankmain:depositinv", 4181052166315159939)
    val deposit_worn = find("bankmain:depositworn", 6176312401936476794)
    val quantity_1 = find("bankmain:quantity1", 2028749095784448127)
    val quantity_5 = find("bankmain:quantity5", 5597503861291556734)
    val quantity_10 = find("bankmain:quantity10", 530701198982376107)
    val quantity_x = find("bankmain:quantityx", 4314937377474528347)
    val quantity_all = find("bankmain:quantityall", 7260605638689424571)

    val incinerator_toggle = find("bankmain:incinerator_toggle", 152791449389222287)
    val tutorial_button_toggle = find("bankmain:banktut_toggle", 2572133164486258208)
    val inventory_item_options_toggle = find("bankmain:sideops_toggle", 980304221051521837)
    val deposit_inv_toggle = find("bankmain:depositinv_toggle", 8611847314471561275)
    val deposit_worn_toggle = find("bankmain:depositworn_toggle", 8611847314471561276)
    val release_placehold = find("bankmain:release_placeholders", 858253636043190880)
    val bank_fillers_1 = find("bankmain:bank_filler_1", 1405338793908319559)
    val bank_fillers_10 = find("bankmain:bank_filler_10", 3705073302143633260)
    val bank_fillers_50 = find("bankmain:bank_filler_50", 3271681311175106781)
    val bank_fillers_x = find("bankmain:bank_filler_x", 1079924942115649784)
    val bank_fillers_all = find("bankmain:bank_filler_all", 5233713028712513739)
    val bank_fillers_fill = find("bankmain:bank_filler_confirm", 8705589461804834154)
    val bank_tab_display = find("bankmain:dropdown_content", 932684711177491023)

    val side_inventory = find("bankside:items", 1885880344080200061)
    val worn_inventory = find("bankside:wornops", 6203990611586493264)
    val lootingbag_inventory = find("bankside:lootingbag_items", 8800055068705330501)
    val league_inventory = find("bankside:league_secondinv_items", 81253577765913503)
    val bankside_highlight = find("bankside:bankside_highlight", 6202930921141607019)

    val tutorial_close_button = find("screenhighlight:pausebutton", 8373824249352593324)
    val tutorial_next_page = find("screenhighlight:continue", 2368578001968595651)
    val tutorial_prev_page = find("screenhighlight:previous", 7461125518300620858)
}

@Suppress("ConstPropertyName")
object BankSubComponents {
    const val main_tab = 10
    val other_tabs = 11..19

    val tab_extended_slots_offset = 19..28
}
