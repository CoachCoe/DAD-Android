package org.dad.foundation

import android.content.Context

/**
 * DEBUG-only capturing [ReferralSharer] for the Compose instrumentation tests. It mirrors the scripted
 * backend pattern: instead of touching the OS clipboard or the share sheet it records what the app asked
 * to copy or share into [DebugBackend], so a UI test asserts the invite payload at the app's own sharer
 * seam deterministically — the real [ReferralShareAction] + analytics still run, only the terminal
 * OS sink is replaced. This source set is compiled into debug builds only; release builds never see it.
 */
class DebugReferralSharer : ReferralSharer {
    override fun share(link: String, channel: String) {
        DebugBackend.sharedLink = link
        DebugBackend.sharedChannel = channel
    }

    override fun copy(link: String) {
        DebugBackend.copiedLink = link
    }
}

/**
 * The sharer the composition root uses for the invite link. In debug it captures to [DebugBackend] when
 * the scripted stub backend is active (identified by the stub public origin), and otherwise uses the real
 * [AndroidReferralSharer]. See the release twin, which always returns the real sharer.
 */
fun debugReferralSharer(context: Context, publicOrigin: String): ReferralSharer =
    if (publicOrigin == DebugBackend.PUBLIC_ORIGIN) DebugReferralSharer() else AndroidReferralSharer(context)
