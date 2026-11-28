package org.rsmod.api.player.output

import net.rsprot.protocol.game.outgoing.camera.CamLookAt
import net.rsprot.protocol.game.outgoing.camera.CamMoveTo
import net.rsprot.protocol.game.outgoing.camera.CamReset
import net.rsprot.protocol.game.outgoing.camera.CamShake
import net.rsprot.protocol.game.outgoing.camera.CamSmoothReset
import org.rsmod.game.entity.Player
import org.rsmod.map.CoordGrid

public object Camera {
    public fun camReset(player: Player) {
        player.client.write(CamReset)
    }

    public fun camLookAt(player: Player, dest: CoordGrid, height: Int, rate: Int, rate2: Int) {
        // TODO: Add require assertion to make sure coords is valid within build area.
        val dx = dest.x - player.buildArea.x
        val dz = dest.z - player.buildArea.z
        // Rev 243 added `heightRelative`; true keeps the old ground-relative height.
        player.client.write(CamLookAt(dx, dz, height, rate, rate2, heightRelative = true))
    }

    /**
     * Shakes the camera (rsprot `CamShake`): [axis] 0-4 picks what shakes (x, height, z, yaw,
     * pitch), [random] / [amplitude] the random and sine parts, [rate] the sine speed.
     */
    public fun camShake(player: Player, axis: Int, random: Int, amplitude: Int, rate: Int) {
        player.client.write(CamShake(axis, random, amplitude, rate))
    }

    /** Eases the camera back to the player (rsprot `CamSmoothReset`). */
    public fun camSmoothReset(
        player: Player,
        moveConstant: Int,
        moveProportional: Int,
        lookConstant: Int,
        lookProportional: Int,
    ) {
        player.client.write(
            CamSmoothReset(moveConstant, moveProportional, lookConstant, lookProportional)
        )
    }

    public fun camMoveTo(player: Player, dest: CoordGrid, height: Int, rate: Int, rate2: Int) {
        // TODO: Add require assertion to make sure coords is valid within build area.
        val dx = dest.x - player.buildArea.x
        val dz = dest.z - player.buildArea.z
        player.client.write(CamMoveTo(dx, dz, height, rate, rate2, heightRelative = true))
    }
}
