package com.agency.photoduel.engine.android

import android.content.Context
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath

/** Sizes of the image caches built by [buildPhotoDuelImageLoader]. */
object CoilConfig {
    /** Share of the app's available heap given to decoded bitmaps in memory. */
    const val MEMORY_CACHE_PERCENT: Double = 0.20

    /** Upper bound of the encoded-response cache on disk. */
    const val DISK_CACHE_MAX_BYTES: Long = 50L * 1024 * 1024

    /** Sub-directory of `cacheDir` that holds the disk cache. */
    const val DISK_CACHE_DIRECTORY_NAME: String = "photoduel-images"
}

/**
 * Builds the app's Coil 3 [ImageLoader]: a memory cache of about 20 percent of the available heap, a
 * 50 MB disk cache in `cacheDir`, and a crossfade between placeholder and image.
 *
 * The network fetch is not configured here. The caller supplies it through [components], using the
 * app's authenticated OkHttp client (the data layer adds the Authorization header, the UI never sees a
 * token):
 *
 * ```
 * val loader = buildPhotoDuelImageLoader(context) {
 *     add(OkHttpNetworkFetcherFactory(callFactory = { authenticatedOkHttpClient }))
 * }
 * ```
 *
 * `OkHttpNetworkFetcherFactory` comes from `coil-network-okhttp`.
 *
 * **Cache-revocation trade-off.** The server authorizes every media request and revocation takes
 * effect on the next request (docs/architecture.md decision D-05). This loader deliberately keeps
 * bitmaps in memory and responses on disk to save bandwidth, which is the largest cost driver
 * (docs/architecture.md section 10). The consequence is that bytes already cached on the device can
 * outlive server-side access revocation: a photo the user was allowed to see, and which was then
 * hidden, purged, or made inaccessible because the user left the room or was blocked, can still be
 * drawn from the memory cache or the disk cache until the entry is evicted, its freshness
 * (`Cache-Control: private, max-age=300`) runs out and the revalidation is refused, or the caches are
 * cleared. The cache is private to the app, limited to 50 MB, and lives in `cacheDir`, which the
 * system may clear. It is not a security boundary against someone who holds the unlocked device.
 * Call [clearImageCaches] on logout, account deletion and when the user leaves a room, so revocation
 * the user triggers themselves takes effect on the device at once.
 */
fun buildPhotoDuelImageLoader(
    context: Context,
    components: ComponentRegistry.Builder.() -> Unit = {},
): ImageLoader {
    val appContext = context.applicationContext ?: context
    return ImageLoader.Builder(appContext)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(appContext, CoilConfig.MEMORY_CACHE_PERCENT)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(appContext.cacheDir.resolve(CoilConfig.DISK_CACHE_DIRECTORY_NAME).toOkioPath())
                .maxSizeBytes(CoilConfig.DISK_CACHE_MAX_BYTES)
                .build()
        }
        .crossfade(true)
        .components(components)
        .build()
}

/**
 * Empties both image caches of [imageLoader]. The disk part does file I/O, so call it from a
 * background dispatcher.
 */
fun clearImageCaches(imageLoader: ImageLoader) {
    imageLoader.memoryCache?.clear()
    imageLoader.diskCache?.clear()
}
