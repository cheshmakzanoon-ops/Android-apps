package com.agency.photoduel.platform.sharing

/**
 * The plain-text invitation. A single sentence names the room and carries the invitation URL exactly as
 * the API returned it (no trimming, no re-encoding), followed by a hint for people who do not have the
 * app yet. Pure Kotlin so it is covered by plain JVM tests.
 */
object InvitationText {
    private const val MAX_ROOM_NAME = 60

    fun build(roomName: String, inviteUrl: String): String {
        val name = cleanRoomName(roomName)
        val sentence = if (name.isEmpty()) {
            "Join my PhotoDuel room: $inviteUrl"
        } else {
            "Join my PhotoDuel room \"$name\": $inviteUrl"
        }
        return sentence + "\n" + "Open this link on a phone that has the PhotoDuel app installed."
    }

    /** Control characters removed, runs of whitespace collapsed, length bounded. */
    internal fun cleanRoomName(raw: String): String {
        val printable = raw.filter { !it.isISOControl() || it == '\n' || it == '\r' || it == '\t' }
        return printable.trim().replace(Regex("\\s+"), " ").take(MAX_ROOM_NAME).trim()
    }
}
