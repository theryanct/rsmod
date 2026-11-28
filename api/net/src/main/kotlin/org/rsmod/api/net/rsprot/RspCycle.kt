package org.rsmod.api.net.rsprot

import net.rsprot.protocol.api.Session
import net.rsprot.protocol.game.outgoing.info.Infos
import net.rsprot.protocol.game.outgoing.info.playerinfo.PlayerAvatarExtendedInfo
import net.rsprot.protocol.game.outgoing.info.playerinfo.PlayerInfo
import net.rsprot.protocol.game.outgoing.map.RebuildLogin
import net.rsprot.protocol.game.outgoing.map.RebuildNormal
import net.rsprot.protocol.game.outgoing.map.RebuildRegion
import net.rsprot.protocol.game.outgoing.map.RebuildRegionV2
import net.rsprot.protocol.game.outgoing.map.RebuildWorldEntityV4
import net.rsprot.protocol.game.outgoing.info.worldentityinfo.WorldEntityAvatar
import net.rsprot.protocol.game.outgoing.info.worldentityinfo.WorldEntityAvatarFactory
import com.github.michaelbull.logging.InlineLogger
import org.rsmod.game.entity.player.PlayerWorldEntity
import net.rsprot.protocol.game.outgoing.map.util.RebuildRegionZone
import org.rsmod.api.config.refs.baseanimsets
import org.rsmod.api.config.refs.params
import org.rsmod.api.player.righthand
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.game.client.ClientCycle
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.util.EntityFaceAngle
import org.rsmod.game.headbar.Headbar
import org.rsmod.game.hit.Hitmark
import org.rsmod.game.movement.MoveSpeed
import org.rsmod.game.region.Region
import org.rsmod.game.region.zone.RegionZoneCopy
import org.rsmod.game.seq.EntitySeq
import org.rsmod.game.spot.EntitySpotanim
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.Wearpos
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey

class RspCycle(
    private val session: Session<Player>,
    private val infos: Infos,
    private val objTypes: ObjTypeList,
    private val regions: RegionRegistry,
    private val worldEntityAvatars: WorldEntityAvatarFactory,
) : ClientCycle {
    private val logger = InlineLogger()

    // Single-player Sailing: at most one world entity avatar - the boat this player stands on.
    private var boatAvatar: WorldEntityAvatar? = null
    private var boatState: PlayerWorldEntity? = null
    private var boatRootBuilt: PlayerWorldEntity? = null

    private var knownCoords: CoordGrid = CoordGrid.ZERO

    private var knownBuildArea: CoordGrid = CoordGrid.NULL

    private var knownCachedSpeed: MoveSpeed = MoveSpeed.Stationary

    private var knownFaceEntity: Int? = -1

    private var knownRegionUid: Int? = null

    private var cachedRegionZoneProvider: RebuildRegionV2.RebuildRegionZoneProvider? = null

    private val playerInfo: PlayerInfo
        get() = infos.playerInfo

    private val playerExtendedInfo: PlayerAvatarExtendedInfo
        get() = playerInfo.avatar.extendedInfo

    private val worldId: Int
        get() = -1

    fun init(player: Player) {
        player.updateCoords()
        player.queueRebuildLogin()
    }

    private fun Player.queueRebuildLogin() {
        // Rev 237+: maps are unencrypted, rebuild messages no longer carry xtea keys.
        val rebuild = RebuildLogin(x shr 3, z shr 3, worldId, playerInfo)
        session.queue(rebuild)
    }

    override fun update(player: Player) {
        player.syncBoatAvatar()
        player.updateMoveSpeed()
        player.updateCoords()
        player.rebuildArea()
        player.applyExactMove()
        player.applyPublicMessage()
        player.applyFacePathingEntity()
        player.applyFaceAngle()
        player.applyAnim()
        player.applySpotanims()
        player.applySay()
        player.applyHeadbars()
        player.applyHitmarks()
        player.syncAppearance(objTypes)
    }

    // Rev 243 info API: one Infos per player builds world entity, player and npc info together
    // (see the rsprot README "Info Packets" guide). We only use the root world (no world
    // entities yet), so active-world updates are not sent.
    override fun flush(player: Player) {
        val packets = infos.getPackets()
        val root = packets.rootWorldInfoPackets
        session.queue(root.activeWorld)
        session.queue(root.npcUpdateOrigin)
        root.worldEntityInfo.getOrNull()?.let(session::queue)
        root.playerInfo.getOrNull()?.let(session::queue)
        root.npcInfo.getOrNull()?.let(session::queue)

        // Rev 243 world entities (rsprot README "Info Packets"). Single-player Sailing: the only
        // world that can be active is this player's own boat, so no observer filtering. Boat
        // worlds have no dynamic zone content in tier 1, so no per-world zone updates are sent.
        val worlds = packets.activeWorlds
        if (worlds.isEmpty()) {
            return
        }
        for (world in worlds) {
            session.queue(world.activeWorld)
            if (world.added) {
                rebuildWorldEntity(player, world.worldId)
            }
            world.npcInfo.getOrNull()?.let { npcInfo ->
                session.queue(world.npcUpdateOrigin)
                session.queue(npcInfo)
            }
        }
        if (packets.removedWorldIndices.isNotEmpty()) {
            logger.info { "[boat] removed world indices=${packets.removedWorldIndices}" }
        }
        // Back to the root world (client-side pathfinding etc. depend on it).
        session.queue(root.activeWorld)
    }

    override fun release() {
        val packets = infos.getPackets()
        val root = packets.rootWorldInfoPackets
        root.worldEntityInfo.getOrNull()?.safeRelease()
        root.playerInfo.getOrNull()?.safeRelease()
        root.npcInfo.getOrNull()?.safeRelease()
        for (world in packets.activeWorlds) {
            world.npcInfo.getOrNull()?.safeRelease()
        }
    }

    /** Allocates / releases the one boat avatar to match [Player.worldEntity]. */
    private fun Player.syncBoatAvatar() {
        val state = worldEntity
        if (state === boatState) {
            if (state != null) {
                // Moving/turning the boat is tier 2; keep the avatar's angle in step anyway.
                boatAvatar?.updateAngle(state.angle)
            }
            return
        }
        boatAvatar?.let { avatar ->
            worldEntityAvatars.release(avatar)
            logger.info { "[boat] released avatar (old index=${boatState?.index})" }
        }
        boatAvatar = null
        boatState = state
        if (state == null) {
            // Back in the root world: force a normal rebuild around the player next update.
            boatRootBuilt = null
            knownBuildArea = CoordGrid.ZERO
            return
        }
        val swZone = state.region.southWestZone
        val root = state.rootCoords
        val fineX = root.x * 128 + 64
        val fineZ = root.z * 128 + 64
        // Argument order per the rsprot README "World Entities" section.
        boatAvatar =
            worldEntityAvatars.alloc(
                state.index,
                state.typeId,
                slotId,
                state.sizeXZones,
                state.sizeZZones,
                swZone.x,
                swZone.z,
                0,
                3,
                fineX,
                fineZ,
                root.level,
                state.activeLevel,
                state.angle,
            )
        logger.info {
            "[boat] alloc avatar index=${state.index} type=${state.typeId} owner=$slotId " +
                "size=${state.sizeXZones}x${state.sizeZZones} zones swZone=(${swZone.x},${swZone.z}) " +
                "regionSw=${state.region.southWest} root=$root fine=($fineX,$fineZ) " +
                "activeLevel=${state.activeLevel} angle=${state.angle} playerCoords=$coords"
        }
    }

    /** Builds the boat's map in the client from its region template (copied zones). */
    private fun rebuildWorldEntity(player: Player, worldId: Int) {
        val state = boatState
        if (state == null) {
            logger.warn { "[boat] world $worldId added but no boat state (player at ${player.coords})" }
            return
        }
        val zones = createRegionZoneProvider(state.region)
        val provider =
            object : RebuildWorldEntityV4.RebuildWorldEntityZoneProvider {
                override fun provide(zoneX: Int, zoneZ: Int, level: Int): RebuildRegionZone? {
                    val zone = zones.provide(zoneX, zoneZ, level)
                    logger.info { "[boat] zone request ($zoneX,$zoneZ,$level) -> $zone" }
                    return zone
                }
            }
        val sw = state.region.southWest
        val rebuild = RebuildWorldEntityV4(sw.x, sw.z, state.sizeXZones, state.sizeZZones, provider)
        logger.info {
            "[boat] RebuildWorldEntityV4 world=$worldId base=(${sw.x},${sw.z}) " +
                "size=${state.sizeXZones}x${state.sizeZZones} zones=${rebuild.zones}"
        }
        session.queue(rebuild)
    }

    private fun Player.updateMoveSpeed() {
        if (knownCachedSpeed != cachedMoveSpeed) {
            val extendedInfo = playerInfo.avatar.extendedInfo
            extendedInfo.setMoveSpeed(cachedMoveSpeed.steps)
            knownCachedSpeed = cachedMoveSpeed
        }
        val moveSpeed = resolvePendingMoveSpeed()
        if (moveSpeed != cachedMoveSpeed && coords != knownCoords) {
            val extendedInfo = playerInfo.avatar.extendedInfo
            extendedInfo.setTempMoveSpeed(moveSpeed.steps)
        }
    }

    private fun Player.resolvePendingMoveSpeed(): MoveSpeed =
        when {
            pendingTelejump -> MoveSpeed.Stationary
            pendingTeleport -> MoveSpeed.Walk
            pendingStepCount == 1 -> MoveSpeed.Walk
            pendingStepCount == 2 -> MoveSpeed.Run
            else -> moveSpeed
        }

    private fun Player.updateCoords() {
        infos.updateRootCoord(level, x, z)
        knownCoords = coords
    }

    private fun Player.rebuildArea() {
        val boat = worldEntity
        if (boat != null) {
            // On a boat the player's coords are in the boat's instanced map, but the root world
            // the client shows is wherever the boat sits: rebuild the root around the boat once.
            if (boatRootBuilt !== boat) {
                val root = boat.rootCoords
                infos.updateRootBuildAreaCenteredOnPlayer(root.x, root.z)
                session.queue(RebuildNormal(root.x shr 3, root.z shr 3, worldId))
                boatRootBuilt = boat
                knownRegionUid = null
                cachedRegionZoneProvider = null
                logger.info { "[boat] root rebuild around $root (player at $coords, buildArea=$buildArea)" }
            }
            return
        }
        val recalcBuildArea = knownBuildArea != buildArea && buildArea != CoordGrid.NULL
        if (recalcBuildArea) {
            val zone = ZoneKey.from(buildArea)
            infos.updateRootBuildArea(zone.x, zone.z)
        }

        if (!recalcBuildArea) {
            return
        }

        // Skip log-in rebuild as RebuildLogin is already sent.
        if (knownBuildArea == CoordGrid.NULL) {
            knownBuildArea = buildArea
            return
        }

        if (regionUid == null) {
            val rebuild = RebuildNormal(x shr 3, z shr 3, worldId)
            knownBuildArea = buildArea
            knownRegionUid = null
            cachedRegionZoneProvider = null
            session.queue(rebuild)
            return
        }

        val region = regions[coords]

        // The player's region uid should be reassigned every cycle before calling this function,
        // as such we should expect the region to always be valid at this point.
        checkNotNull(region) { "Unexpected invalid region: uid=$regionUid, coords=$coords" }

        // TODO: When implementing `net` module properly, figure out what the best way would be to
        //  "invalidate" the `cachedRebuildRegion` if the region is somehow altered. This can
        //  happen in regions such as the Gauntlet. (If we decide to keep this as a cached value
        //  as opposed to reconstructing it every time)
        if (regionUid != knownRegionUid) {
            cachedRegionZoneProvider = createRegionZoneProvider(region)
            knownRegionUid = regionUid
        }

        val zoneProvider = cachedRegionZoneProvider ?: createRegionZoneProvider(region)
        val rebuild = RebuildRegion(x shr 3, z shr 3, true, zoneProvider)
        knownBuildArea = buildArea
        cachedRegionZoneProvider = zoneProvider
        session.queue(rebuild)
    }

    private fun createRegionZoneProvider(
        region: Region
    ): RebuildRegionV2.RebuildRegionZoneProvider {
        val regionZones = region.toZoneList()
        val rebuildZones =
            regionZones.associateWith { zone ->
                val copyZone = regions[zone]
                if (copyZone == RegionZoneCopy.NULL) {
                    return@associateWith null
                }
                RebuildRegionZone(
                    copyZone.normalX,
                    copyZone.normalZ,
                    copyZone.normalLevel,
                    copyZone.rotation,
                )
            }
        val zoneProvider =
            object : RebuildRegionV2.RebuildRegionZoneProvider {
                override fun provide(zoneX: Int, zoneZ: Int, level: Int): RebuildRegionZone? {
                    val zoneKey = ZoneKey(zoneX, zoneZ, level)
                    return rebuildZones[zoneKey]
                }
            }
        return zoneProvider
    }

    private fun Player.applyPublicMessage() {
        val message = publicMessage ?: return
        playerExtendedInfo.setChat(
            colour = message.colour,
            effects = message.effect,
            modicon = message.modIcon,
            autotyper = message.autoTyper,
            text = message.text,
            pattern = message.pattern,
        )
        publicMessage = null
    }

    private fun Player.applyFacePathingEntity() {
        val slot = faceEntity.entitySlot
        if (knownFaceEntity != slot) {
            playerExtendedInfo.setFacePathingEntity(slot)
            knownFaceEntity = slot
        }
    }

    private fun Player.applyFaceAngle() {
        if (pendingFaceAngle != EntityFaceAngle.NULL) {
            playerExtendedInfo.setFaceAngle(pendingFaceAngle.intValue)
        }
    }

    private fun Player.applyAnim() {
        when (pendingSequence) {
            EntitySeq.NULL -> return
            EntitySeq.ZERO -> playerExtendedInfo.setSequence(-1, 0)
            else -> playerExtendedInfo.setSequence(pendingSequence.id, pendingSequence.delay)
        }
    }

    private fun Player.applySpotanims() {
        if (pendingSpotanims.isEmpty) {
            return
        }
        for (packed in pendingSpotanims.longIterator()) {
            val (id, delay, height, slot) = EntitySpotanim(packed)
            playerExtendedInfo.setSpotAnim(slot, id, delay, height)
        }
    }

    private fun Player.applySay() {
        val text = pendingSay ?: return
        playerExtendedInfo.setSay(text)
    }

    private fun Player.applyExactMove() {
        val move = pendingExactMove ?: return
        playerExtendedInfo.setExactMove(
            deltaX1 = move.deltaX1,
            deltaZ1 = move.deltaZ1,
            delay1 = move.clientDelay1,
            deltaX2 = move.deltaX2,
            deltaZ2 = move.deltaZ2,
            delay2 = move.clientDelay2,
            angle = move.direction,
        )
    }

    private fun Player.applyHeadbars() {
        for (packedHeadbar in activeHeadbars.longIterator()) {
            val headbar = Headbar(packedHeadbar)
            playerExtendedInfo.addHeadBar(
                sourceIndex = if (headbar.isNoSource) -1 else headbar.sourceSlot,
                selfType = headbar.self,
                otherType = if (headbar.isPrivate) -1 else headbar.public,
                startFill = headbar.startFill,
                endFill = headbar.endFill,
                startTime = headbar.startTime,
                endTime = headbar.endTime,
            )
        }
    }

    private fun Player.applyHitmarks() {
        for (packedHitmark in activeHitmarks.longIterator()) {
            val hitmark = Hitmark(packedHitmark)
            playerExtendedInfo.addHitMark(
                sourceIndex = if (hitmark.isNoSource) -1 else hitmark.sourceSlot,
                selfType = hitmark.self,
                sourceType = hitmark.source,
                otherType = if (hitmark.isPrivate) -1 else hitmark.public,
                value = hitmark.damage,
                delay = hitmark.delay,
            )
        }
    }

    private fun Player.syncAppearance(objTypes: ObjTypeList) {
        if (!appearance.rebuild) {
            return
        }
        val info = playerExtendedInfo

        val colours = appearance.coloursSnapshot()
        for (i in colours.indices) {
            info.setColour(i, colours[i].toInt())
        }

        val identKit = appearance.identKitSnapshot()
        for (i in identKit.indices) {
            info.setIdentKit(i, identKit[i].toInt())
        }

        info.setName(displayName)
        info.setOverheadIcon(overheadIcon ?: -1)
        info.setSkullIcon(skullIcon ?: -1)
        info.setCombatLevel(combatLevel)
        info.setBodyType(appearance.bodyType)
        info.setPronoun(appearance.pronoun)
        info.setHidden(appearance.softHidden)

        info.setNameExtras(
            beforeName = appearance.namePrefix ?: "",
            afterName = appearance.nameSuffix ?: "",
            afterCombatLevel = appearance.combatLvlSuffix ?: "",
        )

        val bas = this.appearance.bas
        val weapon = this.righthand
        val transmog = this.transmog

        val readyAnim: Int
        val turnOnSpotAnim: Int
        val walkForwardAnim: Int
        val walkBackAnim: Int
        val walkLeftAnim: Int
        val walkRightAnim: Int
        val runningAnim: Int

        if (bas != null) {
            readyAnim = bas.readyAnim.id
            turnOnSpotAnim = bas.turnOnSpot.id
            walkForwardAnim = bas.walkForward.id
            walkBackAnim = bas.walkBack.id
            walkLeftAnim = bas.walkLeft.id
            walkRightAnim = bas.walkRight.id
            runningAnim = bas.running.id
        } else if (transmog != null) {
            readyAnim = transmog.readyAnim
            turnOnSpotAnim = transmog.turnBackAnim
            walkForwardAnim = transmog.walkAnim
            walkBackAnim = transmog.walkAnim
            walkLeftAnim = transmog.turnLeftAnim
            walkRightAnim = transmog.turnRightAnim
            runningAnim = transmog.runAnim
        } else if (weapon != null) {
            val type = objTypes[weapon]
            readyAnim = type.param(params.bas_readyanim).id
            turnOnSpotAnim = type.param(params.bas_turnonspot).id
            walkForwardAnim = type.param(params.bas_walk_f).id
            walkBackAnim = type.param(params.bas_walk_b).id
            walkLeftAnim = type.param(params.bas_walk_l).id
            walkRightAnim = type.param(params.bas_walk_r).id
            runningAnim = type.param(params.bas_running).id
        } else {
            val default = baseanimsets.human_default
            readyAnim = default.readyAnim.id
            turnOnSpotAnim = default.turnOnSpot.id
            walkForwardAnim = default.walkForward.id
            walkBackAnim = default.walkBack.id
            walkLeftAnim = default.walkLeft.id
            walkRightAnim = default.walkRight.id
            runningAnim = default.running.id
        }

        info.setTransmogrification(transmog?.id ?: -1)
        info.setBaseAnimationSet(
            readyAnim = readyAnim,
            turnAnim = turnOnSpotAnim,
            walkAnim = walkForwardAnim,
            walkAnimBack = walkBackAnim,
            walkAnimLeft = walkLeftAnim,
            walkAnimRight = walkRightAnim,
            runAnim = runningAnim,
        )

        for (wearpos in Wearpos.visibleWearpos) {
            val obj = worn[wearpos.slot]
            if (obj == null) {
                info.setWornObj(wearpos.slot, -1, -1, -1)
                continue
            }
            val objType = objTypes[obj]
            info.setWornObj(wearpos.slot, obj.id, objType.wearpos2, objType.wearpos3)
        }
    }
}
