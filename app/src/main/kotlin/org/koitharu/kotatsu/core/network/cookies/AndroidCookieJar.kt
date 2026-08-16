package org.koitharu.kotatsu.core.network.cookies

import android.webkit.CookieManager
import androidx.annotation.WorkerThread
import androidx.core.util.Predicate
import okhttp3.Cookie
import okhttp3.HttpUrl
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AndroidCookieJar : MutableCookieJar {

	private val cookieManager = CookieManager.getInstance()

	@WorkerThread
	override fun loadForRequest(url: HttpUrl): List<Cookie> {
		val rawCookie = runCatching { cookieManager.getCookie(url.toString()) }.getOrNull() ?: return emptyList()
		return rawCookie.split(';').mapNotNull {
			parseWebViewCookie(url, it)
		}
	}

	@WorkerThread
	override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
		if (cookies.isEmpty()) {
			return
		}
		val urlString = url.toString()
		runCatching {
			for (cookie in cookies) {
				cookieManager.setCookie(urlString, cookie.toString())
			}
			safeFlush(cookieManager)
		}
	}

	override fun removeCookies(url: HttpUrl, predicate: Predicate<Cookie>?) {
		val cookies = loadForRequest(url)
		if (cookies.isEmpty()) {
			return
		}
		val urlString = url.toString()
		runCatching {
			for (c in cookies) {
				if (predicate != null && !predicate.test(c)) {
					continue
				}
				val nc = c.newBuilder()
					.expiresAt(System.currentTimeMillis() - 100000)
					.build()
				cookieManager.setCookie(urlString, nc.toString())
			}
			safeFlush(cookieManager)
		}
	}

	override suspend fun clear() = suspendCoroutine<Boolean> { continuation ->
		runCatching {
			cookieManager.removeAllCookies(continuation::resume)
		}.onFailure {
			continuation.resume(false)
		}
	}

	companion object {
		fun safeFlush(cookieManager: CookieManager) {
			try {
				cookieManager.flush()
			} catch (e: Throwable) {
				runCatching {
					java.util.concurrent.Executors.newSingleThreadExecutor().execute {
						runCatching { cookieManager.flush() }
					}
				}
			}
		}

		/**
		 * Convert one entry of [CookieManager.getCookie]'s `name=value; name=value` output into an
		 * OkHttp [Cookie].
		 *
		 * The WebView deliberately hides every cookie attribute, so the missing ones have to be
		 * synthesised — and they have to be synthesised to the *same* values the server originally
		 * sent, otherwise the same cookie ends up stored twice under two different identities
		 * (a cookie is keyed by name + domain + path). Two `cf_clearance` entries then go out in one
		 * `Cookie` header, Cloudflare reads the stale one and challenges again, forever.
		 *
		 * - `path`: `/`, not OkHttp's default-path. Without it a cookie harvested while solving a
		 *   challenge at `/manga/x/1` is scoped to `/manga/x`, so it is not even sent to `/`.
		 * - `secure`: mirrors the URL scheme, so an https cookie keeps the flag the server set.
		 */
		fun parseWebViewCookie(url: HttpUrl, rawCookie: String): Cookie? {
			val trimmed = rawCookie.trim()
			if (trimmed.isEmpty()) return null
			// Only the part after the first ';' can hold attributes; a value that happens to contain
			// "path=" must not be mistaken for one.
			val attrs = trimmed.substringAfter(';', "")
			val topDomain = runCatching { url.topPrivateDomain() }.getOrNull()
				?: extractRootDomain(url.host)
			val builder = StringBuilder(trimmed)
			if (!attrs.contains("domain=", ignoreCase = true)) {
				builder.append("; domain=.").append(topDomain)
			}
			if (!attrs.contains("path=", ignoreCase = true)) {
				builder.append("; path=/")
			}
			if (url.isHttps && !attrs.contains("secure", ignoreCase = true)) {
				builder.append("; secure")
			}
			return Cookie.parse(url, builder.toString())
				?: Cookie.parse(url, trimmed)
		}

		private fun extractRootDomain(host: String): String {
			val parts = host.split('.')
			if (parts.size >= 2 && !host.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
				return parts.takeLast(2).joinToString(".")
			}
			return host
		}
	}
}
