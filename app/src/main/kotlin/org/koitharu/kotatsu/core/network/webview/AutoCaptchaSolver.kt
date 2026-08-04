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
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.resume

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

		// If another thread is actively solving, wait for a new clearance cookie to appear
		if (mutex.isLocked) {
			val startTime = System.currentTimeMillis()
			while (System.currentTimeMillis() - startTime < timeout) {
				kotlinx.coroutines.delay(400)
				if (!CloudFlareHelper.getClearanceCookie(cookieJar, exception.url).isNullOrEmpty()) {
					return true
				}
				if (!mutex.isLocked) break
			}
		}

		return mutex.withLock {
			// Clear any expired clearance cookie before solving so we only accept a newly acquired cookie
			if (httpUrl != null) {
				cookieJar.removeCookies(httpUrl) { cookie ->
					cookie.name == "cf_clearance"
				}
			}

			for (attempt in 1..MAX_SOLVE_ATTEMPTS) {
				val attemptTimeout = timeout + (attempt - 1) * RETRY_TIMEOUT_INCREMENT
				val result = runCatchingCancellable {
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
									// Inject stealth BEFORE loading so CloudFlare never sees webdriver=true.
									// evaluateJavascript is async — suspend until the script has run,
									// guaranteeing it executes before the CF challenge JS runs.
									suspendCancellableCoroutine<Unit> { stealthCont ->
										webView.evaluateJavascript(
											CaptchaSolverScript.stealthScript(userAgent),
										) { stealthCont.resume(Unit) }
									}
									webView.loadUrl(exception.url)
								}
							}
							AndroidCookieJar.safeFlush(CookieManager.getInstance())
							syncCookiesFromWebView(exception.url)
						} finally {
							webView.reset()
						}
					}
				}.onFailure { e ->
					e.printStackTraceDebug()
					if (attempt == MAX_SOLVE_ATTEMPTS) {
						exception.addSuppressed(e)
					}
				}
				if (result.isSuccess) return@withLock true
			}
			false
		}
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
	}
}
