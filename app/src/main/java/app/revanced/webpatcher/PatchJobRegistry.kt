package app.revanced.webpatcher

import app.revanced.webpatcher.model.PatchJobResponse
import app.revanced.webpatcher.model.PatchLogEvent
import app.revanced.webpatcher.model.PatchLogEventType
import app.revanced.webpatcher.model.PatchLogSeverity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class PatchJobStatus {
    CREATED,
    RUNNING,
    SUCCESS,
    FAILED,
}

private data class PatchJob(
    val id: UUID,
    val apkName: String?,
    val patchFiles: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val status: PatchJobStatus,
    val outputFileName: String? = null,
    val errorMessage: String? = null,
    val events: MutableSharedFlow<PatchLogEvent>,
)

class PatchJobRegistry {
    private companion object {
        private const val EVENT_REPLAY = 256
    }

    private val jobs = ConcurrentHashMap<UUID, PatchJob>()

    operator fun get(id: UUID): PatchJobResponse? = jobs[id]?.toResponse()

    fun ensureJob(id: UUID): PatchJobResponse? {
        val now = Instant.now()
        val existed = jobs.containsKey(id)
        val job = jobs.computeIfAbsent(id) {
            PatchJob(
                id = id,
                apkName = null,
                patchFiles = emptyList(),
                createdAt = now,
                updatedAt = now,
                status = PatchJobStatus.CREATED,
                events = MutableSharedFlow(replay = EVENT_REPLAY, extraBufferCapacity = EVENT_REPLAY),
            )
        }
        if (!existed) {
            job.events.tryEmit(
                PatchLogEvent(
                    PatchLogEventType.JOB_PREPARED,
                    patch = null,
                    message = "Job prepared",
                    timestamp = now,
                    severity = PatchLogSeverity.INFO,
                ),
            )
        }
        return job.toResponse()
    }

    fun events(id: UUID): SharedFlow<PatchLogEvent>? = jobs[id]?.events

    fun emit(id: UUID, event: PatchLogEvent) {
        jobs[id]?.events?.tryEmit(event)
    }

    fun createJob(apkName: String?, patchFiles: List<String>, requestedId: UUID? = null): UUID {
        val now = Instant.now()
        val id = requestedId ?: UUID.randomUUID()

        var created = false

        val updated = jobs.compute(id) { _, existing ->
            val base = existing ?: PatchJob(
                id = id,
                apkName = null,
                patchFiles = emptyList(),
                createdAt = now,
                updatedAt = now,
                status = PatchJobStatus.CREATED,
                events = MutableSharedFlow(replay = EVENT_REPLAY, extraBufferCapacity = EVENT_REPLAY),
            ).also { created = true }

            base.copy(
                apkName = apkName,
                patchFiles = patchFiles,
                status = PatchJobStatus.CREATED,
                createdAt = base.createdAt,
                updatedAt = now,
            )
        }

        if (created) {
            updated?.events?.tryEmit(
                PatchLogEvent(
                    PatchLogEventType.JOB_PREPARED,
                    patch = null,
                    message = "Job prepared",
                    timestamp = now,
                    severity = PatchLogSeverity.INFO,
                ),
            )
        }

        return id
    }

    fun markRunning(id: UUID) = update(id) { job ->
        job.copy(status = PatchJobStatus.RUNNING, updatedAt = Instant.now())
    }

    fun markSuccess(id: UUID, outputFileName: String) = update(id) { job ->
        job.copy(
            status = PatchJobStatus.SUCCESS,
            outputFileName = outputFileName,
            updatedAt = Instant.now(),
        )
    }

    fun markFailure(id: UUID, message: String) = update(id) { job ->
        job.copy(
            status = PatchJobStatus.FAILED,
            errorMessage = message,
            updatedAt = Instant.now(),
        )
    }

    private fun update(id: UUID, transformer: (PatchJob) -> PatchJob) {
        jobs.computeIfPresent(id) { _, job -> transformer(job) }
    }

    private fun PatchJob.toResponse() = PatchJobResponse(
        id = id,
        status = status,
        apkName = apkName,
        patchFiles = patchFiles,
        outputFileName = outputFileName,
        errorMessage = errorMessage,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
