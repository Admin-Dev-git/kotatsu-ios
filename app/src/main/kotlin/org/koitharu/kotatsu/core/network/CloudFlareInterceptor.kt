package org.koitharu.kotatsu.core.network

import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import okio.IOException
import org.koitharu.kotatsu.core.exceptions.CloudFlareBlockedException
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper

/**
 * Detects Cloudflare-protected responses and throws appropriate exceptions.
 *
 * This interceptor is intentionally non-blocking (no runBlocking).
 * Cloudflare protection exceptions are caught in coroutine scopes (e.g. [ParserMangaRepository],
 * [CaptchaHandler]) where [AutoCaptchaSolver] can run asynchronously without thread deadlocks.
 */
class CloudFlareInterceptor(
	private val cookieJar: CookieJar,
) : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val response = chain.proceed(request)
		return when (CloudFlareHelper.checkResponseForProtection(response)) {
			CloudFlareHelper.PROTECTION_BLOCKED -> response.closeThrowing(
				CloudFlareBlockedException(
					url = request.url.toString(),
					source = request.tag(MangaSource::class.java),
				),
			)

			CloudFlareHelper.PROTECTION_CAPTCHA -> {
				try {
					response.close()
				} catch (_: Exception) {
				}
				val source = request.tag(MangaSource::class.java)

				// Exactly one retry, and only when we actually hold clearance — another thread may
				// have solved the challenge while this request was in flight. Firing extra requests
				// at a challenged endpoint is what escalates Cloudflare from "challenge" to
				// "blocked", so an unconditional second attempt is worse than throwing.
				val clearance = CloudFlareHelper.getClearanceCookie(cookieJar, request.url.toString())
				if (clearance.isNullOrEmpty()) {
					throw CloudFlareProtectedException(
						url = request.url.toString(),
						source = source,
						headers = request.headers,
					)
				}
				val retryResponse = chain.proceed(request)
				when (CloudFlareHelper.checkResponseForProtection(retryResponse)) {
					CloudFlareHelper.PROTECTION_NOT_DETECTED -> retryResponse
					CloudFlareHelper.PROTECTION_BLOCKED -> retryResponse.closeThrowing(
						CloudFlareBlockedException(
							url = request.url.toString(),
							source = source,
						),
					)

					else -> {
						// Challenged twice while holding this clearance: the cookie is dead, whatever
						// the app thinks. Leaving it in place makes every later solve compare against
						// a value Cloudflare has already rejected, so "solved" and "still challenged"
						// stay true at the same time and the prompt returns forever. Scoped to the
						// exact value observed, so a solve that landed in the meantime is untouched.
						dropClearance(request.url, clearance)
						retryResponse.closeThrowing(
							CloudFlareProtectedException(
								url = request.url.toString(),
								source = source,
								headers = request.headers,
							),
						)
					}
				}
			}

			else -> response
		}
	}

	private fun dropClearance(url: HttpUrl, value: String) {
		val jar = cookieJar as? MutableCookieJar ?: return
		runCatching {
			jar.removeCookies(url) { cookie -> cookie.name == CF_CLEARANCE && cookie.value == value }
		}
	}

	private fun Response.closeThrowing(error: IOException): Nothing {
		try {
			close()
		} catch (e: Exception) {
			error.addSuppressed(e)
		}
		throw error
	}

	private companion object {
		private const val CF_CLEARANCE = "cf_clearance"
	}
}
