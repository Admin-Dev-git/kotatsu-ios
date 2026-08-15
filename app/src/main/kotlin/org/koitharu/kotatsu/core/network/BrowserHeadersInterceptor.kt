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
		val isImage = request.isImageRequest()
		if (request.header(CommonHeaders.ACCEPT) == null) {
			builder.header(
				CommonHeaders.ACCEPT,
				if (isImage) {
					"image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
				} else {
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
		// same-origin top-level navigation is itself a bot signal.
		if (request.header("sec-fetch-dest") == null) {
			builder.header("sec-fetch-dest", if (isImage) "image" else "document")
		}
		if (request.header("sec-fetch-mode") == null) {
			builder.header("sec-fetch-mode", if (isImage) "no-cors" else "navigate")
		}
		if (request.header("sec-fetch-site") == null) {
			builder.header("sec-fetch-site", request.fetchSite())
		}
		if (!isImage && request.header("sec-fetch-user") == null) {
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

	private fun Request.isImageRequest(): Boolean {
		val accept = header(CommonHeaders.ACCEPT)
		if (accept != null) {
			return accept.startsWith("image/")
		}
		val path = url.encodedPath.substringAfterLast('/')
		val extension = path.substringAfterLast('.', "").lowercase()
		return extension in IMAGE_EXTENSIONS
	}

	private companion object {
		private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "avif", "gif", "bmp", "jxl")
	}
}
