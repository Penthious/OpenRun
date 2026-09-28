package dev.digitalducktape.openrun.core.garmin

import java.io.IOException
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class GarminFailure(val userMessage: String, val retryable: Boolean = false, val authentication: Boolean = false) :
    IOException(userMessage)

data class GarminResponse(val status: Int, val body: String)

fun interface GarminTransport {
    fun request(url: String, headers: Map<String, String>, body: ByteArray): GarminResponse
    fun get(url: String, headers: Map<String, String>): GarminResponse = throw UnsupportedOperationException()
}

/** Per-login cookie jar; never shared between riders or persisted. No redirects with credentials. */
class GarminHttpTransport : GarminTransport {
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    override fun request(url: String, headers: Map<String, String>, body: ByteArray): GarminResponse {
        return execute(url, headers, body)
    }
    override fun get(url: String, headers: Map<String, String>): GarminResponse = execute(url, headers, null)
    private fun execute(url: String, headers: Map<String, String>, body: ByteArray?): GarminResponse {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host in setOf("sso.garmin.com", "diauth.garmin.com", "connectapi.garmin.com"))
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = if (body == null) "GET" else "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.doOutput = body != null
            connection.setRequestProperty("User-Agent", "GCM-Android-5.23")
            connection.setRequestProperty("Accept", "application/json")
            cookies.get(uri, emptyMap()).forEach { (name, values) -> connection.setRequestProperty(name, values.joinToString("; ")) }
            headers.forEach(connection::setRequestProperty)
            if (body != null) {
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            cookies.put(uri, connection.headerFields.filterKeys { it != null })
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 1_048_576) throw GarminFailure("Garmin returned an oversized response.")
                    output.write(buffer, 0, count)
                }
                String(output.toByteArray(), Charsets.UTF_8)
            }.orEmpty()
            return GarminResponse(status, response)
        } finally {
            connection.disconnect()
        }
    }
}

sealed interface GarminLoginResult {
    data class Connected(val tokens: GarminTokens) : GarminLoginResult
    data class VerificationRequired(val method: String) : GarminLoginResult
}

interface GarminUploadApi {
    suspend fun history(tokens: GarminTokens): List<dev.digitalducktape.openrun.PaceSegment> = emptyList()
    suspend fun refresh(tokens: GarminTokens): GarminTokens
    suspend fun upload(tokens: GarminTokens, tcx: String): Boolean
    suspend fun uploadFit(tokens: GarminTokens, fit: ByteArray): Boolean = throw GarminFailure("FIT upload is unavailable.")
    suspend fun scheduledWorkouts(tokens: GarminTokens): List<GarminPlannedWorkout> = throw GarminFailure("Workout import is unavailable.")
    suspend fun scheduledWorkouts(tokens: GarminTokens, today: java.time.LocalDate): List<GarminPlannedWorkout> = scheduledWorkouts(tokens)
    suspend fun workoutPreview(tokens: GarminTokens, workout: GarminPlannedWorkout): GarminWorkoutPreview = throw GarminFailure("Workout preview is unavailable.")
}

/** Independent implementation of the unofficial mobile SSO and TCX import protocol. */
class GarminApi(
    private val transport: GarminTransport = GarminHttpTransport(),
    private val now: () -> Long = System::currentTimeMillis,
) : GarminUploadApi {
    private var mfaMethod: String? = null

    private fun get(tokens: GarminTokens, path: String): String {
        val response = transport.get("https://connectapi.garmin.com$path", mapOf(
            "Authorization" to "Bearer ${tokens.access}", "NK" to "NT",
        ))
        checkStatus(response)
        return response.body
    }

    override suspend fun history(tokens: GarminTokens): List<dev.digitalducktape.openrun.PaceSegment> = withContext(Dispatchers.IO) {
        val activities=GarminHistoryParser.activities(get(tokens,"/activitylist-service/activities/search/activities?start=0&limit=60&activityType=running"),now())
        activities.flatMap { GarminHistoryParser.segments(get(tokens,"/activity-service/activity/${it.id}/details?maxChartSize=2000&maxPolylineSize=0"),it) }
    }

    override suspend fun scheduledWorkouts(tokens: GarminTokens): List<GarminPlannedWorkout> =
        scheduledWorkouts(tokens, java.time.Instant.ofEpochMilli(now()).atZone(java.time.ZoneId.systemDefault()).toLocalDate())

    override suspend fun scheduledWorkouts(tokens: GarminTokens, today: java.time.LocalDate): List<GarminPlannedWorkout> = withContext(Dispatchers.IO) {
        (0L..1L).flatMap { offset ->
            val month = today.withDayOfMonth(1).plusMonths(offset)
            GarminWorkoutParser.calendar(get(tokens, "/calendar-service/year/${month.year}/month/${month.monthValue - 1}"), today)
        }.distinctBy { Triple(it.date, it.scheduleId, it.adaptiveUuid) }.sortedBy { it.date }
    }

    override suspend fun workoutPreview(tokens: GarminTokens, workout: GarminPlannedWorkout): GarminWorkoutPreview = withContext(Dispatchers.IO) {
        workout.adaptiveUuid?.let { uuid ->
            require(uuid.matches(Regex("[0-9a-fA-F-]{36}")))
            return@withContext GarminWorkoutParser.preview(get(tokens, "/workout-service/fbt-adaptive/$uuid")).copy(source = workout)
        }
        val id = workout.workoutId ?: GarminWorkoutParser.scheduledId(get(tokens, "/workout-service/schedule/${workout.scheduleId}"))
        GarminWorkoutParser.preview(get(tokens, "/workout-service/workout/$id")).copy(source = workout.copy(workoutId = id))
    }

    suspend fun login(email: String, password: String): GarminLoginResult = withContext(Dispatchers.IO) {
        mfaMethod = null
        val payload = JSONObject().put("username", email.trim()).put("password", password)
            .put("rememberMe", true).put("captchaToken", "")
        loginResponse(postJson("login", payload))
    }

    suspend fun verify(code: String): GarminLoginResult = withContext(Dispatchers.IO) {
        val method = mfaMethod ?: throw GarminFailure("Start sign-in again; the verification session expired.")
        val payload = JSONObject().put("mfaMethod", method).put("mfaVerificationCode", code.trim())
            .put("rememberMyBrowser", true).put("reconsentList", org.json.JSONArray()).put("mfaSetup", false)
        loginResponse(postJson("mfa/verifyCode", payload))
    }

    private fun postJson(path: String, payload: JSONObject): GarminResponse = transport.request(
        "https://sso.garmin.com/mobile/api/$path?" + form(mapOf(
            "clientId" to "GCM_ANDROID_DARK", "locale" to "en-US", "service" to SERVICE,
        )),
        mapOf("Content-Type" to "application/json", "Origin" to "https://sso.garmin.com"),
        payload.toString().toByteArray(Charsets.UTF_8),
    )

    private fun loginResponse(response: GarminResponse): GarminLoginResult {
        checkStatus(response)
        val json = parse(response)
        return when (json.optJSONObject("responseStatus")?.optString("type")) {
            "SUCCESSFUL" -> {
                val ticket = json.optString("serviceTicketId")
                if (ticket.isBlank()) throw GarminFailure("Garmin sign-in returned an incomplete response. Try again.")
                val result = tokenRequest(mapOf(
                    "client_id" to CLIENT_ID,
                    "service_ticket" to ticket,
                    "grant_type" to "https://connectapi.garmin.com/di-oauth2-service/oauth/grant/service_ticket",
                    "service_url" to SERVICE,
                ), CLIENT_ID)
                mfaMethod = null
                GarminLoginResult.Connected(tokens(result, CLIENT_ID))
            }
            "MFA_REQUIRED" -> {
                mfaMethod = json.optJSONObject("customerMfaInfo")?.optString("mfaLastMethodUsed")
                    ?.takeIf { it.isNotBlank() } ?: "email"
                GarminLoginResult.VerificationRequired(mfaMethod!!)
            }
            "INVALID_USERNAME_PASSWORD" -> throw GarminFailure("Garmin did not accept that email or password.")
            "CAPTCHA_REQUIRED" -> throw GarminFailure("Garmin requires a browser security check. Sign in at Garmin Connect, then try again here.")
            else -> throw GarminFailure("Garmin could not complete sign-in. Check your verification code or start again.")
        }
    }

    override suspend fun refresh(tokens: GarminTokens): GarminTokens = withContext(Dispatchers.IO) {
        val response = tokenRequest(mapOf("grant_type" to "refresh_token", "client_id" to tokens.clientId,
            "refresh_token" to tokens.refresh), tokens.clientId)
        tokens(response, tokens.clientId, tokens.refresh)
    }

    private fun tokenRequest(fields: Map<String, String>, client: String): GarminResponse {
        val response = transport.request("https://diauth.garmin.com/di-oauth2-service/oauth/token", mapOf(
            "Content-Type" to "application/x-www-form-urlencoded",
            "Authorization" to "Basic " + Base64.getEncoder().encodeToString("$client:".toByteArray()),
        ), form(fields).toByteArray(Charsets.UTF_8))
        if (response.status == 400) throw GarminFailure("Garmin sign-in expired. Reconnect your account.", authentication = true)
        checkStatus(response)
        return response
    }

    private fun tokens(response: GarminResponse, client: String, previousRefresh: String = ""): GarminTokens {
        val json = parse(response)
        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token", previousRefresh)
        if (access.isBlank() || refresh.isBlank()) throw GarminFailure("Garmin returned incomplete login tokens. Sign in again.")
        return GarminTokens(access, refresh, client, now() + json.optLong("expires_in", 3600).coerceIn(60, 86_400) * 1000)
    }

    /** True includes Garmin's explicit duplicate response: the ride is already in the account. */
    override suspend fun upload(tokens: GarminTokens, tcx: String): Boolean = uploadFile(tokens, "tcx", tcx.toByteArray(Charsets.UTF_8))
    override suspend fun uploadFit(tokens: GarminTokens, fit: ByteArray): Boolean = uploadFile(tokens, "fit", fit)

    private suspend fun uploadFile(tokens: GarminTokens, format: String, payload: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val boundary = "OpenRun-${UUID.randomUUID()}"
        val prefix = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"openrun.$format\"\r\nContent-Type: application/octet-stream\r\n\r\n"
        val body = prefix.toByteArray(Charsets.UTF_8) + payload + "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val response = transport.request("https://connectapi.garmin.com/upload-service/upload/$format", mapOf(
            "Authorization" to "Bearer ${tokens.access}", "Content-Type" to "multipart/form-data; boundary=$boundary",
            "NK" to "NT", "Origin" to "https://sso.garmin.com",
        ), body)
        if (response.status == 409) return@withContext true
        checkStatus(response)
        val result = parse(response).optJSONObject("detailedImportResult")
        if ((result?.optJSONArray("successes")?.length() ?: 0) > 0) return@withContext true
        throw GarminFailure("Garmin did not confirm the import. You can retry from Profile.")
    }

    private fun parse(response: GarminResponse): JSONObject = try { JSONObject(response.body) } catch (_: Exception) {
        throw GarminFailure("Garmin returned an unexpected response. Try again later.", retryable = true)
    }

    private fun checkStatus(response: GarminResponse) {
        when (response.status) {
            in 200..299 -> return
            401 -> throw GarminFailure("Garmin sign-in expired. Reconnect your account.", authentication = true)
            403 -> throw GarminFailure("Garmin blocked this request. Try signing in again later.", authentication = true)
            429 -> throw GarminFailure("Garmin is limiting requests. Uploads will retry later.", retryable = true)
            in 500..599 -> throw GarminFailure("Garmin is unavailable. Uploads will retry later.", retryable = true)
            else -> throw GarminFailure("Garmin rejected the request (HTTP ${response.status}). Try reconnecting.")
        }
    }

    private companion object {
        const val SERVICE = "https://mobile.integration.garmin.com/gcm/android"
        const val CLIENT_ID = "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2"
        fun form(fields: Map<String, String>): String = fields.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
    }
}
