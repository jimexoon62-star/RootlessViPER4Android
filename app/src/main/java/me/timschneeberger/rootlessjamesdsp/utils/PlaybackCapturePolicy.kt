package me.timschneeberger.rootlessjamesdsp.utils

/** Native AudioAttributes flags, not AudioTrack output flags nor public player flags.
 * Android 16 AudioPolicyMix::mixMatch rejects either bit for ordinary projection.
 */
internal object PlaybackCapturePolicy {
    private const val NO_MEDIA_PROJECTION = 0x400
    private const val NO_SYSTEM_CAPTURE = 0x1000
    fun isRestricted(flags: Int?) = flags != null &&
        flags and (NO_MEDIA_PROJECTION or NO_SYSTEM_CAPTURE) != 0

    fun parseNativeFlags(dump: String): Map<Pair<Int, Int>, Int> {
        val result = mutableMapOf<Pair<Int, Int>, Int>()
        var identity: Pair<Int, Int>? = null
        val client = Regex("Session (?:ID|Id):\\s*(\\d+);?\\s*(?:uid|UID):?\\s*(\\d+)")
        val flags = Regex("Flags:\\s*0x([0-9a-fA-F]+)")
        for (line in dump.lineSequence()) {
            // An Attributes record must belong to this client, never a later
            // unrelated client or output descriptor. No cross-record regex.
            client.find(line)?.let {
                identity = it.groupValues[1].toIntOrNull()?.let { sid ->
                    it.groupValues[2].toIntOrNull()?.let { uid -> sid to uid }
                }
            }
            val key = identity ?: continue
            if ("Attributes:" in line) {
                flags.find(line)?.groupValues?.get(1)?.toLongOrNull(16)
                    ?.takeIf { it <= Int.MAX_VALUE }?.toInt()?.let {
                        result[key] = (result[key] ?: 0) or it
                    }
                identity = null
            } else if (line.isBlank()) identity = null
        }
        return result
    }
}
