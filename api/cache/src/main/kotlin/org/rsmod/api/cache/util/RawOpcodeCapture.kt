package org.rsmod.api.cache.util

import io.netty.buffer.ByteBuf
import java.io.ByteArrayOutputStream
import org.rsmod.game.type.util.RawOpcodes

/**
 * Collects client-only opcode records while decoding a config type.
 *
 * Call [capture] right after reading the opcode byte; it runs [skip] to consume the payload and
 * copies `opcode + payload` verbatim. [result] returns the collected records (or `null`).
 */
public class RawOpcodeCapture {
    private var out: ByteArrayOutputStream? = null

    public fun capture(code: Int, data: ByteBuf, skip: ByteBuf.() -> Unit) {
        val start = data.readerIndex()
        data.skip()
        val end = data.readerIndex()
        val bytes = ByteArray(end - start)
        data.getBytes(start, bytes)
        val stream = out ?: ByteArrayOutputStream().also { out = it }
        stream.write(code)
        stream.write(bytes)
    }

    public fun result(): RawOpcodes? = out?.toByteArray()?.let(::RawOpcodes)
}

/** Writes [raw] records verbatim (call before the config terminator). */
public fun ByteBuf.writeRawOpcodes(raw: RawOpcodes?) {
    if (raw != null && !raw.isEmpty) {
        writeBytes(raw.bytes)
    }
}

/** EntityOps sub-op record payload: index, subId, text. */
public fun ByteBuf.skipEntitySubOp() {
    readUnsignedByte()
    readUnsignedByte()
    skipString()
}

/** EntityOps conditional op payload: index, varp, varbit, min, max, text. */
public fun ByteBuf.skipEntityConditionalOp() {
    readUnsignedByte()
    readUnsignedShort()
    readUnsignedShort()
    readInt()
    readInt()
    skipString()
}

/** EntityOps conditional sub-op payload: index, subId(u16), varp, varbit, min, max, text. */
public fun ByteBuf.skipEntityConditionalSubOp() {
    readUnsignedByte()
    readUnsignedShort()
    readUnsignedShort()
    readUnsignedShort()
    readInt()
    readInt()
    skipString()
}

/** Skips a 0-terminated cp1252 string. */
public fun ByteBuf.skipString() {
    while (readByte().toInt() != 0) {
        // consume
    }
}
