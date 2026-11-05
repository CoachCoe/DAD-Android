package org.dad.foundation

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/** Release builds contain no scripted backend; the composition root treats null as "not available". */
object DebugBackend {
    const val PUBLIC_ORIGIN = ""
    @Suppress("UNUSED_PARAMETER")
    fun stub(context: Context, scope: CoroutineScope, options: LaunchOptions): AppServices? = null
}

/** Release builds always use the real clipboard/share-sheet sharer; there is no capturing double. */
@Suppress("UNUSED_PARAMETER")
fun debugReferralSharer(context: Context, publicOrigin: String): ReferralSharer = AndroidReferralSharer(context)
