package org.rsmod.api.obj.plugin

import org.junit.jupiter.api.Test
import org.rsmod.api.config.refs.objs
import org.rsmod.api.testing.GameTestState
import org.rsmod.api.testing.scope.GameTestScope
import org.rsmod.game.obj.Obj
import org.rsmod.game.obj.ObjEntity
import org.rsmod.game.obj.ObjScope
import org.rsmod.map.CoordGrid

class ObjTakePluginTest {
    @Test
    fun GameTestState.`take map spawn from same tile`() =
        runGameTest(ObjTakePlugin::class) {
            val obj = spawnMapObj(SPAWN)
            player.teleport(SPAWN)
            player.opObj3(obj)

            advance(ticks = 2)
            assertEquals(1, player.count(objs.bronze_axe))
            assertTrue(objRepo.findAll(SPAWN).none { it.type == obj.type })
        }

    @Test
    fun GameTestState.`take map spawn from a distance then respawns`() =
        runGameTest(ObjTakePlugin::class) {
            val obj = spawnMapObj(SPAWN)
            player.teleport(SPAWN.translateX(-3))
            player.opObj3(obj)

            advanceUntil(predicate = { player.count(objs.bronze_axe) == 1 }, timeoutTicks = 10)
            assertEquals(1, player.count(objs.bronze_axe))
            assertTrue(objRepo.findAll(SPAWN).none { it.type == obj.type })

            advance(ticks = objTypes[objs.bronze_axe].respawnRate + 1)
            assertTrue(objRepo.findAll(SPAWN).any { it.type == obj.type })
        }

    /** Mirrors how `GameMapDecoder.putObjs` registers packed map obj spawns. */
    private fun GameTestScope.spawnMapObj(coords: CoordGrid): Obj {
        val entity = ObjEntity(objs.bronze_axe.id, count = 1, scope = ObjScope.Perm.id)
        val obj = Obj(coords, entity, creationCycle = 0, receiverId = Obj.NULL_OBSERVER_ID)
        objRepo.add(obj, duration = Int.MAX_VALUE)
        return obj
    }

    private companion object {
        val SPAWN = CoordGrid(0, 50, 50, 21, 18)
    }
}
