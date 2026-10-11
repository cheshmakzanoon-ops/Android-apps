package com.agency.photoduel.engine.pure

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure engine package is compiled by the plain JVM core module. This test keeps it that way even
 * if somebody builds it inside the Android module, where a stray import would still compile.
 */
class PurePackageImportsTest {

    private fun pureSourceDir(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "src/main/kotlin/com/agency/photoduel/engine/pure")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("engine/pure sources not found above ${System.getProperty("user.dir")}")
    }

    @Test
    fun pureEngineSourcesImportNoAndroidCoilOrOkhttpTypes() {
        val forbidden = listOf("android.", "androidx.", "coil.", "coil3.", "okhttp3.", "okio.")
        val files = pureSourceDir().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("no pure engine sources found", files.isNotEmpty())
        for (file in files) {
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("import ") || trimmed.startsWith("package ")) {
                    for (prefix in forbidden) {
                        assertTrue(
                            "${file.name}:${index + 1} imports a forbidden package: $trimmed",
                            !trimmed.removePrefix("import ").removePrefix("package ").startsWith(prefix),
                        )
                    }
                }
            }
        }
    }
}
