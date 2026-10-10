package com.agency.timeflip.app

import android.content.Context
import com.agency.timeflip.data.PrivatePhotoStore
import com.agency.timeflip.data.RoomProjectRepository
import com.agency.timeflip.data.StorageLayout
import com.agency.timeflip.data.StorageRecovery
import com.agency.timeflip.data.db.TimeFlipDatabase
import com.agency.timeflip.data.prefs.UserPreferences
import com.agency.timeflip.domain.model.AlignmentEstimate
import com.agency.timeflip.domain.model.ImagePair
import com.agency.timeflip.domain.model.PixelSize
import com.agency.timeflip.domain.port.PhotoStore
import com.agency.timeflip.domain.port.ProjectRepository
import com.agency.timeflip.domain.port.VideoExportEngine
import com.agency.timeflip.engine.auto.AutoAligner
import com.agency.timeflip.engine.export.Mp4VideoExporter
import com.agency.timeflip.engine.image.PhotoBitmaps
import com.agency.timeflip.ui.ports.AlignmentRunner
import com.agency.timeflip.ui.ports.OnboardingStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual composition root. [TimeFlipApplication] creates exactly one instance per process.
 *
 * The container holds no Activity, View or Compose reference. The UI receives every implementation
 * through these properties and nothing else. It does not own the UI.
 *
 * Layout on disk:
 * - [rootDir] = filesDir/timeflip (photos/ and exports/ live below it). The Room file is outside it.
 * - [exportsDir] = filesDir/timeflip/exports. The FileProvider shares from here (see file_paths.xml).
 *
 * @param photoStore test seam. Instrumented tests pass a fake, so they never touch real photo copies.
 *   Production passes nothing and gets [PrivatePhotoStore].
 */
class AppContainer(
    context: Context,
    photoStore: PhotoStore? = null,
) {
    private val appContext: Context = context.applicationContext

    val rootDir: File = File(appContext.filesDir, ROOT_DIR_NAME)
    val exportsDir: File = StorageLayout.exportsDir(rootDir)

    private val database: TimeFlipDatabase = sharedDatabase(appContext)

    val projects: ProjectRepository = RoomProjectRepository(database, rootDir, appContext.cacheDir)
    val photos: PhotoStore = photoStore ?: PrivatePhotoStore(appContext, rootDir)
    val preferences: UserPreferences = UserPreferences(appContext)

    /** UI port for the first-run flag, implemented on top of [preferences]. */
    val onboarding: OnboardingStore = PreferencesOnboardingStore(preferences)

    val bitmaps: PhotoBitmaps = PhotoBitmaps(photos)
    val autoAligner: AutoAligner = AutoAligner(photos, bitmaps)

    /** UI port for automatic alignment, adapting [autoAligner]. */
    val alignmentRunner: AlignmentRunner = object : AlignmentRunner {
        override suspend fun align(pair: ImagePair, frame: PixelSize): AlignmentEstimate =
            autoAligner.align(pair, frame)
    }

    val exporter: VideoExportEngine = Mp4VideoExporter(photos, exportsDir)

    /** Reconciles the database with the files on disk: missing copies, orphans, partial exports. */
    val storageRecovery: StorageRecovery = StorageRecovery(
        rootDir = rootDir,
        cacheDir = appContext.cacheDir,
        db = database,
    )

    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Runs [storageRecovery] once per process on a background dispatcher. The UI never waits for it.
     * Failures are contained here, so a damaged store cannot stop the app from opening. Nothing is
     * logged, because failure details can include file names.
     */
    val startupRecovery: Job = processScope.launch {
        runCatching { storageRecovery.recover() }
    }

    private companion object {
        const val ROOT_DIR_NAME = "timeflip"

        @Volatile
        private var sharedDb: TimeFlipDatabase? = null

        /**
         * One Room database per process. [TimeFlipDatabase.create] builds a new connection pool each
         * time, so a second container (the test seam) must reuse this instance.
         */
        fun sharedDatabase(context: Context): TimeFlipDatabase =
            sharedDb ?: synchronized(this) {
                sharedDb ?: TimeFlipDatabase.create(context).also { sharedDb = it }
            }
    }
}
