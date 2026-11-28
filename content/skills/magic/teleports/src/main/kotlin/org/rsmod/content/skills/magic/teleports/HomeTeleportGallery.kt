package org.rsmod.content.skills.magic.teleports

import org.rsmod.game.entity.Player

/**
 * Home Teleport's right-click "Gallery" (op8, see HomeTeleportEdits): the gallery module
 * (content/other/gallery) sets [open] at startup; null = the gallery isn't loaded.
 */
object HomeTeleportGallery {
    var open: ((Player) -> Unit)? = null
}
