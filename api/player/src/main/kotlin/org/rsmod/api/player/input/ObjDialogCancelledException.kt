package org.rsmod.api.player.input

import kotlin.coroutines.cancellation.CancellationException

/**
 * Thrown into a coroutine paused in `objDialog` when the player escapes the obj search (the client
 * resumes with a null obj). Callers that need to tidy up can catch it; uncaught, it ends the
 * coroutine quietly like any other cancellation.
 */
public class ObjDialogCancelledException : CancellationException("Obj dialog cancelled.")
