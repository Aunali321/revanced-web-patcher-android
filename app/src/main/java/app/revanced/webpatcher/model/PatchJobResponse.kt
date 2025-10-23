package app.revanced.webpatcher.model

import app.revanced.webpatcher.PatchJobStatus
import java.time.Instant
import java.util.UUID

data class PatchJobResponse(
    val id: UUID,
    val status: PatchJobStatus,
    val apkName: String?,
    val patchFiles: List<String>,
    val outputFileName: String?,
    val errorMessage: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
