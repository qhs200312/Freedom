package com.v2ray.ang.root

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class RootProcessScriptTest {
    private val bash = listOf(File("C:/Program Files/Git/bin/bash.exe"), File("/bin/bash"))
        .firstOrNull(File::isFile)

    @Test
    fun coreDeathAndHelperDeathCleanRoutesButRetiredSessionCannotCleanReplacement() {
        assumeTrue("A POSIX shell is required for executable watchdog simulation", bash != null)
        for (scenario in listOf("core-dead", "helper-dead", "replaced")) {
            val directory = Files.createTempDirectory("freedom-root-watchdog").toFile()
            try {
                val path = directory.absolutePath.replace('\\', '/')
                File(directory, "session").writeText("token")
                File(directory, "helper.pid").writeText("4243")
                File(directory, "watchdog.pid").writeText("4244")
                File(directory, "teardown_rules.sh").writeText(
                    "printf cleaned > ${RootProcessScript.quote("$path/cleaned")}\n")
                val harness = """
                    ticks=0
                    awk() {
                      case "${'$'}2" in
                        /proc/4242/stat)
                          if [ "$scenario" != core-dead ] || [ "${'$'}ticks" -eq 0 ]; then printf 100; fi;;
                        /proc/4243/stat)
                          if [ "$scenario" != helper-dead ] || [ "${'$'}ticks" -eq 0 ]; then printf 200; fi;;
                      esac
                    }
                    kill() { printf '%s\n' "${'$'}*" >> ${RootProcessScript.quote("$path/kills")}; }
                    sleep() {
                      ticks=${'$'}((ticks+1))
                      [ "${'$'}ticks" -lt 3 ] || exit 9
                      if [ "$scenario" = replaced ]; then printf new-token > ${RootProcessScript.quote("$path/session")}; fi
                    }
                """.trimIndent() + "\n" + RootProcessScript.watchdog(4242, "token", path, "$path/helper.pid")
                val script = File(directory, "harness.sh").apply { writeText(harness) }
                val process = ProcessBuilder(bash!!.absolutePath, script.absolutePath.replace('\\', '/'))
                    .redirectErrorStream(true).start()
                assertTrue("Watchdog must finish without external network requests", process.waitFor(10, TimeUnit.SECONDS))
                assertEquals(process.inputStream.bufferedReader().readText(), 0, process.exitValue())
                assertEquals(scenario != "replaced", File(directory, "cleaned").exists())
                assertEquals(scenario == "helper-dead", File(directory, "kills").exists())
                if (scenario == "helper-dead") assertEquals("4242", File(directory, "kills").readText().trim())
            } finally { directory.deleteRecursively() }
        }
    }

    @Test
    fun quotedPathsAndPidCleanupAreValidShellSyntax() {
        assumeTrue(bash != null)
        val file = Files.createTempFile("freedom-root-quoted", ".sh").toFile()
        try {
            file.writeText(RootProcessScript.stopFunction +
                RootProcessScript.watchdog(4242, "token", "/data/private path/it's root", "/data/helper.pid"))
            val process = ProcessBuilder(bash!!.absolutePath, "-n", file.absolutePath.replace('\\', '/'))
                .redirectErrorStream(true).start()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(process.inputStream.bufferedReader().readText(), 0, process.exitValue())
        } finally { file.delete() }
    }
}
