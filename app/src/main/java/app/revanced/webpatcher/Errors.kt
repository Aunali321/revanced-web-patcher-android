package app.revanced.webpatcher

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory

class PatchProcessingException(
    override val message: String,
    val status: PatchErrorStatus,
    override val cause: Throwable? = null,
) : RuntimeException(message, cause)

enum class PatchErrorStatus(val httpStatus: HttpStatusCode) {
    BAD_REQUEST(HttpStatusCode.BadRequest),
    PATCH_FAILURE(HttpStatusCode.UnprocessableEntity),
    SERVER_ERROR(HttpStatusCode.InternalServerError),
}

private val logger = LoggerFactory.getLogger("PatchErrors")

suspend fun ApplicationCall.respondPatchError(
    message: String,
    status: PatchErrorStatus,
    logCause: Throwable? = null,
) {
    if (logCause != null) {
        logger.error(message, logCause)
    }

    respond(
        status.httpStatus,
        mapOf(
            "error" to message,
            "status" to status.name.lowercase(),
        ),
    )
}
