package com.agency.photoduel.data.remote

import com.agency.photoduel.domain.model.AppResult
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.PhotoDuelError
import com.agency.photoduel.domain.port.SessionStore
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response

/**
 * The HTTP transport for docs/api-contract.md. It knows the rules every route shares and nothing about
 * individual routes (those live in [RemotePhotoDuelService]):
 *
 * - **Base URL.** [baseUrl] may end with or without the `/v1` version segment (`https://host/` and
 *   `https://host/v1/` are the same server). An unusable value throws [IllegalArgumentException].
 * - **Authentication.** The bearer token is read from [sessionStore] for every request, right before it
 *   is sent. Without a stored token an authenticated call fails with `UNAUTHENTICATED` and nothing is sent.
 *   The token is never logged and never appears in an error message.
 * - **Timeouts.** Connect 10 s, read 30 s, write 60 s.
 * - **Server time.** [ServerDateInterceptor] feeds each response's `Date` header into [clock].
 * - **Retries.** Only GET is retried automatically: at most [MAX_GET_RETRIES] more attempts, after a
 *   network failure or a 5xx, waiting [getRetryDelaysMillis] in between. OkHttp's own silent retries are
 *   switched off and non-GET bodies are one-shot, so a POST, PUT, PATCH or DELETE is sent exactly once.
 * - **Errors.** Mapped by [ApiErrors] as section 3 of the contract states. A network failure is
 *   `NETWORK` (or `TIMEOUT`) with `retryable = true`.
 */
class PhotoDuelApiClient(
    baseUrl: String,
    internal val sessionStore: SessionStore,
    val clock: OffsetServerClock = OffsetServerClock(),
    private val getRetryDelaysMillis: List<Long> = DEFAULT_GET_RETRY_DELAYS_MILLIS,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    init {
        require(getRetryDelaysMillis.size <= MAX_GET_RETRIES) { "At most $MAX_GET_RETRIES GET retries are allowed" }
        require(getRetryDelaysMillis.all { it >= 0 }) { "Retry delays must not be negative" }
    }

    /** The server root without the version segment, always ending with `/`. */
    val root: HttpUrl = apiRoot(baseUrl.trim().toHttpUrl())

    /** The client for API calls. Media requests use [mediaClient], which shares its connections. */
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(ServerDateInterceptor(clock))
        .build()

    /**
     * Client for image loading (for example Coil's OkHttp fetcher). It adds the bearer token only to
     * `GET {root}v1/media/…` on the configured scheme, host and port, so the UI never handles a token
     * and no other host ever receives it. Image fetches run off the main thread, where reading the
     * session store synchronously is acceptable.
     */
    val mediaClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .retryOnConnectionFailure(true)
            .addInterceptor(MediaAuthInterceptor(root, sessionStore))
            .build()
    }

    /** Absolute URL of `/v1/<segments>`. Each segment is percent-encoded, so an id cannot change the path. */
    fun url(segments: List<String>, query: List<Pair<String, String>> = emptyList()): HttpUrl {
        val builder = root.newBuilder().addPathSegment(VERSION_SEGMENT)
        segments.forEach { builder.addPathSegment(it) }
        query.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build()
    }

    /**
     * Sends one API request and converts the outcome. [parse] receives the body of a 2xx response; if it
     * throws [IllegalArgumentException] (which includes kotlinx.serialization errors) the result is an
     * `UNKNOWN` failure, never a crash. Coroutine cancellation cancels the HTTP call.
     */
    suspend fun <T> execute(
        method: String,
        segments: List<String>,
        query: List<Pair<String, String>> = emptyList(),
        body: RequestBody? = null,
        authenticated: Boolean = true,
        missingResource: ErrorCode = ErrorCode.NOT_FOUND,
        parse: (String) -> T,
    ): AppResult<T> {
        require(method in METHODS) { "Unsupported method $method" }
        require(method != GET || body == null) { "GET has no body" }
        // URL normalisation would drop or pop these segments and silently address another route. No id or
        // token of the contract can take these values, so the resource cannot exist.
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            return AppResult.Failure(
                PhotoDuelError(missingResource, ApiErrors.defaultMessage(missingResource), retryable = false),
            )
        }
        val url = url(segments, query)
        val requestBody: RequestBody? = when {
            method == GET -> null
            body == null -> emptyOneShotBody()
            body.isOneShot() -> body
            else -> OneShotRequestBody(body)
        }
        val maxAttempts = if (method == GET) 1 + getRetryDelaysMillis.size else 1
        var attempt = 0
        while (true) {
            attempt++
            val token: String? = if (authenticated) {
                when (val read = readToken()) {
                    is TokenRead.Present -> read.token
                    TokenRead.Absent -> return AppResult.Failure(ApiErrors.notSignedIn())
                    TokenRead.Failed -> return AppResult.Failure(ApiErrors.sessionStorage())
                }
            } else {
                null
            }
            val request = Request.Builder()
                .url(url)
                .method(method, requestBody)
                .header("Accept", "application/json")
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .build()

            val raw: RawResponse = try {
                httpClient.newCall(request).await()
            } catch (e: IOException) {
                if (attempt < maxAttempts) {
                    sleep(getRetryDelaysMillis[attempt - 1])
                    continue
                }
                return AppResult.Failure(ApiErrors.transport(e))
            }

            if (raw.code in 200..299) {
                return try {
                    AppResult.Success(parse(raw.body))
                } catch (e: IllegalArgumentException) {
                    AppResult.Failure(ApiErrors.unreadable())
                }
            }
            if (raw.code in 500..599 && attempt < maxAttempts) {
                sleep(getRetryDelaysMillis[attempt - 1])
                continue
            }
            return AppResult.Failure(ApiErrors.fromResponse(raw.code, raw.body, raw.retryAfter, listOf(token)))
        }
    }

    private suspend fun readToken(): TokenRead = try {
        sessionStore.read()?.takeIf { it.isNotBlank() }?.let { TokenRead.Present(it) } ?: TokenRead.Absent
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TokenRead.Failed
    }

    private sealed interface TokenRead {
        class Present(val token: String) : TokenRead
        data object Absent : TokenRead
        data object Failed : TokenRead
    }

    /** What is kept of a response: the body is read on the OkHttp thread, so no coroutine thread blocks. */
    private class RawResponse(val code: Int, val body: String, val retryAfter: String?)

    private suspend fun Call.await(): RawResponse = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val raw = try {
                        response.use { RawResponse(it.code, it.body?.string().orEmpty(), it.header("Retry-After")) }
                    } catch (e: IOException) {
                        continuation.resumeWithException(e)
                        return
                    }
                    continuation.resume(raw)
                }
            },
        )
    }

    companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 30L
        const val WRITE_TIMEOUT_SECONDS = 60L

        /** At most two automatic retries, for GET only. */
        const val MAX_GET_RETRIES = 2

        /** Wait before the first and the second GET retry. */
        val DEFAULT_GET_RETRY_DELAYS_MILLIS: List<Long> = listOf(500L, 1_500L)

        const val VERSION_SEGMENT = "v1"

        internal const val GET = "GET"
        internal const val POST = "POST"
        internal const val PUT = "PUT"
        internal const val PATCH = "PATCH"
        internal const val DELETE = "DELETE"
        private val METHODS = setOf(GET, POST, PUT, PATCH, DELETE)

        /** `https://host/api/v1/` and `https://host/api/` both give `https://host/api/`. */
        internal fun apiRoot(base: HttpUrl): HttpUrl {
            val segments = base.encodedPathSegments.filter { it.isNotEmpty() }.toMutableList()
            if (segments.lastOrNull() == VERSION_SEGMENT) segments.removeAt(segments.lastIndex)
            return base.newBuilder()
                .query(null)
                .fragment(null)
                .encodedPath("/" + segments.joinToString("") { "$it/" })
                .build()
        }
    }
}

/** Adds the bearer token to media GETs on the API origin only. */
internal class MediaAuthInterceptor(root: HttpUrl, private val sessionStore: SessionStore) : Interceptor {
    private val scheme = root.scheme
    private val host = root.host
    private val port = root.port
    private val mediaPathPrefix = root.encodedPath + PhotoDuelApiClient.VERSION_SEGMENT + "/media/"

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isMediaRequest(request) || request.header("Authorization") != null) return chain.proceed(request)
        val token = try {
            runBlocking { sessionStore.read() }
        } catch (e: CancellationException) {
            throw IOException("Canceled")
        } catch (e: Exception) {
            throw IOException("The saved session could not be read")
        }
        if (token.isNullOrBlank()) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
    }

    private fun isMediaRequest(request: Request): Boolean {
        val url = request.url
        return request.method == "GET" &&
            url.scheme == scheme &&
            url.host == host &&
            url.port == port &&
            url.encodedPath.startsWith(mediaPathPrefix)
    }
}
