package app.revanced.webpatcher.routing

import app.revanced.library.PatchesOptions
import app.revanced.webpatcher.JsonMapper
import app.revanced.webpatcher.OptionParser
import app.revanced.webpatcher.PatchErrorStatus
import app.revanced.webpatcher.PatchJobRegistry
import app.revanced.webpatcher.PatchProcessingException
import app.revanced.webpatcher.model.UploadedFile
import app.revanced.webpatcher.service.PatchRequest
import app.revanced.webpatcher.service.PatchMetadataService
import app.revanced.webpatcher.service.PatchService
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.request.receiveMultipart
import io.ktor.http.CacheControl
import kotlinx.coroutines.flow.collect
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

fun Application.configurePatchRoutes() {
    val jobRegistry = PatchJobRegistry()
    val patchService = PatchService(jobRegistry)
    val metadataService = PatchMetadataService()

    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok", "timestamp" to Instant.now().toString()))
        }

        get("/patch/{id}") {
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: throw PatchProcessingException("Invalid job identifier", PatchErrorStatus.BAD_REQUEST)

            val job = jobRegistry[id]
                ?: throw PatchProcessingException("Job not found", PatchErrorStatus.BAD_REQUEST)

            call.respond(job)
        }

        get("/patch/{id}/events") {
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: throw PatchProcessingException("Invalid job identifier", PatchErrorStatus.BAD_REQUEST)

            jobRegistry.ensureJob(id)
            val events = jobRegistry.events(id)
                ?: throw PatchProcessingException("Job not found", PatchErrorStatus.BAD_REQUEST)

            call.response.cacheControl(CacheControl.NoCache(null))
            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                events.collect { event ->
                    val payload = JsonMapper.mapper.writeValueAsString(event)
                    write("event: ${event.event}\n")
                    write("data: $payload\n\n")
                    flush()
                }
            }
        }

        post("/patches/metadata") {
            val uploadedFiles = mutableListOf<File>()
            val patchBundles = mutableListOf<UploadedFile>()
            var apk: UploadedFile? = null

            val multipart = call.receiveMultipart()
            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        val savedFile = saveUpload(part).also(uploadedFiles::add)
                        val originalName = part.originalFileName
                        when (part.name) {
                            "patches" -> patchBundles += UploadedFile(savedFile, originalName)
                            "apk" -> apk = UploadedFile(savedFile, originalName)
                            else -> savedFile.delete()
                        }
                    }

                    else -> Unit
                }

                part.dispose()
            }

            val response = try {
                metadataService.describeBundles(
                    patchBundles.map(UploadedFile::file),
                    apk?.file,
                )
            } finally {
                uploadedFiles.forEach(File::delete)
            }

            call.respond(response)
        }

        post("/patch") {
            val uploadedFiles = mutableListOf<File>()

            var apk: UploadedFile? = null
            val patchBundles = mutableListOf<UploadedFile>()
            var options: PatchesOptions = emptyMap()
            var force = false
            var selectedPatches: Set<String> = emptySet()
            var requestedJobId: UUID? = null

            val multipart = call.receiveMultipart()
            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        val savedFile = saveUpload(part).also(uploadedFiles::add)
                        val originalName = part.originalFileName
                        when (part.name) {
                            "apk" -> apk = UploadedFile(savedFile, originalName)
                            "patches" -> patchBundles += UploadedFile(savedFile, originalName)
                            else -> savedFile.delete()
                        }
                    }

                    is PartData.FormItem -> {
                        when (part.name) {
                            "options" -> options = OptionParser.parse(part.value)
                            "force" -> force = part.value.toBooleanStrictOrNull() ?: false
                            "selectedPatches" -> selectedPatches = OptionParser.parseSelectedPatches(part.value)
                            "jobId" -> requestedJobId = part.value.takeIf { it.isNotBlank() }?.let {
                                runCatching { UUID.fromString(it) }.getOrElse {
                                    throw PatchProcessingException("Invalid job identifier", PatchErrorStatus.BAD_REQUEST)
                                }
                            }
                        }
                    }

                    else -> Unit
                }

                part.dispose()
            }

            if (apk == null) {
                throw PatchProcessingException("Missing APK upload", PatchErrorStatus.BAD_REQUEST)
            }

            if (patchBundles.isEmpty()) {
                throw PatchProcessingException("At least one patch bundle must be provided", PatchErrorStatus.BAD_REQUEST)
            }

            val jobId = jobRegistry.createJob(
                apk!!.originalName,
                patchBundles.mapNotNull(UploadedFile::originalName),
                requestedJobId,
            )

            val request = PatchRequest(
                apk = apk!!.file,
                patches = patchBundles.map(UploadedFile::file),
                options = options,
                force = force,
                selectedPatches = selectedPatches,
            )

            val result = try {
                patchService.patch(jobId, request)
            } catch (throwable: Throwable) {
                uploadedFiles.forEach(File::delete)
                throw when (throwable) {
                    is PatchProcessingException -> throwable
                    else -> PatchProcessingException(
                        throwable.message ?: "Failed to process patch request",
                        PatchErrorStatus.PATCH_FAILURE,
                        throwable,
                    )
                }
            }

            try {
                call.response.headers.append("X-Patch-Job-Id", jobId.toString())
                call.response.headers.append(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(
                        ContentDisposition.Parameters.FileName,
                        result.outputFile.name,
                    ).toString(),
                )

                val contentType = ContentType("application", "vnd.android.package-archive")

                call.respondOutputStream(contentType) {
                    result.outputFile.inputStream().use { input ->
                        input.copyTo(this)
                    }
                }
            } finally {
                result.close()
                uploadedFiles.forEach(File::delete)
            }
        }
    }
}

private fun saveUpload(part: PartData.FileItem): File {
    val originalName = part.originalFileName ?: "upload.bin"
    val extension = originalName.substringAfterLast('.', "").lowercase().let {
        if (it.isBlank()) "" else ".$it"
    }
    val tempFile = Files.createTempFile("web-patcher-upload-", extension).toFile()
    part.streamProvider().use { input ->
        tempFile.outputStream().use { output ->
            input.copyTo(output)
        }
    }
    return tempFile
}
