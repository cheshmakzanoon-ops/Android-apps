package com.agency.photoduel.domain.port

/** Persists the bearer token. The data layer calls it; the platform layer encrypts it (Keystore). */
interface SessionStore {
    suspend fun read(): String?
    suspend fun write(token: String?)
}
