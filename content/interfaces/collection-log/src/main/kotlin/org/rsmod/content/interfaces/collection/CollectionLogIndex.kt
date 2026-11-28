package org.rsmod.content.interfaces.collection

import org.rsmod.game.type.enums.EnumTypeList
import org.rsmod.game.type.struct.StructTypeList

/**
 * The collection log's layout, read from the cache the way the client draws it:
 * - tab structs 471-475: param 684 = tab slot, 683 = enum of category structs, 685-688 = the
 *   list's scroll layer / background / text / scrollbar components;
 * - category structs: param 689 = name, 690 = enum of logged objs;
 * - enum 3721 maps some logged objs to the obj id the client counts instead.
 */
internal class CollectionLogIndex(
    val tabs: List<Tab>,
    /** Any obj id (logged or its 3721 counterpart) -> the id the client counts. */
    val countedId: Map<Int, Int>,
) {
    class Tab(
        val slot: Int,
        val struct: Int,
        /** Params 685-688, passed to client script collection_draw. */
        val components: List<Int>,
        val categories: List<Category>,
    ) {
        /** Unique counted ids in this tab. */
        val items: Set<Int> = categories.flatMapTo(LinkedHashSet()) { it.items }
    }

    class Category(
        val struct: Int,
        val name: String,
        /** Counted ids, in page order. */
        val items: List<Int>,
        /** Its "completed" varbit (colours the name green), if the client has one. */
        val completedVarbit: Int?,
    )

    val allItems: Set<Int> = tabs.flatMapTo(LinkedHashSet()) { it.items }

    companion object {
        private val TAB_STRUCTS = 471..475
        private const val COUNTED_ID_ENUM = 3721

        private const val TAB_CATEGORIES = 683
        private const val TAB_SLOT = 684
        private val TAB_COMPONENTS = 685..688
        private const val CATEGORY_NAME = 689
        private const val CATEGORY_ITEMS = 690

        fun load(enums: EnumTypeList, structs: StructTypeList): CollectionLogIndex {
            val completed = loadCompletionVarbits()
            val counted = enums.intMap(COUNTED_ID_ENUM)
            fun countedId(obj: Int): Int = counted[obj]?.takeIf { it != -1 } ?: obj

            val countedIds = HashMap<Int, Int>()
            val tabs =
                TAB_STRUCTS.mapNotNull { tabStruct ->
                    val params =
                        structs[tabStruct]?.paramMap?.primitiveMap ?: return@mapNotNull null
                    val slot = params[TAB_SLOT] as? Int ?: return@mapNotNull null
                    val listEnum = params[TAB_CATEGORIES] as? Int ?: return@mapNotNull null
                    val components = TAB_COMPONENTS.map { params[it] as? Int ?: -1 }
                    val categories =
                        enums.intMap(listEnum).toSortedMap().values.mapNotNull { struct ->
                            val catParams = structs[struct]?.paramMap?.primitiveMap
                            val name = catParams?.get(CATEGORY_NAME) as? String
                            val itemEnum = catParams?.get(CATEGORY_ITEMS) as? Int
                            if (name == null || itemEnum == null) {
                                return@mapNotNull null
                            }
                            val items =
                                enums.intMap(itemEnum).toSortedMap().values.map { obj ->
                                    val id = countedId(obj)
                                    countedIds[obj] = id
                                    countedIds[id] = id
                                    id
                                }
                            Category(struct, name, items.distinct(), completed[struct])
                        }
                    Tab(slot, tabStruct, components, categories)
                }
            return CollectionLogIndex(tabs.sortedBy { it.slot }, countedIds)
        }

        private fun EnumTypeList.intMap(id: Int): Map<Int, Int> {
            val enum = this[id] ?: return emptyMap()
            val out = HashMap<Int, Int>()
            for ((key, value) in enum.primitiveMap) {
                if (key is Int && value is Int) {
                    out[key] = value
                }
            }
            return out
        }

        private fun loadCompletionVarbits(): Map<Int, Int> {
            val input =
                checkNotNull(
                    CollectionLogIndex::class.java.getResourceAsStream("completion_varbits.tsv")
                ) {
                    "completion_varbits.tsv not found"
                }
            return input.bufferedReader().use { reader ->
                reader
                    .readLines()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .associate { line ->
                        val (struct, varbit) = line.split('\t')
                        struct.toInt() to varbit.toInt()
                    }
            }
        }
    }
}
