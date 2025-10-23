package app.revanced.webpatcher

import app.revanced.library.PatchesOptions
import com.fasterxml.jackson.module.kotlin.readValue

object OptionParser {
    private val mapper get() = JsonMapper.mapper

    fun parse(input: String?): PatchesOptions {
        if (input.isNullOrBlank()) return emptyMap()

        return runCatching {
            mapper.readValue<PatchesOptions>(input)
        }.getOrElse { throwable ->
            throw IllegalArgumentException("Unable to parse options JSON: ${throwable.message}", throwable)
        }
    }

    fun parseSelectedPatches(input: String?): Set<String> {
        if (input.isNullOrBlank()) return emptySet()

        return runCatching {
            mapper.readValue<Set<String>>(input)
        }.getOrElse { throwable ->
            throw IllegalArgumentException("Unable to parse selected patches JSON: ${throwable.message}", throwable)
        }
    }
}
