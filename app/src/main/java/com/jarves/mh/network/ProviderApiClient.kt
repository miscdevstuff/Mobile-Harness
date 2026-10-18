package com.jarves.mh.network

import android.content.Context
import com.jarves.mh.R
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.ProviderProtocol
import com.jarves.mh.model.isKeylessAllowed
import com.jarves.mh.runtime.OpencodeRuntimeBridge
import com.jarves.mh.runtime.RuntimeInstaller
import com.jarves.mh.ui.AppStrings
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Shared OkHttpClient instance so all network callers reuse connection pools and TLS sessions. */
val sharedHttpClient: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

data class DiscoveredModel(val id: String, val displayName: String = id, val isFree: Boolean = false)

sealed interface ModelDiscoveryResult {
    data class Success(val models: List<DiscoveredModel>, val endpoint: String) : ModelDiscoveryResult
    data class Failure(val message: String, val providerMessage: String? = null) : ModelDiscoveryResult
}

sealed interface ConnectionValidation {
    data class Success(val message: String) : ConnectionValidation
    data class Failure(
        val message: String,
        val providerMessage: String? = null,
        val label: String = "Failed",
    ) : ConnectionValidation
}

/**
 * [context] is optional so existing call sites keep compiling; pass the application
 * context to return user-facing messages in the language picked inside the app.
 */
class ProviderApiClient(private val context: Context? = null) {
    private fun text(@androidx.annotation.StringRes id: Int, fallback: String, vararg args: Any): String =
        context?.let { AppStrings.get(it, id, *args) } ?: fallback

    /**
     * Discovers models for [ProviderKind.OPENCODE_CLI] using the installed OpenCode v2 CLI
     * (`opencode models --standalone`), falling back to the public OpenCode catalog endpoint
     * if queried before the CLI binary is installed.
     */
    suspend fun discoverOpencodeCliModels(
        profile: ProviderProfile,
        apiKey: String,
    ): ModelDiscoveryResult = withContext(Dispatchers.IO) {
        val appContext = context
        if (appContext != null && RuntimeInstaller(appContext).isAgentInstalled(AgentKind.OPENCODE)) {
            val bridge = OpencodeRuntimeBridge(appContext, secretFor = { apiKey })
            val cliModels = runCatching { bridge.discoverCliModels(profile, apiKey) }.getOrDefault(emptyList())
            if (cliModels.isNotEmpty()) {
                return@withContext ModelDiscoveryResult.Success(cliModels, OpencodeRuntimeBridge.OPENCODE_GUEST_PATH)
            }
        }
        discoverModels(profile.resolvedBaseUrl, apiKey, profile.kind.protocol)
    }

    /**
     * Validates a provider profile through the official OpenCode v2 CLI binary inside PRoot
     * so free-tier OpenCode Zen models never trigger Cloudflare's HTTP `FreeTierError`.
     */
    suspend fun validateOpencodeCli(
        profile: ProviderProfile,
        apiKey: String,
    ): ConnectionValidation = withContext(Dispatchers.IO) {
        if (profile.model.isBlank()) {
            return@withContext ConnectionValidation.Failure(
                text(R.string.rt_api_required_fields, "Base URL, model, and API key are required."),
            )
        }
        if (apiKey.isBlank() && !profile.kind.isKeylessAllowed(profile.model)) {
            return@withContext ConnectionValidation.Failure(
                text(R.string.rt_api_required_fields, "Base URL, model, and API key are required."),
            )
        }
        val appContext = context
        if (appContext != null && RuntimeInstaller(appContext).isAgentInstalled(AgentKind.OPENCODE)) {
            val bridge = OpencodeRuntimeBridge(appContext, secretFor = { apiKey })
            return@withContext runCatching {
                bridge.hello(profile, secretOverride = apiKey)
                ConnectionValidation.Success(text(R.string.rt_api_connection_verified, "Connection verified."))
            }.getOrElse { error ->
                val reason = error.message.orEmpty()
                val rejected = reason.contains("api key", true) ||
                    reason.contains("auth", true) ||
                    reason.contains("401", true) ||
                    reason.contains("403", true)
                ConnectionValidation.Failure(
                    message = if (rejected) {
                        text(R.string.rt_api_check_key, "Check this API key or select another saved key.")
                    } else {
                        reason.take(240).ifBlank { text(R.string.rt_api_check_connection, "Check your connection and try again.") }
                    },
                    providerMessage = reason.takeIf { it.isNotBlank() && rejected },
                    label = if (rejected) {
                        text(R.string.rt_api_label_rejected, "Rejected")
                    } else {
                        text(R.string.rt_api_label_request_failed, "Request failed")
                    },
                )
            }
        }

        if (profile.kind != ProviderKind.OPENCODE_CLI) {
            return@withContext validate(
                baseUrl = profile.resolvedBaseUrl,
                model = profile.model,
                apiKey = apiKey,
                protocol = profile.kind.protocol,
                discoveredModels = emptyList(),
                openRouterProviderOrder = profile.openRouterProviderOrder,
                openRouterAllowFallbacks = profile.openRouterAllowFallbacks,
            )
        }

        // Fallback if validated before the OpenCode agent overlay is installed: verify catalog reachability.
        val catalogEndpoint = "${profile.resolvedBaseUrl.trim().trimEnd('/')}/models"
        val response = request(catalogEndpoint, "GET", apiKey, protocol = ProviderProtocol.OPENAI_CHAT)
        if (response.code in 200..299) {
            ConnectionValidation.Success(text(R.string.rt_api_connection_verified, "Connection verified."))
        } else {
            ConnectionValidation.Failure(
                text(R.string.rt_api_check_internet, "Check your internet connection and provider settings."),
                response.error ?: providerErrorMessage(response.body),
                text(R.string.rt_api_label_network_error, "Network error"),
            )
        }
    }

    suspend fun discoverModels(
        baseUrl: String,
        apiKey: String,
        protocol: ProviderProtocol,
    ): ModelDiscoveryResult = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) {
            return@withContext ModelDiscoveryResult.Failure(text(R.string.rt_api_enter_base_url, "Enter a base URL first."))
        }

        var authError = false
        var lastMessage = text(
            R.string.rt_api_no_model_list,
            "This provider did not expose a model list. You can enter a custom model name.",
        )
        var lastProviderMessage: String? = null
        for (endpoint in modelEndpoints(baseUrl, protocol)) {
            val discoveryKey = if (isPublicCatalogEndpoint(endpoint)) "" else apiKey
            val response = request(endpoint, "GET", discoveryKey, protocol = protocol)
            when {
                response.code == 401 || response.code == 403 -> {
                    authError = true
                    lastProviderMessage = providerErrorMessage(response.body)
                }
                response.code in 200..299 -> {
                    val models = ModelResponseParser.parse(response.body)
                    if (models.isNotEmpty()) return@withContext ModelDiscoveryResult.Success(models, endpoint)
                    lastMessage = text(
                        R.string.rt_api_empty_model_list,
                        "The provider replied, but its model list was empty or unsupported.",
                    )
                }
                response.code > 0 && response.code != 404 -> {
                    lastMessage = friendlyHttpError(response.code)
                    lastProviderMessage = providerErrorMessage(response.body)
                }
                response.error != null -> lastMessage = response.error
            }
        }
        ModelDiscoveryResult.Failure(
            if (authError) {
                text(R.string.rt_api_check_saved_key, "Check the saved API key, then try refreshing again.")
            } else {
                lastMessage
            },
            lastProviderMessage,
        )
    }

    suspend fun validate(
        baseUrl: String,
        model: String,
        apiKey: String,
        protocol: ProviderProtocol,
        discoveredModels: List<DiscoveredModel>,
        openRouterProviderOrder: String = "",
        openRouterAllowFallbacks: Boolean = true,
    ): ConnectionValidation = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || model.isBlank()) {
            return@withContext ConnectionValidation.Failure(text(R.string.rt_api_required_fields, "Base URL, model, and API key are required."))
        }
        val endpoint = messagesEndpoint(baseUrl, protocol)
        val body = validationBody(model, protocol, openRouterProviderOrder, openRouterAllowFallbacks)
        val response = request(endpoint, "POST", apiKey, body, protocol, connectTimeoutMs = 12_000, readTimeoutMs = 45_000)
        when {
            response.code in 200..299 -> ConnectionValidation.Success(text(R.string.rt_api_connection_verified, "Connection verified."))
            response.code == 401 || response.code == 403 -> ConnectionValidation.Failure(
                text(R.string.rt_api_check_key, "Check this API key or select another saved key."),
                providerErrorMessage(response.body),
                text(R.string.rt_api_label_rejected, "Rejected"),
            )
            response.code == 404 -> ConnectionValidation.Failure(
                text(R.string.rt_api_check_base_url, "Check the Base URL and selected gateway protocol."),
                providerErrorMessage(response.body),
                text(R.string.rt_api_label_endpoint_error, "Endpoint error"),
            )
            response.code == 400 && response.body.contains("model", ignoreCase = true) ->
                ConnectionValidation.Failure(
                    text(R.string.rt_api_refresh_models, "Refresh the model list or select a different model."),
                    providerErrorMessage(response.body),
                    text(R.string.rt_api_label_model_error, "Model error"),
                )
            response.code == 429 -> ConnectionValidation.Failure(
                text(R.string.rt_api_rate_limited_retry, "Wait a moment, then retry or use another API key."),
                providerErrorMessage(response.body),
                text(R.string.rt_api_label_rate_limited, "Rate limited"),
            )
            response.code in 500..599 -> ConnectionValidation.Failure(
                text(R.string.rt_api_provider_unavailable, "The provider is temporarily unavailable. Try again shortly."),
                providerErrorMessage(response.body),
                text(R.string.rt_api_label_provider_error, "Provider error"),
            )
            response.code > 0 -> ConnectionValidation.Failure(
                text(R.string.rt_api_review_settings, "Review the model, protocol, and endpoint settings."),
                providerErrorMessage(response.body),
                text(R.string.rt_api_label_request_failed, "Request failed"),
            )
            response.error?.contains("timeout", ignoreCase = true) == true ||
                response.error?.contains("timed out", ignoreCase = true) == true ->
                ConnectionValidation.Failure(
                    text(R.string.rt_api_check_connection, "Check your connection and try again."),
                    response.error,
                    text(R.string.rt_api_label_timed_out, "Timed out"),
                )
            else -> ConnectionValidation.Failure(
                text(R.string.rt_api_check_internet, "Check your internet connection and provider settings."),
                response.error,
                text(R.string.rt_api_label_network_error, "Network error"),
            )
        }
    }

    private fun request(
        endpoint: String,
        method: String,
        apiKey: String,
        body: String? = null,
        protocol: ProviderProtocol,
        connectTimeoutMs: Int = 12_000,
        readTimeoutMs: Int = 20_000,
    ): HttpResult {
        if (!LocalNetwork.isPlainHttpAllowed(endpoint)) {
            return HttpResult(0, "", text(R.string.rt_api_http_public, "Plain http:// is only allowed for local network addresses. Use https:// for this server."))
        }
        return runCatching {
            val client = sharedHttpClient.newBuilder()
                .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .build()
            val requestBuilder = Request.Builder()
                .url(endpoint)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .apply {
                    if (apiKey.isNotBlank()) {
                        header("Authorization", "Bearer $apiKey")
                    }
                    if (endpoint.startsWith("https://opencode.ai/zen/")) {
                        header("User-Agent", "opencode/1.18.20")
                        header("x-session-id", "session-${UUID.randomUUID()}")
                    }
                    if (apiKey.isNotBlank() &&
                        protocol != ProviderProtocol.OPENROUTER &&
                        protocol != ProviderProtocol.OPENAI_CHAT &&
                        protocol != ProviderProtocol.OPENAI_RESPONSES
                    ) {
                        header("x-api-key", apiKey)
                        header("anthropic-version", "2023-06-01")
                    }
                    val requestBody = body?.toRequestBody(JSON_MEDIA_TYPE)
                    method(method, requestBody)
                }
            client.newCall(requestBuilder.build()).execute().use { response ->
                val responseBody = response.body?.charStream()?.buffered()?.use { reader ->
                    buildString {
                        val buffer = CharArray(8 * 1024)
                        while (length < MAX_PROVIDER_RESPONSE_CHARS) {
                            val count = reader.read(buffer, 0, minOf(buffer.size, MAX_PROVIDER_RESPONSE_CHARS - length))
                            if (count <= 0) break
                            append(buffer, 0, count)
                        }
                    }
                }.orEmpty()
                HttpResult(response.code, responseBody)
            }
        }.getOrElse { HttpResult(0, "", it.message ?: text(R.string.rt_api_network_failed, "Network connection failed")) }
    }

    private fun modelEndpoints(baseUrl: String, protocol: ProviderProtocol): List<String> {
        val base = baseUrl.trim().trimEnd('/')
        val withoutAnthropic = base.removeSuffix("/anthropic")
        val candidates = when (protocol) {
            ProviderProtocol.OPENROUTER -> listOf("$base/v1/models")
            ProviderProtocol.OPENAI_CHAT, ProviderProtocol.OPENAI_RESPONSES -> listOf("$base/models")
            else -> listOf("$base/v1/models", "$base/models", "$withoutAnthropic/models", "$withoutAnthropic/v1/models")
        }
        return candidates.distinct()
    }

    private fun messagesEndpoint(baseUrl: String, protocol: ProviderProtocol): String {
        val base = baseUrl.trim().trimEnd('/')
        return when (protocol) {
            ProviderProtocol.OPENROUTER -> "$base/v1/messages"
            ProviderProtocol.OPENAI_CHAT -> "$base/chat/completions"
            ProviderProtocol.OPENAI_RESPONSES -> "$base/responses"
            else -> if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
        }
    }

    private fun isPublicCatalogEndpoint(endpoint: String): Boolean {
        val url = endpoint.toHttpUrlOrNull() ?: return false
        return (url.host.equals("openrouter.ai", ignoreCase = true) || url.host.equals("opencode.ai", ignoreCase = true)) &&
            url.encodedPath.trimEnd('/').endsWith("/models")
    }

    internal fun validationBody(
        model: String,
        protocol: ProviderProtocol,
        openRouterProviderOrder: String = "",
        openRouterAllowFallbacks: Boolean = true,
    ): String = when (protocol) {
        ProviderProtocol.OPENAI_RESPONSES -> JSONObject()
            .put("model", model)
            .put(
                "input",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "input_text")
                                    .put("text", "Hello, reply with 1 word."),
                            ),
                        ),
                ),
            )
            .toString()
        ProviderProtocol.OPENAI_CHAT -> JSONObject()
            .put("model", model)
            .put("max_tokens", VALIDATION_MAX_TOKENS)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply OK")))
            .toString()
        else -> JSONObject()
            .put("model", model)
            .put("max_tokens", VALIDATION_MAX_TOKENS)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply OK")))
            .also { body ->
                val providers = openRouterProviderOrder.split(',')
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct()
                if (protocol == ProviderProtocol.OPENROUTER && providers.isNotEmpty()) {
                    body.put(
                        "provider",
                        JSONObject()
                            .put("order", JSONArray(providers))
                            .put("allow_fallbacks", openRouterAllowFallbacks),
                    )
                }
            }
            .toString()
    }

    private fun friendlyHttpError(code: Int): String = when (code) {
        429 -> text(R.string.rt_api_rate_limit_reached, "The provider rate limit was reached. Wait a moment and try again.")
        in 500..599 -> text(R.string.rt_api_unavailable_http, "The provider is temporarily unavailable (HTTP $code).", code)
        else -> text(R.string.rt_api_http_error, "The provider returned HTTP $code. Check the URL and account access.", code)
    }

    private fun providerErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        val extracted = runCatching {
            val root = JSONObject(body)
            when (val error = root.opt("error")) {
                is JSONObject -> error.optString("message").ifBlank { error.optString("detail") }
                is String -> error
                else -> root.optString("message").ifBlank { root.optString("detail") }
            }
        }.getOrNull().orEmpty()
        if (extracted.isBlank()) return null
        return extracted
            .replace(Regex("(?i)bearer\\s+\\S+"), "Bearer ••••")
            .replace(Regex("(?i)sk-[a-z0-9_-]{8,}"), "sk-••••")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(280)
    }

    private data class HttpResult(val code: Int, val body: String, val error: String? = null)

    private companion object {
        const val VALIDATION_MAX_TOKENS = 8
        const val MAX_PROVIDER_RESPONSE_CHARS = 2 * 1024 * 1024
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

object ModelResponseParser {
    private fun isFreeModelId(id: String): Boolean {
        val clean = id.trim().removePrefix("opencode/")
        return clean.endsWith(":free", ignoreCase = true) ||
            clean.endsWith("-free", ignoreCase = true) ||
            clean.equals("big-pickle", ignoreCase = true)
    }

    fun parse(json: String): List<DiscoveredModel> = runCatching {
        val trimmed = json.trim()
        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> {
                val root = JSONObject(trimmed)
                root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
            }
        }
        buildList {
            for (index in 0 until array.length()) {
                when (val item = array.opt(index)) {
                    is String -> add(DiscoveredModel(item, item, isFreeModelId(item)))
                    is JSONObject -> {
                        val id = item.optString("id").ifBlank { item.optString("name") }
                        if (id.isNotBlank()) {
                            val label = item.optString("display_name").ifBlank { item.optString("displayName") }.ifBlank { id }
                            val pricing = item.optJSONObject("pricing")
                            val free = isFreeModelId(id) || pricing?.let {
                                listOf("prompt", "completion", "request").all { field ->
                                    it.optString(field, "0").toDoubleOrNull() == 0.0
                                }
                            } == true
                            add(DiscoveredModel(id, label, free))
                        }
                    }
                }
            }
        }.distinctBy { it.id }.sortedWith(compareByDescending<DiscoveredModel> { it.isFree }.thenBy { it.displayName.lowercase() })
    }.getOrDefault(emptyList())

    /**
     * Parses the newline-delimited `provider/model` output of `opencode models --standalone`.
     */
    fun parseOpencodeCliOutput(output: String, providerPrefix: String = "opencode/"): List<DiscoveredModel> {
        val normalizedPrefix = providerPrefix.trim()
        return output.lineSequence()
            .map(String::trim)
            .filter { line ->
                line.isNotBlank() &&
                    !line.startsWith("proot warning:", ignoreCase = true) &&
                    line.matches(Regex("[a-z0-9._-]+/[a-zA-Z0-9._:-]+"))
            }
            .filter { line ->
                normalizedPrefix.isEmpty() || line.startsWith(normalizedPrefix, ignoreCase = true)
            }
            .map { line ->
                val modelId = if (normalizedPrefix.isNotEmpty() && line.startsWith(normalizedPrefix, ignoreCase = true)) {
                    line.substring(normalizedPrefix.length)
                } else {
                    line
                }
                DiscoveredModel(
                    id = modelId,
                    displayName = formatOpencodeModelDisplayName(modelId),
                    isFree = isFreeModelId(modelId),
                )
            }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<DiscoveredModel> { it.isFree }.thenBy { it.displayName.lowercase(Locale.ROOT) })
            .toList()
    }

    private fun formatOpencodeModelDisplayName(modelId: String): String = when (modelId.lowercase(Locale.ROOT)) {
        "mimo-v2.6-flash-free" -> "MiMo-V2.6-Flash Free"
        "mimo-v2.5-free" -> "MiMo-V2.5 Free"
        "deepseek-v4.1-flash-free" -> "DeepSeek V4.1 Flash Free"
        "big-pickle" -> "Big Pickle"
        "space-bunny-free" -> "Space Bunny Free"
        "cat-jam-free" -> "Cat Jam Free"
        "longcat-2.5-preview-free" -> "LongCat 2.5 Preview Free"
        "step-5-preview-free" -> "Step 5 Preview Free"
        "exo-free" -> "Exo Free"
        "nemotron-3-ultra-free" -> "Nemotron 3 Ultra Free"
        "nemotron-3.5-lightning-free" -> "Nemotron 3.5 Lightning Free"
        "muse-spark-1.3-contributor-free" -> "Muse Spark 1.3 Contributor Free"
        "jev-1.13-free" -> "Jev 1.13 Free"
        else -> modelId
    }
}
