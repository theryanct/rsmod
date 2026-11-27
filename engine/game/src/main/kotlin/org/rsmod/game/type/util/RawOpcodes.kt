package org.rsmod.game.type.util

/**
 * Client-only config opcodes the server does not model, kept verbatim so they survive a
 * decode -> edit -> encode round trip into the client (js5) cache.
 *
 * [bytes] is a concatenation of complete records: each record is the opcode byte followed by its
 * payload, exactly as read from the cache. Compared by content, and deliberately left out of type
 * identity hashes (these fields are cosmetic/client-side only).
 */
public class RawOpcodes(public val bytes: ByteArray) {
    public val isEmpty: Boolean
        get() = bytes.isEmpty()

    public operator fun plus(other: RawOpcodes): RawOpcodes = RawOpcodes(bytes + other.bytes)

    override fun equals(other: Any?): Boolean =
        this === other || (other is RawOpcodes && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "RawOpcodes(size=${bytes.size})"

    public companion object {
        public val EMPTY: RawOpcodes = RawOpcodes(ByteArray(0))

        /** Edited types keep their base type's raw opcodes unless the edit carries its own. */
        public fun merge(edit: RawOpcodes?, base: RawOpcodes?): RawOpcodes? =
            if (edit != null && !edit.isEmpty) edit else base
    }
}
