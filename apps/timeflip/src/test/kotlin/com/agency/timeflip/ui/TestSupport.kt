package com.agency.timeflip.ui

import com.agency.timeflip.domain.model.ImagePair
import com.agency.timeflip.domain.model.PhotoSlot
import com.agency.timeflip.domain.model.PhotoSourceRef
import com.agency.timeflip.domain.model.ProjectId
import com.agency.timeflip.domain.model.TimeFlipProject
import com.agency.timeflip.domain.port.PhotoStore
import com.agency.timeflip.domain.rules.ProjectRules
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Runs ViewModel coroutines eagerly on the test thread, so state can be asserted right after a call. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(private val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

/** Imports two real fake photo copies so that originalPath() resolves for both. */
suspend fun importPair(photos: PhotoStore, now: Long = 0L): ImagePair {
    val before = photos.importPhoto(PhotoSourceRef("picker://before"), PhotoSlot.BEFORE, now)
    val after = photos.importPhoto(PhotoSourceRef("picker://after"), PhotoSlot.AFTER, now)
    return ImagePair(before, after)
}

fun newTestProject(id: String, pair: ImagePair, nowEpochMs: Long = 1_000L): TimeFlipProject =
    ProjectRules.newProject(id = ProjectId(id), rawName = "Test project", pair = pair, nowEpochMs = nowEpochMs)
