package org.koitharu.kotatsu.core.network.webview

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellableContinuation
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/**
 * A [WebViewClient] that automatically solves CloudFlare JS challenges.
 *
 * On each page load it:
 * 1. Injects stealth anti-detection script on page start (before page scripts run)
 * 2. Syncs WebView cookies into OkHttp and checks if `cf_clearance` changed
 * 3. If not solved, injects [CaptchaSolverScript] (detect + continuous solve loop)
 * 4. Polls for clearance every [COOKIE_CHECK_INTERVAL] ms (Turnstile often sets
 *    the cookie without further navigation events)
 * 5. Resumes the continuation when the challenge is solved
 */
internal class AutoCaptchaWebViewClient(
	private val cookieJar: MutableCookieJar,
	private val targetUrl: String,
	private val continuation: Continuation<Unit>,
) : WebViewClient() {

	private val oldClearance = CloudFlareHelper.getClearanceCookie(cookieJar, targetUrl)
	private val handler = Handler(Looper.getMainLooper())
	private var webViewRef: WebView? = null

	@Volatile
	private var scriptInjectCount = 0

	@Volatile
	private var continuousLoopStarted = false

	private val cookieCheckRunnable: Runnable = object : Runnable {
		override fun run() {
			if (isResumed) return
			syncCookiesFromWebView()
			if (isClearanceObtained()) {
				resumeOnce(webViewRef)
			} else {
				// Re-inject solver periodically — widgets often mount late.
				webViewRef?.let { maybeReinjectSolver(it) }
				handler.postDelayed(this, COOKIE_CHECK_INTERVAL)
			}
		}
	}

	override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
		super.onPageStarted(view, url, favicon)
		webViewRef = view
		// Inject stealth script on every page start to ensure it's active
		// before any page scripts execute (CloudFlare checks fingerprints early).
		view?.let { injectStealthScript(it) }
		syncCookiesFromWebView()
		if (isClearanceObtained()) {
			resumeOnce(view)
			return
		}
		// Start periodic cookie polling to catch Turnstile solutions.
		handler.removeCallbacks(cookieCheckRunnable)
		handler.postDelayed(cookieCheckRunnable, COOKIE_CHECK_INTERVAL)
	}

	override fun onPageFinished(view: WebView?, url: String?) {
		super.onPageFinished(view, url)
		if (isResumed) return

		webViewRef = view
		syncCookiesFromWebView()
		if (isClearanceObtained()) {
			resumeOnce(view)
			return
		}

		// Inject the auto-solve script (and start continuous loop if needed).
		view?.let { injectSolverScript(it, forceContinuous = true) }
	}

	override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
		super.doUpdateVisitedHistory(view, url, isReload)
		// URL changed — often means challenge redirect completed.
		syncCookiesFromWebView()
		if (isClearanceObtained()) {
			resumeOnce(view)
		}
	}

	private fun isClearanceObtained(): Boolean {
		val clearance = CloudFlareHelper.getClearanceCookie(cookieJar, targetUrl)
		return clearance != null && clearance != oldClearance
	}

	/**
	 * Inject the stealth anti-detection script. This masks bot fingerprints
	 * (navigator.webdriver, missing window.chrome, empty plugins, etc.)
	 * that CloudFlare Turnstile checks before presenting the challenge.
	 */
	private fun injectStealthScript(webView: WebView) {
		try {
			webView.evaluateJavascript(CaptchaSolverScript.STEALTH_SCRIPT, null)
		} catch (e: Exception) {
			e.printStackTraceDebug()
		}
	}

	private fun maybeReinjectSolver(webView: WebView) {
		if (isResumed) return
		if (scriptInjectCount >= MAX_SCRIPT_INJECTIONS) return
		// Light re-inject of one-shot click strategies without restarting the loop.
		scriptInjectCount++
		try {
			webView.evaluateJavascript(CaptchaSolverScript.SOLVE_SCRIPT, null)
		} catch (e: Exception) {
			e.printStackTraceDebug()
		}
	}

	private fun injectSolverScript(webView: WebView, forceContinuous: Boolean) {
		if (isResumed) return
		if (scriptInjectCount >= MAX_SCRIPT_INJECTIONS && continuousLoopStarted) return

		try {
			webView.evaluateJavascript(CaptchaSolverScript.DETECT_CHALLENGE_SCRIPT) { result ->
				if (isResumed) return@evaluateJavascript
				val isChallenge = result?.contains("true") == true
				if (!isChallenge) {
					// Page may already have passed; re-check cookies once more.
					syncCookiesFromWebView()
					if (isClearanceObtained()) {
						resumeOnce(webView)
					}
					return@evaluateJavascript
				}

				scriptInjectCount++
				// One-shot click attempt (covers managed checkbox / verify buttons).
				webView.evaluateJavascript(CaptchaSolverScript.SOLVE_SCRIPT) {
					syncCookiesFromWebView()
					if (isClearanceObtained()) {
						resumeOnce(webView)
					}
				}

				// Continuous loop handles delayed Turnstile widget mounts & re-tries.
				if (forceContinuous && !continuousLoopStarted) {
					continuousLoopStarted = true
					webView.evaluateJavascript(CaptchaSolverScript.CONTINUOUS_SOLVE_SCRIPT, null)
				}
			}
		} catch (e: Exception) {
			e.printStackTraceDebug()
		}
	}

	/**
	 * Sync cookies from Android WebView CookieManager back into OkHttp's CookieJar.
	 * Without this, [isClearanceObtained] never sees `cf_clearance` set by the WebView.
	 *
	 * Constructs cookies with explicit domain/path to ensure they match subsequent
	 * requests to the same domain (including subdomains).
	 */
	private fun syncCookiesFromWebView() {
		val httpUrl = targetUrl.toHttpUrlOrNull() ?: return
		val cookieManager = CookieManager.getInstance()
		val cookieString = cookieManager.getCookie(targetUrl) ?: return
		val domain = httpUrl.host
		val cookies = cookieString.split(";").mapNotNull { raw ->
			val trimmed = raw.trim()
			if (trimmed.isEmpty()) return@mapNotNull null
			val eqIndex = trimmed.indexOf('=')
			if (eqIndex <= 0) return@mapNotNull null
			val name = trimmed.substring(0, eqIndex).trim()
			val value = trimmed.substring(eqIndex + 1).trim()
			Cookie.Builder()
				.name(name)
				.value(value)
				.domain(domain)
				.path("/")
				.secure()
				.build()
		}
		if (cookies.isNotEmpty()) {
			cookieJar.saveFromResponse(httpUrl, cookies)
		}
	}

	private val isResumed: Boolean
		get() = continuation is CancellableContinuation && !continuation.isActive

	private fun resumeOnce(view: WebView?) {
		if (isResumed) return
		handler.removeCallbacks(cookieCheckRunnable)
		syncCookiesFromWebView()
		if (continuation is CancellableContinuation) {
			if (continuation.isActive) {
				view?.webViewClient = WebViewClient() // stop further callbacks
				continuation.resume(Unit)
			}
		} else {
			view?.webViewClient = WebViewClient()
			continuation.resume(Unit)
		}
	}

	companion object {
		private const val MAX_SCRIPT_INJECTIONS = 20
		private const val COOKIE_CHECK_INTERVAL = 500L
	}
}
