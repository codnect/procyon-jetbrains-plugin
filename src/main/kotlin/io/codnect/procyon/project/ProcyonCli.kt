package io.codnect.procyon.project

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import io.codnect.procyon.ProcyonBundle
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Installs the Procyon CLI with the Go SDK and generates applications with it. */
object ProcyonCli {

    private const val PACKAGE = "go.codnect.io/procyon/cmd/procyon@latest"

    /**
     * An application that `procyon init` wrote to a scratch directory. [tidyProblem] is what `go mod tidy`
     * reported when it failed, which leaves the dependencies unresolved.
     */
    class Generated(val directory: Path, private val scratch: Path, val tidyProblem: String?) {

        /** Copies the files that [accept] takes, given their path in the application, into [target]. */
        fun copyTo(target: Path, accept: (String) -> Boolean) {
            Files.walk(directory).use { paths ->
                paths.filter { Files.isRegularFile(it) }.forEach { source ->
                    val relative = directory.relativize(source).toString()
                    if (!accept(relative)) return@forEach

                    val destination = target.resolve(relative)
                    Files.createDirectories(destination.parent)
                    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }

        /**
         * Replaces [names] in [target] through the IDE's file system, so that the IDE knows the new content and
         * cannot write back an older one. A file that is not there yet is created.
         */
        fun replaceIn(target: Path, names: Collection<String>) {
            val fileSystem = LocalFileSystem.getInstance()
            val directory = fileSystem.refreshAndFindFileByNioFile(target) ?: return
            // The files written straight to the disk are not known to the IDE yet.
            directory.refresh(false, true)

            WriteAction.run<java.io.IOException> {
                for (name in names) {
                    val source = this@Generated.directory.resolve(name)
                    if (!Files.isRegularFile(source)) continue

                    val text = Files.readString(source)
                    val file = directory.findChild(name) ?: directory.createChildData(this, name)
                    VfsUtil.saveText(file, text)
                }
            }
        }

        fun delete() {
            scratch.toFile().deleteRecursively()
        }
    }

    /**
     * Installs the CLI, runs `procyon init <name> [--sample <sample>]` and `go mod tidy` in a scratch
     * directory, and returns the result. Nothing is written into the project yet.
     */
    fun generate(go: String, name: String, sample: ProcyonTemplate?, indicator: ProgressIndicator): Generated {
        indicator.text = ProcyonBundle.message("project.installing.cli")
        run(GeneralCommandLine(go, "install", PACKAGE))

        val cli = locateCli(go)

        indicator.text = ProcyonBundle.message("project.initializing")
        val scratch = Files.createTempDirectory("procyon-init")
        try {
            val init = GeneralCommandLine(cli, "init", name).withWorkDirectory(scratch.toFile())
            if (sample != null) {
                init.addParameters("--sample", sample.id)
            }
            run(init)

            // `procyon init` does not resolve the dependencies, so go.sum is incomplete and the Go plugin
            // cannot resolve the Procyon packages. A failure here, say without a network, does not stop the
            // project from being created, but it is reported once the project is open.
            val directory = scratch.resolve(name)
            indicator.text = ProcyonBundle.message("project.resolving")
            val tidy = ExecUtil.execAndGetOutput(GeneralCommandLine(go, "mod", "tidy").withWorkDirectory(directory.toFile()))
            val problem = if (tidy.exitCode == 0) null else tidy.stderr.ifBlank { tidy.stdout }.trim()

            return Generated(directory, scratch, problem)
        } catch (e: Throwable) {
            scratch.toFile().deleteRecursively()
            throw e
        }
    }

    private fun locateCli(go: String): String {
        val gobin = env(go, "GOBIN")
        val dir = gobin.ifBlank {
            env(go, "GOPATH").substringBefore(File.pathSeparatorChar) + File.separator + "bin"
        }
        return Path.of(dir, "procyon").toString()
    }

    private fun env(go: String, key: String): String =
        run(GeneralCommandLine(go, "env", key)).trim()

    private fun run(commandLine: GeneralCommandLine): String {
        val output = ExecUtil.execAndGetOutput(commandLine.withEnvironment("GOFLAGS", "-mod=mod"))
        if (output.exitCode != 0) {
            error("`${commandLine.commandLineString}` failed:\n${output.stderr.ifBlank { output.stdout }}")
        }
        return output.stdout
    }
}
