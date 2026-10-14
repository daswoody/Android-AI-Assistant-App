package de.heimai.app.core.network

import de.heimai.app.core.model.CardEnvelope
import de.heimai.app.core.model.LayoutTemplate
import de.heimai.app.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

@Serializable
data class LoginRequest(val username: String, val password: String, val device_name: String)

@Serializable
data class LoginUser(val name: String, val tier: Int = 1)

@Serializable
data class LoginResponse(val token: String, val user: LoginUser)

@Serializable
data class HealthResponse(val status: String = "ok", val name: String? = null, val version: String? = null)

@Serializable
data class CardLayoutsResponse(val version: Int, val layouts: List<LayoutTemplate>)

@Serializable
data class Voice(val id: String, val name: String)

@Serializable
data class VoicesResponse(val voices: List<Voice>)

/**
 * Zentrale Chat-Historie: Der Server besitzt die Gespräche, die App ist nur
 * eine Ansicht (dieselbe Historie in Browser, Windows und Android).
 */
@Serializable
data class RemoteConversation(
    val id: String,
    val title: String = "",
    val device_name: String = "",
    val message_count: Int = 0,
    val created_at: String = "",
    val updated_at: String = "",
)

@Serializable
data class ConversationsResponse(val conversations: List<RemoteConversation> = emptyList())

@Serializable
data class RemoteMessage(
    val id: Long = 0,
    val role: String,
    val content: String = "",
    val cards: List<CardEnvelope> = emptyList(),
    val has_image: Boolean = false,
    val created_at: String = "",
)

@Serializable
data class ConversationDetail(
    val id: String,
    val title: String = "",
    val device_name: String = "",
    val created_at: String = "",
    val updated_at: String = "",
    val messages: List<RemoteMessage> = emptyList(),
)

@Serializable
data class OkResponse(val ok: Boolean = true)

class ApiException(message: String, val code: Int = 0) : IOException(message)

/**
 * REST-Client für den Voice-Orchestrator (Phase 1.7).
 * Vertrag siehe docs/PROTOCOL.md — der Server existiert noch nicht,
 * dieses Interface IST die Spezifikation.
 */
class ApiClient(
    private val client: OkHttpClient,
    private val json: Json,
    private val settings: SettingsRepository,
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** Erreichbarkeits-Check für die Server-Auswahl beim App-Start. */
    suspend fun health(baseUrl: String): HealthResponse =
        get("${baseUrl.trimEnd('/')}/v1/health", auth = false)

    suspend fun login(baseUrl: String, username: String, password: String, deviceName: String): LoginResponse {
        val body = json.encodeToString(LoginRequest.serializer(), LoginRequest(username, password, deviceName))
        return post("${baseUrl.trimEnd('/')}/v1/auth/login", body, auth = false)
    }

    suspend fun cardLayouts(sinceVersion: Int): CardLayoutsResponse {
        val base = settings.current().serverUrl
        return get("$base/v1/cards/layouts?since_version=$sinceVersion")
    }

    suspend fun voices(): VoicesResponse {
        val base = settings.current().serverUrl
        return get("$base/v1/voices")
    }

    // ---- Zentrale Chat-Historie (Server ist Source of Truth) ----

    suspend fun conversations(): ConversationsResponse {
        val base = settings.current().serverUrl
        return get("$base/v1/conversations")
    }

    suspend fun conversation(id: String): ConversationDetail {
        val base = settings.current().serverUrl
        return get("$base/v1/conversations/$id")
    }

    suspend fun deleteConversation(id: String): OkResponse {
        val base = settings.current().serverUrl
        return execute(Request.Builder().url("$base/v1/conversations/$id").delete(), auth = true)
    }

    private suspend inline fun <reified T> get(url: String, auth: Boolean = true): T =
        execute(Request.Builder().url(url).get(), auth)

    private suspend inline fun <reified T> post(url: String, body: String, auth: Boolean = true): T =
        execute(Request.Builder().url(url).post(body.toRequestBody(jsonMediaType)), auth)

    private suspend inline fun <reified T> execute(builder: Request.Builder, auth: Boolean): T =
        withContext(Dispatchers.IO) {
            if (auth) {
                val token = settings.current().authToken
                if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
            }
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ApiException("HTTP ${response.code}: ${text.take(200)}", response.code)
                }
                json.decodeFromString(text)
            }
        }
}
