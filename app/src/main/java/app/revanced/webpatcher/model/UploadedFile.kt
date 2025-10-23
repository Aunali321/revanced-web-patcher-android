package app.revanced.webpatcher.model

import java.io.File

data class UploadedFile(
    val file: File,
    val originalName: String?,
)
