package com.agency.photoduel.ui.state

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ui/state is compiled by the plain JVM core module and must stay free of Android and Compose. The JVM
 * build would already fail on an android.* import; this test also names the file and line, and it guards
 * the package when it is compiled inside the Android module, where such an import would compile.
 */
class UiStatePackageImportsTest {

    private fun stateSourceDir(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "src/main/kotlin/com/agency/photoduel/ui/state")
            if (candidate.isDirectory) return candidate
            val nested = File(dir, "apps/photoduel/src/main/kotlin/com/agency/photoduel/ui/state")
            if (nested.isDirectory) return nested
            dir = dir.parentFile
        }
        throw AssertionError("ui/state sources not found above ${System.getProperty("user.dir")}")
    }

    @Test
    fun stateSourcesImportOnlyDomainCoroutinesAndTheJdk() {
        val forbidden = listOf("android.", "androidx.", "coil.", "coil3.", "okhttp3.", "okio.", "com.agency.photoduel.ui.UiDependencies")
        val allowed = listOf("com.agency.photoduel.domain.", "com.agency.photoduel.ui.state.", "kotlinx.coroutines.", "kotlin.", "java.")
        val files = stateSourceDir().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("no ui/state sources found", files.size >= 10)
        for (file in files) {
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("import ")) return@forEachIndexed
                val imported = trimmed.removePrefix("import ").trim()
                val where = "${file.name}:${index + 1}"
                assertTrue("$where imports a forbidden package: $imported", forbidden.none { imported.startsWith(it) })
                assertTrue("$where imports outside the allowed packages: $imported", allowed.any { imported.startsWith(it) })
            }
        }
    }
}
