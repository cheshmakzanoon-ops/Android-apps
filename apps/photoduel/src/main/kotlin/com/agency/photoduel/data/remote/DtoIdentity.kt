package com.agency.photoduel.data.remote

import kotlinx.serialization.Serializable

// docs/api-contract.md section 2 (Identity). DTOs that carry a credential override toString so that an
// accidental log line or assertion message never prints it.

/** `POST /v1/identity/guest` request: `{"displayName":"Ada"}`. */
@Serializable
internal data class GuestRequestDto(val displayName: String)

/** `POST /v1/identity/restore` request: `{"recoveryCode":"ABCD-EFGH-JKMN-PQRS"}`. */
@Serializable
internal data class RestoreRequestDto(val recoveryCode: String) {
    override fun toString(): String = "RestoreRequestDto(recoveryCode=${ApiErrors.REDACTED})"
}

/** `{"id","displayName","hasRecoveryCode"}`, used by guest, restore and `/v1/me`. */
@Serializable
internal data class UserDto(val id: String, val displayName: String, val hasRecoveryCode: Boolean)

/** `{"token":"…","expiresAtEpochMillis":1234}`. */
@Serializable
internal data class SessionTokenDto(val token: String, val expiresAtEpochMillis: Long) {
    override fun toString(): String = "SessionTokenDto(token=${ApiErrors.REDACTED}, expiresAtEpochMillis=$expiresAtEpochMillis)"
}

/**
 * Guest creation (`201`) and restore (`200`): `{"user":{…},"session":{…}}`. Restore also returns the
 * rotated `"recoveryCode"` once.
 */
@Serializable
internal data class SessionResponseDto(
    val user: UserDto,
    val session: SessionTokenDto,
    val recoveryCode: String? = null,
) {
    override fun toString(): String =
        "SessionResponseDto(user=$user, session=$session, recoveryCode=${recoveryCode?.let { ApiErrors.REDACTED }})"
}

/** `POST /v1/identity/recovery-code` response `201`: `{"recoveryCode":"ABCD-EFGH-JKMN-PQRS"}`. */
@Serializable
internal data class RecoveryCodeDto(val recoveryCode: String) {
    override fun toString(): String = "RecoveryCodeDto(recoveryCode=${ApiErrors.REDACTED})"
}

/** `PATCH /v1/me` request: `{"displayName":"…"}`. */
@Serializable
internal data class RenameRequestDto(val displayName: String)

/** `DELETE /v1/me` request: `{"confirm":"DELETE"}`. */
@Serializable
internal data class DeleteAccountRequestDto(val confirm: String)
