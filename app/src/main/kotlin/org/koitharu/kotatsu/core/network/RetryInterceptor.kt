package org.koitharu.kotatsu.core.network

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.internal.closeQuietly
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Retries idempotent requests on transient I/O failures and 502/503/504 responses.
 */
class RetryInterceptor(
	private val maxRetries: Int = 3,
) : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		if (request.method != "GET" && request.method != "HEAD") {
			return chain.proceed(request)
		}
		var lastException: IOException? = null
		var response: Response? = null
		for (attempt in 0 until maxRetries) {
			response?.closeQuietly()
			response = null
			try {
				response = chain.proceed(request)
				if (response.isSuccessful || response.code !in RETRYABLE_HTTP_CODES) {
					return response
				}
				// A Cloudflare challenge is commonly served as 503. Retrying it hammers the
				// challenge endpoint and escalates the source from "challenged" to "blocked";
				// let CloudFlareInterceptor classify the response instead.
				if (response.isCloudFlareChallenge()) {
					return response
				}
				if (attempt == maxRetries - 1) {
					return response
				}
				response.closeQuietly()
				response = null
			} catch (e: IOException) {
				lastException = e
				if (attempt == maxRetries - 1) {
					throw e
				}
			}
			sleepBackoff(attempt)
		}
		throw lastException ?: IOException("Request failed after $maxRetries attempts")
	}

	private fun Response.isCloudFlareChallenge(): Boolean {
		return header("server")?.contains("cloudflare", ignoreCase = true) == true
	}

	private fun sleepBackoff(attempt: Int) {
		try {
			Thread.sleep(BACKOFF_BASE_MS * (attempt + 1))
		} catch (_: InterruptedException) {
			Thread.currentThread().interrupt()
		}
	}

	private companion object {
		private val RETRYABLE_HTTP_CODES = setOf(408, 502, 503, 504)
		private val BACKOFF_BASE_MS = TimeUnit.MILLISECONDS.toMillis(400)
	}
}
