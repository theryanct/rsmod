package org.rsmod.game.entity.player

import org.rsmod.game.region.Region
import org.rsmod.map.CoordGrid

/**
 * The world entity (boat) the player is standing on - single-player Sailing (one avatar, owned by
 * this player). The player's own coords are inside [region] (the boat's template map); the
 * network layer allocates one world entity avatar from this and renders it at [rootCoords].
 *
 * @param index world entity index (1-4094); tier 1 always uses 1.
 * @param typeId `worldentity` config id (1 = raft).
 * @param region the instanced template map the deck lives in.
 * @param rootCoords where the boat sits in the root world (its pivot tile).
 * @param angle 0-2047 (0 south, 512 west, 1024 north, 1536 east).
 * @param activeLevel the deck level inside [region] (`worldentity.mainlevel`).
 * @param sizeXZones width of the boat's map in zones, from [Region.southWestZone].
 * @param sizeZZones length of the boat's map in zones.
 */
public class PlayerWorldEntity(
    public val index: Int,
    public val typeId: Int,
    public val region: Region,
    public var rootCoords: CoordGrid,
    public var angle: Int,
    public val activeLevel: Int,
    public val sizeXZones: Int,
    public val sizeZZones: Int,
)
