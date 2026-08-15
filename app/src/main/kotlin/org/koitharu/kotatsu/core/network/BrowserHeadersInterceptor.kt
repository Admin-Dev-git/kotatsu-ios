package org.koitharu.kotatsu.core.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Adds browser-like headers so Cloudflare-protected sources treat requests as a normal client.
 *
 * The client hints here must stay consistent with [org.koitharu.kotatsu.core.network.tls.ChromeTlsIdentity.USER_AGENT]
 * (Android Chrome) — Cloudflare re-validates an issued `cf_clearance` against them and drops it on
 * a mismatch.
 */
class BrowserHeadersInterceptor : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val builder = request.newBuilder()
		val kind = request.classify()
		if (request.header(CommonHeaders.ACCEPT) == null) {
			builder.header(
				CommonHeaders.ACCEPT,
				when (kind) {
					RequestKind.IMAGE -> "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
					RequestKind.API -> "application/json, text/plain, */*"
					RequestKind.DOCUMENT ->
						"text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
				},
			)
		}
		if (request.header("sec-ch-ua") == null) {
			builder.header("sec-ch-ua", "\"Not(A:Brand\";v=\"99\", \"Google Chrome\";v=\"133\", \"Chromium\";v=\"133\"")
		}
		if (request.header("sec-ch-ua-mobile") == null) {
			builder.header("sec-ch-ua-mobile", "?1")
		}
		if (request.header("sec-ch-ua-platform") == null) {
			builder.header("sec-ch-ua-platform", "\"Android\"")
		}
		// Fetch metadata must describe the actual request. Claiming every subresource is a
		// same-origin top-level navigation is itself a bot signal, and `sec-fetch-user: ?1` on
		// something Chrome would never label a user-activated navigation is one of the cheapest
		// inconsistencies for Cloudflare to score against us.
		if (request.header("sec-fetch-dest") == null) {
			builder.header(
				"sec-fetch-dest",
				when (kind) {
					RequestKind.IMAGE -> "image"
					RequestKind.API -> "empty"
					RequestKind.DOCUMENT -> "document"
				},
			)
		}
		if (request.header("sec-fetch-mode") == null) {
			builder.header(
				"sec-fetch-mode",
				when (kind) {
					RequestKind.IMAGE -> "no-cors"
					RequestKind.API -> "cors"
					RequestKind.DOCUMENT -> "navigate"
				},
			)
		}
		if (request.header("sec-fetch-site") == null) {
			builder.header("sec-fetch-site", request.fetchSite())
		}
		if (kind == RequestKind.DOCUMENT && request.header("sec-fetch-user") == null) {
			builder.header("sec-fetch-user", "?1")
		}
		// Do NOT set Accept-Encoding here — OkHttp adds it and transparently decompresses
		// only when the header is absent. Setting it manually breaks all parser responses.
		return chain.proceed(builder.build())
	}

	private fun Request.fetchSite(): String {
		val referer = header(CommonHeaders.REFERER)?.toHttpUrlOrNull() ?: return "none"
		return when {
			referer.host == url.host -> "same-origin"
			referer.topPrivateDomain() != null && referer.topPrivateDomain() == url.topPrivateDomain() -> "same-site"
			else -> "cross-site"
		}
	}

	private fun Request.classify(): RequestKind {
		val accept = header(CommonHeaders.ACCEPT)
		if (accept != null) {
			return when {
				accept.startsWith("image/") -> RequestKind.IMAGE
				accept.contains("application/json") || accept.contains("text/plain") -> RequestKind.API
				else -> RequestKind.DOCUMENT
			}
		}
		if (method != "GET" && method != "HEAD") {
			// A POST from a parser is an XHR/form call, never a top-level navigation.
			return RequestKind.API
		}
		val fileName = url.encodedPath.substringAfterLast('/')
		return when (fileName.substringAfterLast('.', "").lowercase()) {
			in IMAGE_EXTENSIONS -> RequestKind.IMAGE
			"json" -> RequestKind.API
			else -> RequestKind.DOCUMENT
		}
	}

	private enum class RequestKind { DOCUMENT, IMAGE, API }

	private companion object {
		private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "avif", "gif", "bmp", "jxl")
	}
}
