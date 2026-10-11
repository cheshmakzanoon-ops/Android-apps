package com.agency.photoduel.platform.deeplink

/**
 * The only invitation link this build understands: `photoduel://invite/{token}`.
 *
 * There is no verified web domain and no `assetlinks.json`, so Android App Links are NOT claimed
 * (docs/architecture.md D-11). The token is the 43 character Base64url form of 32 random bytes that
 * the API returns once when an invitation is created (docs/api-contract.md section 4).
 *
 * Pure Kotlin on purpose: no android.* imports, so the parsing rules run in plain JVM tests.
 */
object InviteLink {
    const val SCHEME = "photoduel"
    const val HOST = "invite"
    const val TOKEN_LENGTH = 43

    /** Longest link text this parser will look at. Anything longer is not an invitation. */
    private const val MAX_LINK_LENGTH = 2048

    private val TOKEN_PATTERN = Regex("[A-Za-z0-9_-]{$TOKEN_LENGTH}")

    // Scheme and host are case-insensitive in a URI. The token is matched on its own alphabet, so the
    // case-insensitive flag cannot loosen it. An optional trailing slash, query or fragment is tolerated
    // and ignored; extra path segments are not.
    private val LINK_PATTERN = Regex(
        "$SCHEME://$HOST/([A-Za-z0-9_-]{$TOKEN_LENGTH})/?(?:[?#].*)?",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** True when [token] has exactly the shape the server issues. It says nothing about validity. */
    fun isValidToken(token: String?): Boolean = token != null && TOKEN_PATTERN.matches(token)

    /**
     * Returns the token inside [link], or null when [link] is not a well formed invitation link.
     * Only the custom scheme is accepted: an https link never yields a token.
     */
    fun extractToken(link: String?): String? {
        if (link == null) return null
        val trimmed = link.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_LINK_LENGTH) return null
        return LINK_PATTERN.matchEntire(trimmed)?.groupValues?.get(1)
    }

    /** The link for [token], in the exact form the API returns in `url`. */
    fun urlFor(token: String): String {
        require(isValidToken(token)) { "not an invitation token" }
        return "$SCHEME://$HOST/$token"
    }
}
