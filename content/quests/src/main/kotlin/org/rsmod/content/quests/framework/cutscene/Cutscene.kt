package org.rsmod.content.quests.framework.cutscene

import jakarta.inject.Inject
import org.rsmod.api.player.cinematic.CameraMode
import org.rsmod.api.player.cinematic.Cinematic
import org.rsmod.api.player.cinematic.MinimapState
import org.rsmod.api.player.output.Camera
import org.rsmod.api.player.output.ClientScripts.highlightingOff
import org.rsmod.api.player.output.ClientScripts.highlightingOn
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.region.RegionRepository
import org.rsmod.api.repo.region.RegionStaticTemplate
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.type.builders.varp.VarpBuilder
import org.rsmod.api.type.refs.varp.VarpReferences
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.util.PathingEntityCommon
import org.rsmod.game.region.Region
import org.rsmod.game.type.npc.NpcType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.seq.SeqType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap

/**
 * Quest cutscenes: an instanced copy of an area, the cutscene UI (fade, hidden minimap / toplevel /
 * HUD / entity ops, close camera), scripted npcs, camera moves, and a guaranteed restore.
 *
 * ```
 * cutscenes.play(this, template, returnTo = player.coords) {
 *     val juliet = spawn(npcs.juliet, CoordGrid(3159, 3425, 1))
 *     teleport(CoordGrid(3157, 3427, 1))
 *     camMoveTo(CoordGrid(3155, 3427, 1), height = 400)
 *     camLookAt(CoordGrid(3159, 3425, 1), height = 200)
 *     fadeIn()
 *     juliet.say("Romeo!")
 *     conversation { runQuestSection(juliet, dialogue, "...") }
 * }
 * ```
 * Coords given to the scope are **real** map coords inside the template's copied area; [at] maps
 * them into the instance. The scene runs inside the caller's protected script: a ground click
 * during a dialogue cancels it (RSMod cancels the active coroutine), so advance quest state only
 * after [play] returns `true` and let the trigger replay an aborted scene.
 *
 * Restore (instance npcs, camera, UI, back to `returnTo`) runs in `finally`, so it also covers a
 * cancelled scene and a logout (logout waits for / closes the script). If the server dies mid-scene
 * the server varp `cutscene_return` (packed `returnTo`) brings the player back on login; any login
 * stranded in the instance area without a live region is sent to Lumbridge.
 */
class CutsceneRunner
@Inject
constructor(
    private val regionRepo: RegionRepository,
    private val npcRepo: NpcRepository,
    private val npcTypes: NpcTypeList,
) {
    /**
     * Plays [scene] in a fresh copy of [template] (or on the live map when `null`). Returns `true`
     * if the scene ran to the end.
     */
    suspend fun play(
        access: ProtectedAccess,
        template: RegionStaticTemplate?,
        returnTo: CoordGrid,
        scene: suspend CutsceneScope.() -> Unit,
    ): Boolean {
        access.setUp()
        val region = template?.let { regionRepo.add(it) }
        if (template != null && region == null) {
            access.tearDown(emptyList(), returnTo = null)
            access.mes("Could not create the cutscene area. Try again in a few seconds.")
            return false
        }
        VarPlayerIntMapSetter.set(access.player, CutsceneVarps.cutscene_return, returnTo.packed)
        val scope = CutsceneScope(access, region, npcRepo, npcTypes)
        var finished = false
        try {
            scope.scene()
            finished = true
        } finally {
            access.tearDown(scope.spawned, returnTo)
        }
        return finished
    }

    /* Same order as the canoe cutscene. */
    private suspend fun ProtectedAccess.setUp() {
        fadeOverlay(
            startColour = 0,
            startTransparency = 255,
            endColour = 0,
            endTransparency = 0,
            clientDuration = FADE_CYCLES,
        )
        clearHealthHud()
        delay(2)
        camModeClose()
        hideTopLevel()
        hideEntityOps()
        minimapHideMap()
        hideHealthHud()
        tempDisableAcceptAid()
        closeTopLevelTabsLenient()
        highlightingOff(player)
    }

    /** Never suspends (it runs from `finally`, also after a cancellation). */
    private fun ProtectedAccess.tearDown(spawned: List<Npc>, returnTo: CoordGrid?) {
        for (npc in spawned) {
            if (npc.isSlotAssigned) npcRepo.del(npc, Int.MAX_VALUE)
        }
        resetAnim()
        camReset()
        if (returnTo != null && RegionRegistry.inWorkingArea(player.coords)) {
            telejump(returnTo)
        }
        showEntityOps()
        restoreLastAcceptAid()
        minimapReset()
        camModeReset()
        showTopLevel()
        showHealthHud()
        clearHealthHud()
        openTopLevelTabs()
        highlightingOn(player)
        closeFadeOverlay()
        VarPlayerIntMapSetter.set(player, CutsceneVarps.cutscene_return, 0)
    }

    companion object {
        const val FADE_CYCLES = 50
    }
}

/** What a scene can do. Real map coords in, instance coords out (see [at]). */
class CutsceneScope
internal constructor(
    val access: ProtectedAccess,
    val region: Region?,
    private val npcRepo: NpcRepository,
    private val npcTypes: NpcTypeList,
) {
    internal val spawned = ArrayList<Npc>()

    val player: Player
        get() = access.player

    /** [real] (a coord in the template's copied area) inside this scene's instance. */
    fun at(real: CoordGrid): CoordGrid = region?.normal?.get(real) ?: real

    /** Spawns a scene npc (no wandering; removed when the scene ends). */
    fun spawn(type: NpcType, real: CoordGrid): Npc {
        val npc = Npc(npcTypes[type], at(real))
        npcRepo.add(npc, SCENE_NPC_TICKS)
        npc.noneMode()
        spawned += npc
        return npc
    }

    fun despawn(npc: Npc) {
        if (npc.isSlotAssigned) npcRepo.del(npc, Int.MAX_VALUE)
        spawned -= npc
    }

    suspend fun teleport(real: CoordGrid) {
        access.telejump(at(real))
        // Camera coords are relative to the build area, which only moves after the rebuild.
        access.delay(1)
    }

    suspend fun delay(ticks: Int) = access.delay(ticks)

    fun Npc.walkTo(real: CoordGrid) = walk(at(real))

    fun Npc.play(seq: SeqType) = anim(seq)

    fun Npc.faceTile(real: CoordGrid) = faceSquare(at(real))

    fun playerAnim(seq: SeqType) = access.anim(seq)

    fun camMoveTo(real: CoordGrid, height: Int, rate: Int = CAM_RATE, rate2: Int = CAM_RATE) =
        access.camMoveTo(at(real), height, rate, rate2)

    fun camLookAt(real: CoordGrid, height: Int, rate: Int = CAM_RATE, rate2: Int = CAM_RATE) =
        access.camLookAt(at(real), height, rate, rate2)

    fun camShake(axis: Int, random: Int, amplitude: Int, rate: Int) =
        access.camShake(axis, random, amplitude, rate)

    fun camSmoothReset() = access.camSmoothReset()

    /** Black screen fades away (scene appears). */
    fun fadeIn() = access.fadeOverlay(0, 0, 0, 255, CutsceneRunner.FADE_CYCLES)

    /** Screen fades to black. */
    fun fadeOut() = access.fadeOverlay(0, 255, 0, 0, CutsceneRunner.FADE_CYCLES)

    /** Runs dialogue (e.g. `runQuestSection`) as part of the scene. */
    suspend fun conversation(block: suspend ProtectedAccess.() -> Unit) = access.block()

    private companion object {
        const val SCENE_NPC_TICKS = 1000
        const val CAM_RATE = 100
    }
}

/**
 * Login safety net: a player whose scene never tore down (server stop) or who logs in inside the
 * instance area with no live region is put back with the normal UI.
 */
class CutsceneRestore
@Inject
constructor(
    private val regionRegistry: RegionRegistry,
    private val collision: CollisionFlagMap,
) : PluginScript() {
    override fun ScriptContext.startup() {
        onPlayerLogin { player.restore(eventBus) }
    }

    private fun Player.restore(eventBus: EventBus) {
        val packed = vars[CutsceneVarps.cutscene_return]
        val stranded = RegionRegistry.inWorkingArea(coords) && regionRegistry[coords] == null
        if (packed == 0 && !stranded) return
        val back = if (packed != 0) CoordGrid(packed) else LUMBRIDGE
        Cinematic.setHideEntityOps(this, hide = false)
        Cinematic.restoreAcceptAid(this)
        Cinematic.setMinimapState(this, MinimapState.Normal)
        Cinematic.setCameraMode(this, CameraMode.Normal)
        Cinematic.setHideToplevel(this, hide = false)
        Cinematic.setHideHealthHud(this, hide = false)
        Cinematic.openTopLevelTabs(this, eventBus)
        Cinematic.closeFadeOverlay(this, eventBus)
        highlightingOn(this)
        Camera.camReset(this)
        VarPlayerIntMapSetter.set(this, CutsceneVarps.cutscene_return, 0)
        if (RegionRegistry.inWorkingArea(coords)) {
            PathingEntityCommon.teleport(this, collision, back)
        }
    }

    private companion object {
        val LUMBRIDGE = CoordGrid(3222, 3218, 0)
    }
}

internal object CutsceneVarps : VarpReferences() {
    /** Server varp (65775): packed return coord while a cutscene is running, else 0. */
    val cutscene_return = find("cutscene_return")
}

internal object CutsceneVarpBuilds : VarpBuilder() {
    init {
        build("cutscene_return")
    }
}
