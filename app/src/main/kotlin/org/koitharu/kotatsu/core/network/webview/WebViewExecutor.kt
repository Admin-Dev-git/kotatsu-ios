package org.koitharu.kotatsu.core.network.webview

import android.content.Context
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
import org.koitharu.kotatsu.core.exceptions.CloudFlareException
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
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Singleton
class WebViewExecutor @Inject constructor(
	@ApplicationContext private val context: Context,
	private val proxyProvider: ProxyProvider,
	private val cookieJar: MutableCookieJar,
	private val mangaRepositoryFactoryProvider: Provider<MangaRepository.Factory>,
) {

	private var webViewCached: WeakReference<WebView>? = null
	private val mutex = Mutex()

	val defaultUserAgent: String by lazy {
		ChromeTlsIdentity.USER_AGENT
	}

	suspend fun evaluateJs(baseUrl: String?, script: String): String? = mutex.withLock {
		withContext(Dispatchers.Main.immediate) {
			val webView = obtainWebView()
			try {
				if (!baseUrl.isNullOrEmpty()) {
					suspendCoroutine { cont ->
						webView.webViewClient = ContinuationResumeWebViewClient(cont)
						webView.loadDataWithBaseURL(baseUrl, " ", "text/html", null, null)
					}
				}
				suspendCoroutine { cont ->
					webView.evaluateJavascript(script) { result ->
						cont.resume(result?.takeUnless { it == "null" })
					}
				}
			} finally {
				webView.reset()
			}
		}
	}

	suspend fun tryResolveCaptcha(exception: CloudFlareException, timeout: Long): Boolean = mutex.withLock {
		// Retry up to MAX_RESOLVE_ATTEMPTS times with increasing timeout
		for (attempt in 1..MAX_RESOLVE_ATTEMPTS) {
			val attemptTimeout = timeout + (attempt - 1) * RETRY_TIMEOUT_INCREMENT
			val result = runCatchingCancellable {
				withContext(Dispatchers.Main.immediate) {
					val webView = obtainWebView()
					try {
						// Must match tls-client UA or Cloudflare rejects cf_clearance.
						val protectedHeaders = (exception as? CloudFlareProtectedException)?.headers
						webView.settings.userAgentString =
							protectedHeaders?.get(CommonHeaders.USER_AGENT)?.takeIf { it.isNotBlank() }
								?: exception.source.getUserAgent()
								?: defaultUserAgent
						// Sync existing cookies to WebView before loading
						syncCookiesToWebView(exception.url)
						// Inject stealth BEFORE loading so CloudFlare never sees webdriver=true.
						// Suspend until it completes so it definitely runs before the challenge JS.
						suspendCoroutine<Unit> { stealthCont ->
							webView.evaluateJavascript(
								CaptchaSolverScript.stealthScript(webView.settings.userAgentString),
							) { stealthCont.resume(Unit) }
						}
						withTimeout(attemptTimeout) {
							suspendCancellableCoroutine { cont ->
								webView.webViewClient = CaptchaContinuationClient(
									cookieJar = cookieJar,
									targetUrl = exception.url,
									continuation = cont,
								)
								webView.loadUrl(exception.url)
							}
						}
						// Flush and sync cookies back
						org.koitharu.kotatsu.core.network.cookies.AndroidCookieJar.safeFlush(android.webkit.CookieManager.getInstance())
						syncCookiesFromWebView(exception.url)
					} finally {
						webView.reset()
					}
				}
			}.onFailure { e ->
				e.printStackTraceDebug()
				if (attempt == MAX_RESOLVE_ATTEMPTS) {
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
		val cookieManager = android.webkit.CookieManager.getInstance()
		for (cookie in cookies) {
			cookieManager.setCookie(url, cookie.toString())
		}
		org.koitharu.kotatsu.core.network.cookies.AndroidCookieJar.safeFlush(cookieManager)
	}

	/**
	 * Sync cookies from Android WebView CookieManager back to OkHttp CookieJar
	 * to ensure cf_clearance and other session cookies are available.
	 */
	private fun syncCookiesFromWebView(url: String) {
		val httpUrl = url.toHttpUrlOrNull() ?: return
		val cookieManager = android.webkit.CookieManager.getInstance()
		val cookieString = runCatching { cookieManager.getCookie(url) }.getOrNull() ?: return
		val cookies = cookieString.split(";").mapNotNull { raw ->
			org.koitharu.kotatsu.core.network.cookies.AndroidCookieJar.parseWebViewCookie(httpUrl, raw)
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
				it.configureForParser(defaultUserAgent)
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
			?: defaultUserAgent
	}

	@MainThread
	private fun WebView.reset() {
		stopLoading()
		webViewClient = WebViewClient()
		settings.userAgentString = defaultUserAgent
		loadDataWithBaseURL(null, " ", "text/html", null, null)
		clearHistory()
	}

	companion object {
		private const val MAX_RESOLVE_ATTEMPTS = 3
		private const val RETRY_TIMEOUT_INCREMENT = 10_000L // Add 10s per retry
	}
}
