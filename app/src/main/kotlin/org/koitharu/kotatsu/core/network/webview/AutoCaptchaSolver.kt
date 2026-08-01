package org.koitharu.kotatsu.core.network.webview

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.core.network.proxy.ProxyProvider
import org.koitharu.kotatsu.core.network.tls.ChromeTlsIdentity
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.core.util.ext.configureForParser
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Automatically solves CloudFlare JS challenges (Turnstile, Managed Challenge)
 * by loading the challenge page in an invisible WebView and injecting JavaScript
 * to interact with challenge elements.
 *
 * This runs BEFORE the existing [WebViewExecutor.tryResolveCaptcha] as a first
 * line of defense. If auto-solving fails, the existing flow continues unchanged.
 *
 * Important: User-Agent must match [ChromeTlsIdentity.USER_AGENT] so any
 * `cf_clearance` cookie is accepted by subsequent OkHttp / tls-client requests.
 */
@Singleton
class AutoCaptchaSolver @Inject constructor(
	@ApplicationContext private val context: Context,
	private val proxyProvider: ProxyProvider,
	private val cookieJar: MutableCookieJar,
	private val mangaRepositoryFactoryProvider: Provider<MangaRepository.Factory>,
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
	suspend fun trySolve(exception: CloudFlareProtectedException, timeout: Long): Boolean = mutex.withLock {
		// Retry a few times — Turnstile widgets often appear after a delay / soft fail.
		for (attempt in 1..MAX_SOLVE_ATTEMPTS) {
			val attemptTimeout = timeout + (attempt - 1) * RETRY_TIMEOUT_INCREMENT
			val result = runCatchingCancellable {
				withContext(Dispatchers.Main.immediate) {
					val webView = obtainWebView()
					try {
						// Must match network stack UA or Cloudflare rejects cf_clearance.
						webView.settings.userAgentString =
							exception.source.getUserAgent() ?: ChromeTlsIdentity.USER_AGENT
						// Seed WebView with existing session cookies before challenge load.
						syncCookiesToWebView(exception.url)
						withTimeout(attemptTimeout) {
							suspendCancellableCoroutine { cont ->
								webView.webViewClient = AutoCaptchaWebViewClient(
									cookieJar = cookieJar,
									targetUrl = exception.url,
									continuation = cont,
								)
								webView.loadUrl(exception.url)
							}
						}
						// Persist and pull clearance back into OkHttp CookieJar.
						CookieManager.getInstance().flush()
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
		cookieManager.flush()
	}

	/**
	 * Sync cookies from Android WebView CookieManager back to OkHttp CookieJar
	 * so cf_clearance (and related CF session cookies) are available to network calls.
	 */
	private fun syncCookiesFromWebView(url: String) {
		val httpUrl = url.toHttpUrlOrNull() ?: return
		val cookieManager = CookieManager.getInstance()
		val cookieString = cookieManager.getCookie(url) ?: return
		val cookies = cookieString.split(";").mapNotNull { raw ->
			val trimmed = raw.trim()
			if (trimmed.isEmpty()) return@mapNotNull null
			Cookie.parse(httpUrl, trimmed)
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
				it.configureForParser(ChromeTlsIdentity.USER_AGENT)
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
			?: ChromeTlsIdentity.USER_AGENT
	}

	@MainThread
	private fun WebView.reset() {
		stopLoading()
		webViewClient = WebViewClient()
		settings.userAgentString = ChromeTlsIdentity.USER_AGENT
		loadDataWithBaseURL(null, " ", "text/html", null, null)
		clearHistory()
	}

	companion object {
		private const val MAX_SOLVE_ATTEMPTS = 2
		private const val RETRY_TIMEOUT_INCREMENT = 5_000L
	}
}
