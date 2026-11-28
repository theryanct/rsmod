package org.rsmod.content.interfaces.toolbelt


/** One belt-able tool: its [group] (one per belt) and [rank] in that group (higher = better). */
internal data class BeltTool(val group: String, val rank: Int)

/** `tool_belt.tsv`: obj id -> [BeltTool]. Unknown names are skipped (logged by the caller). */
internal class ToolBeltTools(val byObj: Map<Int, BeltTool>, val missing: List<String>) {
    companion object {
        /** @param ids obj internal name -> id (the symbol table, `NameMapping.objs`). */
        fun load(ids: Map<String, Int>): ToolBeltTools {
            val input =
                checkNotNull(ToolBeltTools::class.java.getResourceAsStream("tool_belt.tsv")) {
                    "tool_belt.tsv not found"
                }
            val byObj = LinkedHashMap<Int, BeltTool>()
            val missing = mutableListOf<String>()
            val ranks = HashMap<String, Int>()
            input.bufferedReader().use { reader ->
                for (line in reader.readLines()) {
                    if (line.isBlank() || line.startsWith("#")) {
                        continue
                    }
                    val (group, name) = line.split(TAB)
                    val rank = ranks.merge(group, 1) { a, b -> a + b } ?: 1
                    val id = ids[name]
                    if (id == null) {
                        missing += name
                        continue
                    }
                    byObj[id] = BeltTool(group, rank)
                }
            }
            return ToolBeltTools(byObj, missing)
        }

        private const val TAB = '\u0009'
    }
}
