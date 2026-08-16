package org.koitharu.kotatsu.core.network.tls

import android.util.Base64
import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.network.CommonHeaders
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application interceptor that short-circuits OkHttp's TLS stack and performs the
 * request via bogdanfinn/tls-client. Applied on [org.koitharu.kotatsu.core.network.MangaHttpClient]
 * so every manga source (and Coil page/cover fetches) uses browser TLS fingerprints.
 */
@Singleton
class TlsClientInterceptor @Inject constructor(
	private val tlsClientManager: TlsClientManager,
	private val cookieJar: CookieJar,
) : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		if (!tlsClientManager.ensureReady()) {
			return chain.proceed(request)
		}
		return try {
			executeWithTlsClient(request)
		} catch (e: IOException) {
			throw e
		} catch (e: Exception) {
			Log.w(TAG, "tls-client failed for ${request.url}, falling back to OkHttp", e)
			chain.proceed(request)
		}
	}

	private fun executeWithTlsClient(request: Request): Response {
		val url = request.url
		val method = request.method.uppercase(Locale.ROOT)
		val bodyBytes = request.bodyBytes()
		val wantsBinary = shouldRequestBinary(request, bodyBytes)
		val rawHeaders = request.headers.toRawMap()
		val headers = ChromeTlsIdentity.buildHeaders(
			existing = rawHeaders,
			isImage = wantsBinary,
			hasBody = bodyBytes != null,
		)
		val cookieMap = cookieJar.loadForRequest(url).associate { it.name to it.value }
		if (cookieMap.isNotEmpty()) {
			headers["cookie"] = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
		}

		val bodyPayload = when {
			bodyBytes == null -> null
			wantsBinary && method != "GET" && method != "HEAD" ->
				Base64.encodeToString(bodyBytes, Base64.NO_WRAP)

			else -> bodyBytes.toString(Charsets.UTF_8)
		}

		val tlsResponse = tlsClientManager.withSession { session ->
			session.execute(
				method = method,
				url = url.toString(),
				headers = headers,
				cookies = cookieMap,
				body = bodyPayload,
				byteRequest = wantsBinary && bodyPayload != null && method != "GET" && method != "HEAD",
				byteResponse = wantsBinary,
				proxyUrl = tlsClientManager.currentProxyUrl(),
			)
		}

		if (tlsResponse.status == 0) {
			throw IOException("tls-client request failed for $url: ${tlsResponse.body.take(200)}")
		}

		if (BuildConfig.DEBUG && tlsResponse.status in listOf(403, 429, 503)) {
			Log.w(TAG, "CF-like status ${tlsResponse.status} from ${url.host} via tls-client")
		}

		val responseHeaders = tlsResponse.headers.toOkHttpHeaders()
		persistCookies(url, responseHeaders, tlsResponse.cookies)

		val responseBytes = if (wantsBinary) {
			decodeBody(tlsResponse.body)
		} else {
			tlsResponse.body.toByteArray(Charsets.UTF_8)
		}
		val contentType = responseHeaders[CommonHeaders.CONTENT_TYPE]?.toMediaTypeOrNull()
		val protocol = tlsResponse.usedProtocol.toProtocol()
		val code = tlsResponse.status

		return Response.Builder()
			.request(request)
			.protocol(protocol)
			.code(code)
			.message(statusMessage(code))
			.headers(responseHeaders)
			.body(responseBytes.toResponseBody(contentType))
			.sentRequestAtMillis(System.currentTimeMillis())
			.receivedResponseAtMillis(System.currentTimeMillis())
			.build()
	}

	private fun persistCookies(
		url: HttpUrl,
		headers: Headers,
		cookieMap: Map<String, String>,
	) {
		val parsed = Cookie.parseAll(url, headers).toMutableList()
		if (cookieMap.isNotEmpty()) {
			for ((name, value) in cookieMap) {
				if (parsed.any { it.name == name }) continue
				Cookie.parse(url, "$name=$value; Path=/; Secure")?.let { parsed += it }
			}
		}
		if (parsed.isNotEmpty()) {
			cookieJar.saveFromResponse(url, parsed)
		}
	}

	private fun Headers.toRawMap(): Map<String, String> {
		val result = LinkedHashMap<String, String>(size)
		for (i in 0 until size) {
			val name = name(i)
			if (name.equals(CommonHeaders.CONTENT_ENCODING, ignoreCase = true)) continue
			if (name.equals(CommonHeaders.COOKIE, ignoreCase = true)) continue
			val value = value(i)
			val existing = result[name]
			result[name] = if (existing == null) value else "$existing, $value"
		}
		return result
	}

	private fun Map<String, List<String>>.toOkHttpHeaders(): Headers {
		val builder = Headers.Builder()
		for ((name, values) in this) {
			if (name.equals(CommonHeaders.CONTENT_ENCODING, ignoreCase = true) ||
				name.equals("Transfer-Encoding", ignoreCase = true) ||
				name.equals("Content-Length", ignoreCase = true)
			) {
				continue
			}
			for (value in values) {
				try {
					builder.add(name, value)
				} catch (_: IllegalArgumentException) {
					// Skip malformed header values from upstream.
				}
			}
		}
		return builder.build()
	}

	private fun Request.bodyBytes(): ByteArray? {
		val body = body ?: return null
		val buffer = Buffer()
		body.writeTo(buffer)
		return buffer.readByteArray()
	}

	private fun shouldRequestBinary(request: Request, bodyBytes: ByteArray?): Boolean {
		val accept = request.header(CommonHeaders.ACCEPT).orEmpty().lowercase(Locale.ROOT)
		if (accept.contains("image/") || accept.contains("octet-stream") || accept.contains("video/")) {
			return true
		}
		val path = request.url.encodedPath.lowercase(Locale.ROOT)
		if (IMAGE_EXT.containsMatchIn(path)) {
			return true
		}
		val contentType = request.body?.contentType()
		if (contentType != null && isBinaryMediaType(contentType)) {
			return true
		}
		return bodyBytes != null && !isLikelyText(bodyBytes)
	}

	private fun isBinaryMediaType(type: MediaType): Boolean {
		val raw = type.toString().lowercase(Locale.ROOT)
		return raw.startsWith("image/") ||
			raw.startsWith("audio/") ||
			raw.startsWith("video/") ||
			raw.contains("octet-stream") ||
			raw.startsWith("multipart/")
	}

	private fun isLikelyText(bytes: ByteArray): Boolean {
		val sample = bytes.copyOf(minOf(bytes.size, 512))
		return sample.none { it == 0.toByte() }
	}

	private fun decodeBody(body: String): ByteArray = try {
		Base64.decode(body, Base64.DEFAULT)
	} catch (_: IllegalArgumentException) {
		body.toByteArray(Charsets.UTF_8)
	}

	private fun String.toProtocol(): Protocol = when {
		contains("h3", ignoreCase = true) || contains("http/3", ignoreCase = true) -> Protocol.HTTP_2
		contains("h2", ignoreCase = true) || contains("http/2", ignoreCase = true) -> Protocol.HTTP_2
		else -> Protocol.HTTP_1_1
	}

	private fun statusMessage(code: Int): String = when (code) {
		200 -> "OK"
		201 -> "Created"
		204 -> "No Content"
		301 -> "Moved Permanently"
		302 -> "Found"
		304 -> "Not Modified"
		400 -> "Bad Request"
		401 -> "Unauthorized"
		403 -> "Forbidden"
		404 -> "Not Found"
		429 -> "Too Many Requests"
		500 -> "Internal Server Error"
		502 -> "Bad Gateway"
		503 -> "Service Unavailable"
		else -> "HTTP $code"
	}

	companion object {
		private const val TAG = "TlsClientInterceptor"
		private val IMAGE_EXT = Regex("""\.(avif|bmp|gif|jpe?g|png|webp|svg)(/|$|\?)""", RegexOption.IGNORE_CASE)
	}
}
