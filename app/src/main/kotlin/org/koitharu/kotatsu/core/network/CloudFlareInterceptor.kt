package org.koitharu.kotatsu.core.network

import okhttp3.Interceptor
import okhttp3.Response
import okio.IOException
import org.koitharu.kotatsu.core.exceptions.CloudFlareBlockedException
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper

/**
 * Detects Cloudflare-protected responses and throws appropriate exceptions.
 *
 * When a CF challenge is detected:
 * 1. If the response is a block → throws CloudFlareBlockedException (unrecoverable)
 * 2. If the response is a captcha/challenge → retries once (another thread may have
 *    already solved it and saved cf_clearance to the cookie jar), then throws
 *    CloudFlareProtectedException for CaptchaHandler to auto-resolve via WebView.
 */
class CloudFlareInterceptor : Interceptor {

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
				// Another thread may have already solved the captcha for this domain.
				// Close this response and retry once before throwing.
				try {
					response.close()
				} catch (_: Exception) {
				}
				val retryResponse = chain.proceed(request)
				when (CloudFlareHelper.checkResponseForProtection(retryResponse)) {
					CloudFlareHelper.PROTECTION_NOT_DETECTED -> retryResponse
					CloudFlareHelper.PROTECTION_BLOCKED -> retryResponse.closeThrowing(
						CloudFlareBlockedException(
							url = request.url.toString(),
							source = request.tag(MangaSource::class.java),
						),
					)
					else -> retryResponse.closeThrowing(
						CloudFlareProtectedException(
							url = request.url.toString(),
							source = request.tag(MangaSource::class.java),
							headers = request.headers,
						),
					)
				}
			}

			else -> response
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
}
