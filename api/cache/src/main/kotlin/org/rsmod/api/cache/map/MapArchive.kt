package org.rsmod.api.cache.map

import io.netty.buffer.ByteBuf
import org.openrs2.cache.Cache
import org.openrs2.crypto.SymmetricKey
import org.rsmod.api.cache.Js5Archives
import org.rsmod.api.cache.util.listOrEmpty
import org.rsmod.api.cache.util.readOrNull
import org.rsmod.map.square.MapSquareKey

/**
 * The files a map square is made of. [Terrain] and [Locations] are client groups; the rest are
 * server-only groups packed by rsmod.
 */
public enum class MapFile(internal val prefix: Char, internal val numericFile: Int) {
    Terrain('m', 0),
    Locations('l', 1),
    Npcs('n', 0),
    Objs('o', 1),
    Areas('a', 2);

    internal val serverOnly: Boolean
        get() = this == Npcs || this == Objs || this == Areas
}

/**
 * Reads and writes map square groups in the maps archive (archive 5).
 *
 * Up to revision 236 the archive used named groups (`m50_50`, `l50_50`, ...) and loc groups were
 * xtea-encrypted. From revision 237 the archive is unnamed: group `id` is the [MapSquareKey] id,
 * with terrain as file 0 and locs as file 1, and nothing is encrypted. In that layout rsmod packs
 * its server-only groups (npcs, objs, areas) into group `id + 65536` so they never collide with a
 * client group.
 */
public class MapArchive(private val cache: Cache) {
    private val archive = Js5Archives.MAPS

    /** `true` for the unnamed (revision 237+) layout. */
    public val numeric: Boolean =
        cache.exists(archive) && cache.list(archive).asSequence().firstOrNull()?.nameHash == UNNAMED_HASH

    /** Every map square with both terrain and locs. Only valid for the [numeric] layout. */
    public fun numericSquares(): List<MapSquareKey> {
        check(numeric) { "Map archive uses named groups; squares come from the xtea map." }
        return cache
            .list(archive)
            .asSequence()
            .filter { it.id < SERVER_GROUP_OFFSET }
            .filter { group ->
                val files = cache.listOrEmpty(archive, group.id).asSequence().map { it.id }.toSet()
                // Map squares have 5 files in cache 2777 (terrain, locs + 3 client files). Group
                // 25287 (38 tiny files) is not a map square despite having files 0 and 1.
                files.size <= MAX_SQUARE_FILES &&
                    MapFile.Terrain.numericFile in files &&
                    MapFile.Locations.numericFile in files
            }
            .map { MapSquareKey(it.id) }
            .toList()
    }

    public fun read(
        square: MapSquareKey,
        type: MapFile,
        key: SymmetricKey = SymmetricKey.ZERO,
    ): ByteBuf? =
        if (numeric) {
            cache.readOrNull(archive, group(square, type), type.numericFile)
        } else {
            cache.readOrNull(archive, name(square, type), 0, key)
        }

    public fun write(
        square: MapSquareKey,
        type: MapFile,
        data: ByteBuf,
        key: SymmetricKey = SymmetricKey.ZERO,
    ) {
        if (numeric) {
            cache.write(archive, group(square, type), type.numericFile, data)
        } else {
            cache.write(archive, name(square, type), 0, data, key)
        }
    }

    private fun group(square: MapSquareKey, type: MapFile): Int =
        if (type.serverOnly) square.id + SERVER_GROUP_OFFSET else square.id

    private fun name(square: MapSquareKey, type: MapFile): String =
        "${type.prefix}${square.x}_${square.z}"

    private companion object {
        private const val SERVER_GROUP_OFFSET = 65536
        private const val MAX_SQUARE_FILES = 5

        // openrs2 reports -1 as the name hash of groups in an unnamed index.
        private const val UNNAMED_HASH = -1
    }
}
