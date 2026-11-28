package org.rsmod.content.other.gallery

import jakarta.inject.Inject
import java.util.WeakHashMap
import org.rsmod.api.config.refs.modlevels
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onCommand
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.content.skills.magic.teleports.HomeTeleportGallery
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.npc.NpcMode
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.loc.LocShape
import org.rsmod.game.obj.Obj
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Preview gallery for the unused-asset catalogue (tools/catalogue, the TSVs in this module's
 * resources): an empty map square where a page of unused npcs, locs or objs is spawned in a grid.
 *
 * `::gallery` (or Home Teleport's right-click "Gallery") goes there, free, remembering where you
 * were (varp gallery_return, so it survives a logout); `::gallery npcs|locs|objs [page]`,
 * `next`, `prev`, `find <text>` page through; `::gallery exit` takes you back. Everything a page
 * spawns is temporary and removed on page change, exit and logout.
 */
class GalleryScript
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val npcRepo: NpcRepository,
    private val locRepo: LocRepository,
    private val objRepo: ObjRepository,
    private val protectedAccess: ProtectedAccessLauncher,
) : PluginScript() {
    private class Entry(val id: Int, val symbol: String, val name: String)

    private enum class Kind(val file: String, val columns: Int, val spacing: Int) {
        Npcs("npcs.tsv", 5, 6),
        Locs("locs.tsv", 5, 6),
        Objs("objs.tsv", 7, 2),
    }

    private val catalogue: Map<Kind, List<Entry>> by lazy {
        Kind.entries.associateWith { kind ->
            val input = GalleryScript::class.java.getResourceAsStream(kind.file)
            input?.bufferedReader()?.use { reader ->
                reader.readLines().mapNotNull { line ->
                    if (line.startsWith("#")) {
                        return@mapNotNull null
                    }
                    val parts = line.split('\t')
                    val id = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                    Entry(id, parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
                }
            } ?: emptyList()
        }
    }

    /** What one player's gallery has spawned, and where they are in it. */
    private class View(var kind: Kind, var page: Int) {
        val npcs = ArrayList<Npc>()
        val locs = ArrayList<LocInfo>()
        val objs = ArrayList<Obj>()
    }

    private val views = WeakHashMap<Player, View>()

    override fun ScriptContext.startup() {
        onCommand("gallery") {
            modLevel = modlevels.admin
            desc = "Unused-asset gallery: ::gallery [npcs|locs|objs [page]|next|prev|find <text>|exit]"
            cheat { player.gallery(args) }
        }
        onPlayerLogout { player.clearPage() }
        HomeTeleportGallery.open = { player -> player.gallery(emptyList()) }
    }

    private fun Player.gallery(args: List<String>) {
        val view = views[this]
        when (args.firstOrNull()?.lowercase()) {
            null -> if (view == null) enter() else help(view)
            "exit", "leave" -> leave()
            "next" -> view?.let { show(it.kind, it.page + 1) } ?: enter()
            "prev", "back" -> view?.let { show(it.kind, it.page - 1) } ?: enter()
            "find" -> find(args.drop(1).joinToString(" "))
            else -> {
                val kind = kindOf(args[0]) ?: return mes(USAGE)
                val page = args.getOrNull(1)?.toIntOrNull()?.minus(1) ?: 0
                if (view == null) {
                    enter(kind, page)
                } else {
                    show(kind, page)
                }
            }
        }
    }

    private fun kindOf(arg: String): Kind? =
        when (arg.lowercase()) {
            "npc", "npcs" -> Kind.Npcs
            "loc", "locs" -> Kind.Locs
            "obj", "objs", "item", "items" -> Kind.Objs
            else -> null
        }

    private fun Player.enter(kind: Kind = Kind.Npcs, page: Int = 0) {
        val started =
            protectedAccess.launch(this, busyText = "You're busy right now.") {
                if (!player.inGallery()) {
                    VarPlayerIntMapSetter.set(player, GalleryVarps.gallery_return, coords.packed)
                }
                telejump(VIEWER)
            }
        if (started) {
            show(kind, page)
        }
    }

    private fun Player.leave() {
        clearPage()
        views.remove(this)
        val packed = vars[GalleryVarps.gallery_return]
        val back = if (packed != 0) CoordGrid(packed) else HOME
        val started =
            protectedAccess.launch(this, busyText = "You're busy right now.") {
                telejump(back)
                VarPlayerIntMapSetter.set(player, GalleryVarps.gallery_return, 0)
            }
        if (started) {
            mes("You leave the gallery.")
        }
    }

    private fun Player.inGallery(): Boolean =
        coords.level == VIEWER.level && coords.chebyshevDistance(VIEWER) <= GALLERY_RADIUS

    private fun Player.find(text: String) {
        if (text.isBlank()) {
            return mes("Usage: ::gallery find <part of a name or symbol>")
        }
        val needle = text.lowercase()
        for (kind in Kind.entries) {
            val entries = catalogue.getValue(kind)
            val index =
                entries.indexOfFirst {
                    needle in it.name.lowercase() || needle in it.symbol.lowercase()
                }
            if (index >= 0) {
                val page = index / pageSize(kind)
                if (views[this] == null) enter(kind, page) else show(kind, page)
                return
            }
        }
        mes("Nothing in the gallery matches '$text'.")
    }

    private fun pageSize(kind: Kind): Int = kind.columns * kind.columns

    private fun Player.show(kind: Kind, page: Int) {
        val entries = catalogue.getValue(kind)
        if (entries.isEmpty()) {
            return mes("The ${kind.name.lowercase()} catalogue is empty.")
        }
        val pages = (entries.size + pageSize(kind) - 1) / pageSize(kind)
        val shown = page.mod(pages)
        clearPage()
        val view = views.getOrPut(this) { View(kind, shown) }
        view.kind = kind
        view.page = shown
        val slice = entries.drop(shown * pageSize(kind)).take(pageSize(kind))
        for ((index, entry) in slice.withIndex()) {
            val col = index % kind.columns
            val row = index / kind.columns
            val half = kind.columns / 2
            val at = GRID_CENTRE.translate((col - half) * kind.spacing, (half - row) * kind.spacing)
            spawn(view, kind, entry, at)
        }
        mes(
            "Gallery ${kind.name.lowercase()} page ${shown + 1}/$pages " +
                "(${entries.size} unused). ::gallery next / prev / find / exit"
        )
        for (line in slice.chunked(kind.columns)) {
            mes(line.joinToString(", ") { "${it.id} ${it.name}" })
        }
    }

    private fun Player.spawn(view: View, kind: Kind, entry: Entry, at: CoordGrid) {
        when (kind) {
            Kind.Npcs -> {
                val type = npcTypes[entry.id] ?: return
                val npc = Npc(type, at).apply { mode = NpcMode.None }
                npcRepo.add(npc, SPAWN_DURATION)
                view.npcs += npc
            }
            Kind.Locs -> {
                val type = locTypes[entry.id] ?: return
                val loc =
                    locRepo.add(
                        at,
                        type,
                        SPAWN_DURATION,
                        LocAngle.West,
                        LocShape.CentrepieceStraight,
                    )
                view.locs += loc
            }
            Kind.Objs -> {
                val type = objTypes[entry.id] ?: return
                view.objs += objRepo.add(type, at, SPAWN_DURATION, this)
            }
        }
    }

    private fun Player.clearPage() {
        val view = views[this] ?: return
        for (npc in view.npcs) {
            if (npc.isSlotAssigned) {
                npcRepo.del(npc, Int.MAX_VALUE)
            }
        }
        for (loc in view.locs) {
            locRepo.del(loc, Int.MAX_VALUE)
        }
        for (obj in view.objs) {
            objRepo.del(obj, Int.MAX_VALUE)
        }
        view.npcs.clear()
        view.locs.clear()
        view.objs.clear()
    }

    private fun Player.help(view: View) {
        mes("You're in the gallery (${view.kind.name.lowercase()}, page ${view.page + 1}).")
        mes(USAGE)
    }

    private companion object {
        /**
         * Map square 42_74: flat and empty (no locs, no overlays, all walkable), picked by
         * tools/catalogue/build_catalogue.py. The grid sits north of where the viewer stands.
         */
        private val GRID_CENTRE = CoordGrid(2720, 4772, 0)
        private val VIEWER = CoordGrid(2720, 4750, 0)
        private val HOME = CoordGrid(3222, 3218, 0)
        private const val GALLERY_RADIUS = 40
        /** Ticks a spawned exhibit lives if it's never cleared (a crash): one hour. */
        private const val SPAWN_DURATION = 6000
        private const val USAGE =
            "::gallery npcs|locs|objs [page], ::gallery next|prev, ::gallery find <text>, " +
                "::gallery exit"
    }
}

/** Custom varp (ui range): the packed coords to return to from the gallery, 0 = none (65327). */
internal object GalleryVarps : VarpReferences() {
    val gallery_return = find("gallery_return")
}

internal object GalleryVarpBuilder : VarpBuilder() {
    init {
        build("gallery_return")
    }
}
