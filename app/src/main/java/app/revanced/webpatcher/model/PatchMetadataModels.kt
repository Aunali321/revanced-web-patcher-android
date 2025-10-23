package app.revanced.webpatcher.model

import java.time.Instant

data class PatchMetadataResponse(
    val bundles: List<PatchBundleMetadata>,
    val patches: List<PatchMetadata>,
    val targetPackage: TargetPackageMetadata?,
)

data class PatchBundleMetadata(
    val name: String,
    val patchCount: Int,
)

data class PatchMetadata(
    val name: String,
    val description: String?,
    val defaultSelected: Boolean,
    val bundleNames: List<String>,
    val type: PatchType,
    val dependencies: List<String>,
    val compatiblePackages: List<PatchCompatiblePackage>,
    val options: List<PatchOptionMetadata>,
    val isCompatible: Boolean,
    val isVersionCompatible: Boolean,
    val incompatibilityReason: String?,
)

data class TargetPackageMetadata(
    val packageName: String,
    val packageVersion: String?,
)

data class PatchCompatiblePackage(
    val packageName: String,
    val versions: List<String>?,
)

data class PatchOptionMetadata(
    val key: String,
    val title: String?,
    val description: String?,
    val required: Boolean,
    val type: PatchOptionType,
    val defaultValue: Any?,
    val allowedValues: Map<String, Any?>?,
)

enum class PatchOptionType {
    STRING,
    BOOLEAN,
    INT,
    LONG,
    FLOAT,
    DOUBLE,
    STRING_LIST,
    BOOLEAN_LIST,
    INT_LIST,
    LONG_LIST,
    FLOAT_LIST,
    DOUBLE_LIST,
    UNKNOWN,
}

enum class PatchType {
    BYTECODE,
    RESOURCE,
    RAW_RESOURCE,
}

data class PatchLogEvent(
    val event: PatchLogEventType,
    val patch: String?,
    val message: String,
    val timestamp: Instant,
    val severity: PatchLogSeverity,
)

enum class PatchLogEventType {
    JOB_PREPARED,
    JOB_STARTED,
    JOB_COMPLETED,
    JOB_FAILED,
    PATCH_QUEUED,
    PATCH_STARTED,
    PATCH_SUCCEEDED,
    PATCH_FAILED,
    INFO,
}

enum class PatchLogSeverity {
    INFO,
    WARN,
    ERROR,
}
