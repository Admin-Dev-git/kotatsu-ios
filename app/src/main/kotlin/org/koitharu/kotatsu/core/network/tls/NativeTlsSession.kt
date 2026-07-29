package org.koitharu.kotatsu.core.network.tls

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.util.UUID

internal data class TlsRequestPayload(
	val requestUrl: String,
	val requestMethod: String,
	val requestBody: String? = null,
	val requestCookies: List<TlsCookie>? = null,
	val tlsClientIdentifier: String? = ChromeTlsIdentity.PROFILE,
	val sessionId: String? = null,
	val followRedirects: Boolean = true,
	val insecureSkipVerify: Boolean = false,
	val isByteResponse: Boolean = false,
	val isByteRequest: Boolean = false,
	val withRandomTLSExtensionOrder: Boolean = false,
	val timeoutSeconds: Int = 60,
	val timeoutMilliseconds: Int = 0,
	val proxyUrl: String? = null,
	val headers: Map<String, String>? = null,
	val headerOrder: List<String>? = null,
	val forceHttp1: Boolean = false,
	val withoutCookieJar: Boolean = false,
	val catchPanics: Boolean = true,
)

internal data class TlsCookie(
	val name: String,
	val value: String,
	val path: String = "/",
	val domain: String = "",
	val expires: Long = 0L,
	val maxAge: Int = -1,
	val secure: Boolean = false,
	val httpOnly: Boolean = false,
)

internal data class TlsResponseData(
	val status: Int = 0,
	val body: String = "",
	val headers: Map<String, List<String>> = emptyMap(),
	val cookies: Map<String, String> = emptyMap(),
	val target: String = "",
	val sessionId: String? = null,
	@SerializedName("usedProtocol")
	val usedProtocol: String = "HTTP/1.1",
)

/**
 * Minimal JVM-11-safe wrapper around the native tls-client shared library.
 */
internal class NativeTlsSession(
	private val lib: GoTlsClientLibrary,
	private val gson: Gson = Gson(),
	val sessionId: String = UUID.randomUUID().toString(),
	private val tlsClientIdentifier: String = ChromeTlsIdentity.PROFILE,
	private val insecureSkipVerify: Boolean = false,
) {

	fun execute(
		method: String,
		url: String,
		headers: Map<String, String>,
		cookies: Map<String, String>,
		body: String?,
		byteRequest: Boolean,
		byteResponse: Boolean,
		proxyUrl: String?,
	): TlsResponseData {
		val headerOrder = ChromeTlsIdentity.HEADER_ORDER.filter { name ->
			headers.keys.any { it.equals(name, ignoreCase = true) }
		} + headers.keys.filter { key ->
			ChromeTlsIdentity.HEADER_ORDER.none { it.equals(key, ignoreCase = true) }
		}

		val payload = TlsRequestPayload(
			requestUrl = url,
			requestMethod = method.uppercase(),
			requestBody = body,
			requestCookies = cookies.map { (n, v) -> TlsCookie(n, v) }.takeIf { it.isNotEmpty() },
			tlsClientIdentifier = tlsClientIdentifier,
			sessionId = sessionId,
			followRedirects = true,
			insecureSkipVerify = insecureSkipVerify,
			isByteResponse = byteResponse,
			isByteRequest = byteRequest,
			// Keep extension order stable — random order is a bot signal for Cloudflare.
			withRandomTLSExtensionOrder = false,
			timeoutSeconds = 60,
			proxyUrl = proxyUrl,
			headers = headers.takeIf { it.isNotEmpty() },
			headerOrder = headerOrder.takeIf { it.isNotEmpty() },
			withoutCookieJar = false,
			catchPanics = true,
		)
		val raw = lib.request(gson.toJson(payload))
		return parseResponse(raw)
	}

	fun close() {
		runCatching {
			lib.destroySession(gson.toJson(mapOf("sessionId" to sessionId)))
		}
	}

	private fun parseResponse(raw: String): TlsResponseData {
		return try {
			val type = object : TypeToken<TlsResponseData>() {}.type
			gson.fromJson<TlsResponseData>(raw, type) ?: TlsResponseData(body = raw)
		} catch (_: Exception) {
			TlsResponseData(status = 0, body = raw)
		}
	}
}
