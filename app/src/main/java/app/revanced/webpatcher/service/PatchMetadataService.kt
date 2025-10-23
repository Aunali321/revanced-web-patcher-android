package app.revanced.webpatcher.service

import app.revanced.patcher.Patcher
import app.revanced.patcher.PatcherConfig
import app.revanced.patcher.patch.BytecodePatch
import app.revanced.patcher.patch.Option
import app.revanced.patcher.patch.Patch
import app.revanced.patcher.patch.RawResourcePatch
import app.revanced.patcher.patch.ResourcePatch
import app.revanced.patcher.patch.loadPatchesFromJar
import app.revanced.webpatcher.PatchErrorStatus
import app.revanced.webpatcher.PatchProcessingException
import app.revanced.webpatcher.model.PatchBundleMetadata
import app.revanced.webpatcher.model.PatchCompatiblePackage
import app.revanced.webpatcher.model.PatchMetadata
import app.revanced.webpatcher.model.PatchMetadataResponse
import app.revanced.webpatcher.model.PatchOptionMetadata
import app.revanced.webpatcher.model.PatchOptionType
import app.revanced.webpatcher.model.PatchType
import app.revanced.webpatcher.model.TargetPackageMetadata
import java.io.File
import java.nio.file.Files
import kotlin.collections.LinkedHashSet
import kotlin.reflect.KClass
import kotlin.reflect.KType

class PatchMetadataService {
    fun describeBundles(patchBundles: List<File>, apkFile: File?): PatchMetadataResponse {
        if (patchBundles.isEmpty()) {
            throw PatchProcessingException("At least one patch bundle must be provided", PatchErrorStatus.BAD_REQUEST)
        }

        val loader = runCatching {
            loadPatchesFromJar(patchBundles.toSet())
        }.getOrElse { throwable ->
            throw PatchProcessingException(
                "Failed to load patches: ${throwable.message}",
                PatchErrorStatus.PATCH_FAILURE,
                throwable,
            )
        }

        val bundles = loader.byPatchesFile.entries.map { (file, patches) ->
            PatchBundleMetadata(
                name = file.name,
                patchCount = patches.count { it.name != null },
            )
        }

        val patchesByName = mutableMapOf<String, PatchAccumulator>()

        loader.byPatchesFile.forEach { (file, patches) ->
            patches.forEach { patch ->
                val name = patch.name ?: return@forEach
                val accumulator = patchesByName.getOrPut(name) { PatchAccumulator() }
                accumulator.variants += PatchVariant(patch, file.name)
            }
        }

        val targetPackage = apkFile?.let { resolveTargetPackage(it) }

        val metadata = patchesByName.values.mapNotNull { accumulator ->
            val variant = accumulator.selectVariant(targetPackage)
            val patch = variant.patch
            val compatibility = evaluateCompatibility(patch, targetPackage)
            PatchMetadata(
                name = patch.name ?: "", // already ensured non-null
                description = patch.description,
                defaultSelected = patch.use,
                bundleNames = variant.bundleNames.sorted(),
                type = patch.toPatchType(),
                dependencies = patch.dependencies.mapNotNull { it.name }.sorted(),
                compatiblePackages = patch.compatiblePackages?.map { (packageName, versions) ->
                    PatchCompatiblePackage(packageName, versions?.sorted())
                }?.sortedBy { it.packageName } ?: emptyList(),
                options = patch.options.values.map { option ->
                    option.toMetadata()
                }.sortedBy { it.key },
                isCompatible = compatibility.packageMatch,
                isVersionCompatible = compatibility.versionMatch,
                incompatibilityReason = compatibility.reason,
            )
        }.sortedBy { it.name.lowercase() }

        return PatchMetadataResponse(
            bundles = bundles.sortedBy { it.name.lowercase() },
            patches = metadata,
            targetPackage = targetPackage,
        )
    }

    private fun Patch<*>.toPatchType(): PatchType = when (this) {
        is BytecodePatch -> PatchType.BYTECODE
        is ResourcePatch -> PatchType.RESOURCE
        is RawResourcePatch -> PatchType.RAW_RESOURCE
        else -> PatchType.BYTECODE
    }

    private fun Option<*>.toMetadata(): PatchOptionMetadata {
        val optionType = resolveOptionType(type)
        return PatchOptionMetadata(
            key = key,
            title = title,
            description = description,
            required = required,
            type = optionType,
            defaultValue = normalizeValue(default),
            allowedValues = values?.let { map ->
                val normalized = LinkedHashMap<String, Any?>(map.size)
                map.forEach { (label, optionValue) ->
                    normalized[label] = normalizeValue(optionValue)
                }
                normalized.takeIf { it.isNotEmpty() }
            },
        )
    }

    private fun resolveOptionType(type: KType): PatchOptionType {
        val classifier = type.classifier as? KClass<*> ?: return PatchOptionType.UNKNOWN

        return when (classifier) {
            String::class -> PatchOptionType.STRING
            Boolean::class -> PatchOptionType.BOOLEAN
            Int::class -> PatchOptionType.INT
            Long::class -> PatchOptionType.LONG
            Float::class -> PatchOptionType.FLOAT
            Double::class -> PatchOptionType.DOUBLE
            List::class, MutableList::class -> {
                val argumentType = type.arguments.firstOrNull()?.type
                val argumentClassifier = argumentType?.classifier as? KClass<*>
                when (argumentClassifier) {
                    String::class -> PatchOptionType.STRING_LIST
                    Boolean::class -> PatchOptionType.BOOLEAN_LIST
                    Int::class -> PatchOptionType.INT_LIST
                    Long::class -> PatchOptionType.LONG_LIST
                    Float::class -> PatchOptionType.FLOAT_LIST
                    Double::class -> PatchOptionType.DOUBLE_LIST
                    else -> PatchOptionType.UNKNOWN
                }
            }
            else -> PatchOptionType.UNKNOWN
        }
    }

    private fun normalizeValue(value: Any?): Any? = when (value) {
        null -> null
        is String, is Number, is Boolean -> value
        is Iterable<*> -> value.map { normalizeValue(it) }
        is Array<*> -> value.map { normalizeValue(it) }
        is Map<*, *> -> value.entries.associate { entry ->
            val key = entry.key?.toString() ?: ""
            key to normalizeValue(entry.value)
        }
        else -> value.toString()
    }

    private fun resolveTargetPackage(apkFile: File): TargetPackageMetadata? {
        val workspace = Files.createTempDirectory("metadata-apk-").toFile()
        return try {
            val apkCopy = apkFile.copyTo(workspace.resolve(apkFile.name), overwrite = true)
            val tempDir = workspace.resolve("patcher-temp").also { it.mkdirs() }
            Patcher(
                PatcherConfig(
                    apkCopy,
                    tempDir,
                    aaptBinaryPath = System.getenv("AAPT2_BINARY"),
                    frameworkFileDirectory = tempDir.absolutePath,
                ),
            ).use { patcher ->
                val metadata = patcher.context.packageMetadata
                TargetPackageMetadata(metadata.packageName, metadata.packageVersion)
            }
        } catch (throwable: Throwable) {
            null
        } finally {
            workspace.deleteRecursively()
        }
    }

    private fun evaluateCompatibility(
        patch: Patch<*>,
        target: TargetPackageMetadata?,
    ): CompatibilityResult {
        val compatiblePackages = patch.compatiblePackages
            ?: return CompatibilityResult(packageMatch = true, versionMatch = true, reason = null)

        if (target == null) {
            return CompatibilityResult(packageMatch = true, versionMatch = true, reason = null)
        }

        val packageName = target.packageName
        val packageVersion = target.packageVersion

        val targetPackage = compatiblePackages.firstOrNull { it.first == packageName }
            ?: return CompatibilityResult(
                packageMatch = false,
                versionMatch = false,
                reason = buildPackageListReason(compatiblePackages),
            )

        val versions = targetPackage.second ?: return CompatibilityResult(true, true, null)
        if (versions.isEmpty()) {
            return CompatibilityResult(true, false, "No compatible versions listed")
        }

        if (packageVersion != null && packageVersion in versions) {
            return CompatibilityResult(true, true, null)
        }

        val formattedVersions = versions.joinToString(", ")
        return CompatibilityResult(
            packageMatch = true,
            versionMatch = false,
            reason = "Compatible versions: $formattedVersions",
        )
    }

    private fun buildPackageListReason(packages: Set<Pair<String, Set<String>?>>): String {
        val names = packages.joinToString(", ") { (name, versions) ->
            if (versions == null || versions.isEmpty()) name else "$name (${versions.joinToString(", ")})"
        }
        return "Compatible packages: $names"
    }

    private class PatchAccumulator {
        val variants = mutableListOf<PatchVariant>()

        fun selectVariant(target: TargetPackageMetadata?): VariantSelection {
            require(variants.isNotEmpty()) { "No variants available" }

            val ordered = buildList {
                target?.let {
                    addAll(variants.filter { variant -> variant.matchesTargetStrict(it) })
                    addAll(variants.filter { variant -> variant.matchesPackage(it.packageName) })
                }
                addAll(variants.filter { it.patch.compatiblePackages == null })
                addAll(variants)
            }

            val selected = ordered.firstOrNull() ?: variants.first()
            val bundleNames = variants.filter { it.patch === selected.patch }
                .mapTo(linkedSetOf()) { it.bundleName }

            return VariantSelection(selected.patch, bundleNames)
        }
    }

    private data class PatchVariant(val patch: Patch<*>, val bundleName: String) {
        fun matchesTargetStrict(target: TargetPackageMetadata): Boolean {
            val compat = patch.compatiblePackages ?: return false
            val entry = compat.firstOrNull { it.first == target.packageName } ?: return false
            val versions = entry.second ?: return target.packageVersion != null
            val version = target.packageVersion ?: return false
            return version in versions
        }

        fun matchesPackage(packageName: String?): Boolean {
            if (packageName == null) return false
            val compat = patch.compatiblePackages ?: return false
            return compat.any { it.first == packageName }
        }
    }

    private data class VariantSelection(
        val patch: Patch<*>,
        val bundleNames: LinkedHashSet<String>,
    )

    private data class CompatibilityResult(
        val packageMatch: Boolean,
        val versionMatch: Boolean,
        val reason: String?,
    )
}
