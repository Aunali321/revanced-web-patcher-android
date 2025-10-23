package app.revanced.webpatcher.service

import app.revanced.library.ApkUtils
import app.revanced.library.ApkUtils.applyTo
import app.revanced.library.PatchesOptions
import app.revanced.library.setOptions
import app.revanced.patcher.Patcher
import app.revanced.patcher.PatcherConfig
import app.revanced.patcher.patch.Patch
import app.revanced.patcher.patch.loadPatchesFromJar
import app.revanced.webpatcher.PatchErrorStatus
import app.revanced.webpatcher.PatchJobRegistry
import app.revanced.webpatcher.PatchProcessingException
import app.revanced.webpatcher.model.PatchLogEvent
import app.revanced.webpatcher.model.PatchLogEventType
import app.revanced.webpatcher.model.PatchLogSeverity
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashSet
import java.util.UUID
import java.util.logging.Logger

class PatchService(
    private val jobRegistry: PatchJobRegistry,
) {
    private val logger = Logger.getLogger(PatchService::class.java.name)

    fun patch(jobId: UUID, request: PatchRequest): PatchResultFile {
        jobRegistry.markRunning(jobId)

        val workspace = Files.createTempDirectory("web-patcher-$jobId-").toFile()

        return try {
            val apkCopy = request.apk.copyTo(workspace.resolve(request.apk.name), overwrite = true)
            val patchCopies = request.patches.mapIndexed { index, file ->
                val name = file.name.ifBlank { "patch-${index + 1}.rvp" }
                file.copyTo(workspace.resolve(name), overwrite = true)
            }

            val loader = loadPatchesFromJar(patchCopies.toSet())

            val patcherTemp = workspace.resolve("patcher-temp").also { it.mkdirs() }
            jobRegistry.emit(jobId, PatchLogEvent(PatchLogEventType.JOB_STARTED, null, "Job started", Instant.now(), PatchLogSeverity.INFO))

            val patcherResult = executePatcher(
                jobId,
                loader,
                request.options,
                request.force,
                apkCopy,
                patcherTemp,
                request.selectedPatches,
            )

            val workingApk = apkCopy.copyTo(
                workspace.resolve("${apkCopy.nameWithoutExtension}-work.${apkCopy.extension}"),
                overwrite = true,
            )

            patcherResult.applyTo(workingApk)

            val outputApk = workspace.resolve("${apkCopy.nameWithoutExtension}-patched.${apkCopy.extension}")
            val keystoreFile = workspace.resolve("signing.keystore")

            ApkUtils.signApk(
                workingApk,
                outputApk,
                signer = "ReVanced",
                keyStoreDetails = ApkUtils.KeyStoreDetails(keystoreFile),
            )

            jobRegistry.markSuccess(jobId, outputApk.name)
            jobRegistry.emit(jobId, PatchLogEvent(PatchLogEventType.JOB_COMPLETED, null, "Job completed", Instant.now(), PatchLogSeverity.INFO))

            PatchResultFile(outputApk, workspace)
        } catch (cause: PatchProcessingException) {
            workspace.deleteRecursively()
            jobRegistry.markFailure(jobId, cause.message ?: "Patch failed")
            jobRegistry.emit(
                jobId,
                PatchLogEvent(
                    PatchLogEventType.JOB_FAILED,
                    null,
                    cause.message ?: "Patch failed",
                    Instant.now(),
                    PatchLogSeverity.ERROR,
                ),
            )
            throw cause
        } catch (cause: Throwable) {
            workspace.deleteRecursively()
            jobRegistry.markFailure(jobId, cause.message ?: "Patch failed")
            jobRegistry.emit(
                jobId,
                PatchLogEvent(
                    PatchLogEventType.JOB_FAILED,
                    null,
                    cause.message ?: "Patch failed",
                    Instant.now(),
                    PatchLogSeverity.ERROR,
                ),
            )
            throw PatchProcessingException(
                cause.message ?: "Failed to process patches",
                PatchErrorStatus.PATCH_FAILURE,
                cause,
            )
        }
    }

    private fun executePatcher(
        jobId: UUID,
        patches: Set<Patch<*>>,
        options: PatchesOptions,
        force: Boolean,
        apkFile: File,
        tempDir: File,
        selectedPatches: Set<String>,
    ) = Patcher(
        PatcherConfig(
            apkFile,
            tempDir,
            aaptBinaryPath = System.getenv("AAPT2_BINARY"),
            frameworkFileDirectory = tempDir.absolutePath,
        ),
    ).use { patcher ->
        val packageMetadata = patcher.context.packageMetadata
        logger.info("Processing job $jobId for package ${packageMetadata.packageName}")

        val selected = selectPatches(
            patches,
            packageMetadata.packageName,
            packageMetadata.packageVersion,
            options,
            force,
            selectedPatches,
        )

        logger.info("Executing ${selected.size} patches for job $jobId")
        val orderedPatches = selected.filter { it.name != null }.sortedBy { it.name }

        orderedPatches.forEach { patch ->
            jobRegistry.emit(
                jobId,
                PatchLogEvent(
                    PatchLogEventType.PATCH_QUEUED,
                    patch.name,
                    "Queued patch ${patch.name}",
                    Instant.now(),
                    PatchLogSeverity.INFO,
                ),
            )
        }

        patcher += selected

        val startTimes = mutableMapOf<String, Instant>()
        val executionOrder = orderedPatches.iterator()

        runBlocking {
            patcher().collect { result ->
                val patchName = result.patch.name
                if (patchName != null) {
                    ensurePatchStarted(jobId, patchName, startTimes, executionOrder)
                }

                result.exception?.let { exception ->
                    val message = buildString {
                        append("Patch \"")
                        append(result.patch)
                        append("\" failed: ")
                        append(exception.message ?: "Unexpected error")
                    }
                    jobRegistry.emit(
                        jobId,
                        PatchLogEvent(
                            PatchLogEventType.PATCH_FAILED,
                            patchName,
                            message,
                            Instant.now(),
                            PatchLogSeverity.ERROR,
                        ),
                    )
                    throw PatchProcessingException(
                        "Patch \"${result.patch}\" failed: ${exception.message}",
                        PatchErrorStatus.PATCH_FAILURE,
                        exception,
                    )
                }

                if (patchName != null) {
                    val duration = startTimes[patchName]?.let { Duration.between(it, Instant.now()).toMillis() }
                    val message = if (duration != null) {
                        "Patch $patchName completed in ${duration}ms"
                    } else {
                        "Patch $patchName completed"
                    }
                    jobRegistry.emit(
                        jobId,
                        PatchLogEvent(
                            PatchLogEventType.PATCH_SUCCEEDED,
                            patchName,
                            message,
                            Instant.now(),
                            PatchLogSeverity.INFO,
                        ),
                    )
                }
            }
        }

        patcher.get()
    }

    private fun selectPatches(
        patches: Set<Patch<*>>,
        packageName: String,
        packageVersion: String,
        options: PatchesOptions,
        force: Boolean,
        selectedPatches: Set<String>,
    ): Set<Patch<*>> {
        val requestedPatches = options.keys
        val selectedExplicitly = selectedPatches.ifEmpty { null }
        val selected = LinkedHashSet<Patch<*>>()

        fun include(patch: Patch<*>) {
            if (!selected.add(patch)) return
            patch.dependencies.forEach(::include)
        }

        patches.forEach { patch ->
            val name = patch.name ?: return@forEach

            if (!isCompatible(patch, packageName, packageVersion, force)) {
                logger.fine("Patch $name is incompatible with $packageName $packageVersion")
                return@forEach
            }

            val shouldUse = when {
                selectedExplicitly != null -> name in selectedExplicitly || name in requestedPatches
                else -> patch.use || name in requestedPatches
            }
            if (!shouldUse) return@forEach

            include(patch)
        }

        // Apply options after final selection so dependencies receive shared options if present.
        selected.setOptions(options)

        return selected
    }

    private fun isCompatible(
        patch: Patch<*>,
        packageName: String,
        packageVersion: String,
        force: Boolean,
    ): Boolean {
        if (force) return true

        val compatiblePackages = patch.compatiblePackages ?: return true
        val target = compatiblePackages.firstOrNull { it.first == packageName } ?: return false

        val versions = target.second ?: return true
        if (versions.isEmpty()) return false

        return packageVersion in versions
    }

    private fun ensurePatchStarted(
        jobId: UUID,
        patchName: String,
        startTimes: MutableMap<String, Instant>,
        executionOrder: Iterator<Patch<*>>,
    ) {
        if (startTimes.containsKey(patchName)) return

        while (executionOrder.hasNext()) {
            val nextPatch = executionOrder.next()
            val nextName = nextPatch.name ?: continue
            val timestamp = Instant.now()
            startTimes[nextName] = timestamp
            jobRegistry.emit(
                jobId,
                PatchLogEvent(
                    PatchLogEventType.PATCH_STARTED,
                    nextName,
                    "Patch $nextName started",
                    timestamp,
                    PatchLogSeverity.INFO,
                ),
            )
            if (nextName == patchName) {
                break
            }
        }
    }
}

data class PatchRequest(
    val apk: File,
    val patches: List<File>,
    val options: PatchesOptions,
    val force: Boolean,
    val selectedPatches: Set<String>,
)

class PatchResultFile(
    val outputFile: File,
    private val workspace: File,
) : Closeable {
    override fun close() {
        workspace.deleteRecursively()
    }
}
