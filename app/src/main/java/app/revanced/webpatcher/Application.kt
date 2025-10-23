/*
 * ReVanced Web Patcher - Backend Service
 * Copyright (C) 2025 ReVanced Web Patcher Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 */

package app.revanced.webpatcher

import app.revanced.library.logging.Logger
import app.revanced.webpatcher.routing.configurePatchRoutes
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respond
import java.net.URI
import org.slf4j.event.Level

fun main() {
    Logger.setDefault()
    val port = System.getenv("PORT")?.toIntOrNull() ?: 3000
    embeddedServer(Netty, port = port) { configureServer() }.start(wait = true)
}

private fun Application.configureServer() {
    install(DefaultHeaders)
    install(CallLogging) { level = Level.INFO }

    val allowedOrigins =
            parseAllowedOrigins(
                    (System.getenv("ALLOWED_ORIGINS") ?: "https://rv.aun.rest")
                            .split(',')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() },
            )

    install(PrivateNetworkAccessPlugin) {
        this.allowedOrigins = emptySet() // Empty set = allow all
    }

    install(CORS) {
        // Allow all origins
        anyHost()

        allowHeader(HttpHeaders.AccessControlAllowOrigin)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowHeaders { true }
        allowSameOrigin
        allowNonSimpleContentTypes = true
        allowCredentials = true
        exposeHeader("X-Patch-Job-Id")
        exposeHeader(HttpHeaders.ContentDisposition)
    }

    install(ContentNegotiation) {
        jackson {
            registerModule(JavaTimeModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }
    }
    install(StatusPages) {
        exception<PatchProcessingException> { call, cause ->
            call.respondPatchError(cause.message ?: "Failed to patch APK", cause.status)
        }

        exception<IllegalArgumentException> { call, cause ->
            call.respondPatchError(cause.message ?: "Invalid request", PatchErrorStatus.BAD_REQUEST)
        }

        exception<Throwable> { call, cause ->
            call.respondPatchError(
                    "Unexpected server error",
                    PatchErrorStatus.SERVER_ERROR,
                    logCause = cause,
            )
        }
    }

    configurePatchRoutes()
}

private data class AllowedOrigin(
        val normalized: String,
        val scheme: String,
        val hostForCors: String
)

private val DEFAULT_ALLOWED_ORIGINS =
        listOf(
                "https://rv.aun.rest",
        )

private fun parseAllowedOrigins(entries: List<String>): List<AllowedOrigin> =
        (entries + DEFAULT_ALLOWED_ORIGINS).mapNotNull(::parseOrigin).distinctBy { it.normalized }

private fun parseOrigin(value: String): AllowedOrigin? {
    val trimmed = value.removeSuffix("/")
    val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    return try {
        val uri = URI(candidate)
        val scheme = (uri.scheme ?: "https").lowercase()
        val host = (uri.host ?: uri.authority ?: return null).lowercase()
        val port = uri.port.takeIf { it != -1 }
        val hostForCors = if (port != null) "$host:$port" else host
        val normalized = buildString {
            append(scheme)
            append("://")
            append(host)
            if (port != null) {
                append(":")
                append(port)
            }
        }
        AllowedOrigin(normalized, scheme, hostForCors)
    } catch (_: Exception) {
        null
    }
}

private fun normalizeOrigin(origin: String?): String? = origin?.let(::parseOrigin)?.normalized

private class PrivateNetworkAccessConfig {
    var allowedOrigins: Set<String> = emptySet()
}

private val PrivateNetworkAccessPlugin =
        createApplicationPlugin("PrivateNetworkAccess", ::PrivateNetworkAccessConfig) {
            val allowlist = pluginConfig.allowedOrigins
            val allowAll = allowlist.isEmpty()
            onCall { call ->
                val request = call.request
                if (request.httpMethod == HttpMethod.Options &&
                                request.headers["Access-Control-Request-Private-Network"]?.equals(
                                        "true",
                                        ignoreCase = true
                                ) == true
                ) {
                    val originHeader = request.headers[HttpHeaders.Origin]
                    val normalizedOrigin = normalizeOrigin(originHeader)
                    if (allowAll || normalizedOrigin == null || allowlist.contains(normalizedOrigin)) {
                        if (originHeader != null) {
                            call.response.headers.append(
                                    HttpHeaders.AccessControlAllowOrigin,
                                    originHeader
                            )
                            call.response.headers.append(HttpHeaders.Vary, HttpHeaders.Origin)
                        }
                        call.response.headers.append("Access-Control-Allow-Private-Network", "true")
                        call.response.headers.append(
                                HttpHeaders.AccessControlAllowMethods,
                                "GET,POST,OPTIONS"
                        )
                        call.response.headers.append(
                                HttpHeaders.AccessControlAllowHeaders,
                                request.headers[HttpHeaders.AccessControlRequestHeaders] ?: "*",
                        )
                        call.respond(HttpStatusCode.NoContent)
                        return@onCall
                    }
                }
            }
        }
