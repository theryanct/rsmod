package org.rsmod.api.cache.types.comp

import io.netty.buffer.ByteBufUtil
import io.netty.buffer.PooledByteBufAllocator
import io.netty.buffer.Unpooled
import org.openrs2.cache.Cache
import org.rsmod.api.cache.Js5Archives
import org.rsmod.game.type.comp.UnpackedComponentType

/**
 * Writes op-label edits into the interfaces archive. A component is only rewritten if decoding and
 * re-encoding its current bytes reproduces them exactly, so an encoder gap can never corrupt the
 * rest of the component.
 */
public object ComponentOpPatcher {
    /**
     * Applies [edit] to component [packed] in [cache]; returns `true` if it was rewritten.
     *
     * @throws IllegalStateException if the component is not if3 or doesn't round-trip.
     */
    public fun patch(
        cache: Cache,
        packed: Int,
        edit: (UnpackedComponentType) -> UnpackedComponentType,
    ): Boolean {
        val group = packed ushr 16
        val file = packed and 0xFFFF
        val original = cache.read(Js5Archives.INTERFACES, group, file)
        try {
            val originalBytes = ByteBufUtil.getBytes(original)
            val type = ComponentTypeDecoder.decode(packed, original.duplicate()).build(packed)
            check(type.v3) { "Component $group:$file is not if3; its ops can't be edited." }
            check(encode(type).contentEquals(originalBytes)) {
                "Component $group:$file does not re-encode byte-for-byte; refusing to patch it."
            }
            val edited = edit(type)
            if (edited == type) {
                return false
            }
            val patched = encode(edited)
            cache.write(Js5Archives.INTERFACES, group, file, Unpooled.wrappedBuffer(patched))
            return true
        } finally {
            original.release()
        }
    }

    private fun encode(type: UnpackedComponentType): ByteArray {
        val buffer = PooledByteBufAllocator.DEFAULT.buffer()
        try {
            ComponentTypeEncoder.encode(type, buffer)
            return ByteBufUtil.getBytes(buffer)
        } finally {
            buffer.release()
        }
    }
}
