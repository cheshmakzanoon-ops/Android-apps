package com.agency.photoduel.data.remote

import com.agency.photoduel.domain.port.PhotoDuelService
import com.agency.photoduel.domain.port.ServerClock
import com.agency.photoduel.domain.port.SessionStore
import okhttp3.Call

/**
 * The only entry point the app uses into the remote data layer. Built once by the composition root.
 *
 * [service] talks HTTP to the configured server and [clock] follows that server's `Date` header.
 */
class RemoteServices(val service: PhotoDuelService, val clock: ServerClock) {

    /**
     * Authenticated call factory for the URLs returned by [PhotoDuelService.mediaUrl], to hand to an
     * image loader (for example Coil's `OkHttpNetworkFetcherFactory`). Null when [service] is not the
     * HTTP implementation.
     */
    val mediaCallFactory: Call.Factory?
        get() = (service as? RemotePhotoDuelService)?.mediaCallFactory

    companion object {
        /**
         * Builds the HTTP service for [baseUrl] (with or without the trailing `/v1`), persisting the
         * session token through [sessionStore]. Throws [IllegalArgumentException] when [baseUrl] is not an
         * http or https URL.
         */
        fun create(baseUrl: String, sessionStore: SessionStore): RemoteServices {
            val clock = OffsetServerClock()
            val api = PhotoDuelApiClient(baseUrl, sessionStore, clock)
            return RemoteServices(RemotePhotoDuelService(api), clock)
        }
    }
}
