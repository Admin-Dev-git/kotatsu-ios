package org.koitharu.kotatsu.core.network.webview

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.core.network.cookies.AndroidCookieJar
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.core.network.proxy.ProxyProvider
import org.koitharu.kotatsu.core.network.tls.ChromeTlsIdentity
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.core.util.ext.configureForParser
import org.koitharu.kotatsu.core.util.ext.layoutOffscreen
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Automatically solves CloudFlare JS challenges (Turnstile, Managed Challenge)
 * by loading the challenge page in an invisible WebView and injecting JavaScript
 * to interact with challenge elements.
 */
@Singleton
class AutoCaptchaSolver @Inject constructor(
	@ApplicationContext private val context: Context,
	private val proxyProvider: ProxyProvider,
	private val cookieJar: MutableCookieJar,
	private val mangaRepositoryFactoryProvider: Provider<MangaRepository.Factory>,
	private val webViewExecutor: WebViewExecutor,
) {

	private var webViewCached: WeakReference<WebView>? = null
	private val mutex = Mutex()

	/**
	 * Attempt to automatically solve a CloudFlare captcha challenge.
	 *
	 * @param exception The [CloudFlareProtectedException] containing the blocked URL and source
	 * @param timeout Maximum time in milliseconds to wait for the challenge to be solved
	 * @return `true` if the challenge was solved (cf_clearance cookie obtained), `false` otherwise
	 */
	suspend fun trySolve(exception: CloudFlareProtectedException, timeout: Long): Boolean {
		val httpUrl = exception.url.toHttpUrlOrNull()
		val clearanceBefore = CloudFlareHelper.getClearanceCookie(cookieJar, exception.url)

		// If another thread is actively solving, wait for a new clearance cookie to appear
		if (mutex.isLocked) {
			val startTime = System.currentTimeMillis()
			while (System.currentTimeMillis() - startTime < timeout) {
				kotlinx.coroutines.delay(400)
				if (hasNewClearance(exception.url, clearanceBefore)) {
					return true
				}
				if (!mutex.isLocked) break
			}
		}

		return mutex.withLock {
			// Someone else may have solved the challenge while we were queued on the lock.
			if (hasNewClearance(exception.url, clearanceBefore)) {
				return@withLock true
			}

			for (attempt in 1..MAX_SOLVE_ATTEMPTS) {
				// Only drop the existing clearance cookie before a *retry*. Purging it upfront
				// turns any unrelated 403 into a forced re-solve, which is what made the captcha
				// come back over and over.
				if (attempt > 1 && httpUrl != null) {
					purgeClearance(httpUrl, exception.url)
				}
				val attemptTimeout = timeout + (attempt - 1) * RETRY_TIMEOUT_INCREMENT
				runCatchingCancellable {
					withContext(Dispatchers.Main.immediate) {
						val webView = obtainWebView()
						try {
							val userAgent = exception.headers[CommonHeaders.USER_AGENT]?.takeIf { it.isNotBlank() }
								?: exception.source.getUserAgent()
								?: webViewExecutor.defaultUserAgent
								?: ChromeTlsIdentity.USER_AGENT
							webView.settings.userAgentString = userAgent
							syncCookiesToWebView(exception.url)
							withTimeout(attemptTimeout) {
								suspendCancellableCoroutine { cont ->
									webView.webViewClient = AutoCaptchaWebViewClient(
										cookieJar = cookieJar,
										targetUrl = exception.url,
										userAgent = userAgent,
										continuation = cont,
									)
									if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
										runCatching {
											androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
												webView,
												CaptchaSolverScript.stealthScript(userAgent),
												setOf("*"),
											)
										}
									}
									webView.evaluateJavascript(
										CaptchaSolverScript.stealthScript(userAgent),
									) {
										webView.loadUrl(exception.url)
									}
								}
							}
						} finally {
							// Harvest cookies even when the attempt timed out or was cancelled:
							// Turnstile may have set cf_clearance moments before the deadline.
							AndroidCookieJar.safeFlush(CookieManager.getInstance())
							syncCookiesFromWebView(exception.url)
							webView.reset()
						}
					}
				}.onFailure { e ->
					e.printStackTraceDebug()
					if (attempt == MAX_SOLVE_ATTEMPTS) {
						exception.addSuppressed(e)
					}
				}
				// The WebView continuation resumes for several reasons that do not mean the
				// challenge passed (page navigated, detector said "no challenge here", …), so a
				// fresh cf_clearance cookie is the only thing accepted as proof of a solve.
				if (hasNewClearance(exception.url, clearanceBefore)) return@withLock true
			}
			false
		}
	}

	private fun hasNewClearance(url: String, previous: String?): Boolean {
		val current = CloudFlareHelper.getClearanceCookie(cookieJar, url)
		return !current.isNullOrBlank() && current != previous
	}

	/**
	 * Expire `cf_clearance` in both stores so the next attempt starts from a clean slate.
	 * Deliberately narrow: other `cf_*`/session cookies are left alone.
	 */
	private fun purgeClearance(httpUrl: HttpUrl, url: String) {
		val cookieManager = CookieManager.getInstance()
		cookieManager.setCookie(url, "cf_clearance=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
		AndroidCookieJar.safeFlush(cookieManager)
		cookieJar.removeCookies(httpUrl) { it.name == CF_CLEARANCE }
	}

	/**
	 * Sync cookies from OkHttp CookieJar to Android WebView CookieManager
	 * so the WebView starts with any existing session cookies.
	 */
	private fun syncCookiesToWebView(url: String) {
		val httpUrl = url.toHttpUrlOrNull() ?: return
		val cookies = cookieJar.loadForRequest(httpUrl)
		val cookieManager = CookieManager.getInstance()
		for (cookie in cookies) {
			cookieManager.setCookie(url, cookie.toString())
		}
		AndroidCookieJar.safeFlush(cookieManager)
	}

	/**
	 * Sync cookies from Android WebView CookieManager back to OkHttp CookieJar
	 * so cf_clearance (and related CF session cookies) are available to network calls.
	 */
	private fun syncCookiesFromWebView(url: String) {
		val httpUrl = url.toHttpUrlOrNull() ?: return
		val cookieManager = CookieManager.getInstance()
		// getCookie can throw on malformed cookie data — guard to prevent crashes
		val cookieString = runCatching { cookieManager.getCookie(url) }.getOrNull() ?: return
		val cookies = cookieString.split(";").mapNotNull { raw ->
			AndroidCookieJar.parseWebViewCookie(httpUrl, raw)
		}
		if (cookies.isNotEmpty()) {
			cookieJar.saveFromResponse(httpUrl, cookies)
		}
	}

	private suspend fun obtainWebView(): WebView {
		webViewCached?.get()?.let {
			return it
		}
		return withContext(Dispatchers.Main.immediate) {
			webViewCached?.get()?.let {
				return@withContext it
			}
			WebView(context).also {
				it.configureForParser(webViewExecutor.defaultUserAgent)
				it.layoutOffscreen()
				webViewCached = WeakReference(it)
				proxyProvider.applyWebViewConfig()
				it.onResume()
				it.resumeTimers()
			}
		}
	}

	private fun MangaSource.getUserAgent(): String? {
		val repository = mangaRepositoryFactoryProvider.get().create(this) as? ParserMangaRepository
		return repository?.getRequestHeaders()?.get(CommonHeaders.USER_AGENT)
			?: webViewExecutor.defaultUserAgent
	}

	@MainThread
	private fun WebView.reset() {
		stopLoading()
		webViewClient = WebViewClient()
		settings.userAgentString = webViewExecutor.defaultUserAgent
		loadDataWithBaseURL(null, " ", "text/html", null, null)
		clearHistory()
	}

	companion object {
		private const val MAX_SOLVE_ATTEMPTS = 2
		private const val RETRY_TIMEOUT_INCREMENT = 5_000L
		private const val CF_CLEARANCE = "cf_clearance"
	}
}
