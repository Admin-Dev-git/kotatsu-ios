package org.koitharu.kotatsu.core.network.webview

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellableContinuation
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.core.network.cookies.AndroidCookieJar
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/**
 * A [WebViewClient] that automatically solves CloudFlare JS challenges.
 *
 * On each page load it:
 * 1. Syncs WebView cookies into OkHttp and checks if `cf_clearance` changed
 * 2. If not solved, injects [CaptchaSolverScript] (detect + continuous solve loop)
 * 3. Polls for clearance every [COOKIE_CHECK_INTERVAL] ms (Turnstile often sets
 *    the cookie without further navigation events)
 * 4. Resumes the continuation when the challenge is solved
 *
 * Note: The stealth script ([CaptchaSolverScript.stealthScript]) must be injected
 * BEFORE [WebView.loadUrl] — see [AutoCaptchaSolver.trySolve].
 */
internal class AutoCaptchaWebViewClient(
	private val cookieJar: MutableCookieJar,
	private val targetUrl: String,
	private val userAgent: String = "",
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
		view?.evaluateJavascript(CaptchaSolverScript.stealthScript(userAgent), null)
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

	/**
	 * Clearance means a **new**, non-blank `cf_clearance`. A pre-existing cookie (the one that was
	 * already rejected) or the blank value left behind by a purge must never count as a solve,
	 * otherwise the caller reports success and the very next request is challenged again.
	 */
	private fun isClearanceObtained(): Boolean {
		val clearance = CloudFlareHelper.getClearanceCookie(cookieJar, targetUrl)
		if (!clearance.isNullOrBlank() && clearance != oldClearance) return true
		// Fall back to the WebView store: the jar sync may not have run yet.
		val httpUrl = targetUrl.toHttpUrlOrNull() ?: return false
		val cookieManager = CookieManager.getInstance()
		val rawCookies = runCatching { cookieManager.getCookie(targetUrl) }.getOrNull() ?: return false
		return rawCookies.split(';').any { raw ->
			val cookie = AndroidCookieJar.parseWebViewCookie(httpUrl, raw) ?: return@any false
			cookie.name == CF_CLEARANCE && cookie.value.isNotBlank() && cookie.value != oldClearance
		}
	}

	private fun maybeReinjectSolver(webView: WebView) {
		if (isResumed) return
		if (scriptInjectCount >= MAX_SCRIPT_INJECTIONS) return
		// Light re-inject of one-shot click strategies without restarting the loop.
		scriptInjectCount++
		try {
			dispatchHardwareTouch(webView)
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
					// Page navigated past Cloudflare challenge screen — challenge solved!
					syncCookiesFromWebView()
					resumeOnce(webView)
					return@evaluateJavascript
				}

				scriptInjectCount++
				dispatchHardwareTouch(webView)
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

	private fun dispatchHardwareTouch(webView: WebView) {
		try {
			webView.evaluateJavascript(CaptchaSolverScript.GET_WIDGET_COORDINATES_SCRIPT) { res ->
				if (isResumed) return@evaluateJavascript
				val coords = res?.trim('"')?.replace("\\", "")?.split(',')
				if (coords != null && coords.size == 2) {
					val xDp = coords[0].toFloatOrNull() ?: return@evaluateJavascript
					val yDp = coords[1].toFloatOrNull() ?: return@evaluateJavascript
					val density = webView.resources.displayMetrics.density
					val xPx = xDp * density
					val yPx = yDp * density
					val downTime = android.os.SystemClock.uptimeMillis()
					val eventTime = android.os.SystemClock.uptimeMillis()

					val properties = arrayOf(android.view.MotionEvent.PointerProperties().apply {
						id = 0
						toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
					})
					val coordsArray = arrayOf(android.view.MotionEvent.PointerCoords().apply {
						x = xPx
						y = yPx
						pressure = 0.8f
						size = 0.2f
						touchMajor = 24.0f
						touchMinor = 24.0f
					})

					val downEvent = android.view.MotionEvent.obtain(
						downTime, eventTime, android.view.MotionEvent.ACTION_DOWN,
						1, properties, coordsArray, 0, 0, 1.0f, 1.0f, 0, 0,
						android.view.InputDevice.SOURCE_TOUCHSCREEN, 0
					)
					val upEvent = android.view.MotionEvent.obtain(
						downTime, eventTime + 120, android.view.MotionEvent.ACTION_UP,
						1, properties, coordsArray, 0, 0, 1.0f, 1.0f, 0, 0,
						android.view.InputDevice.SOURCE_TOUCHSCREEN, 0
					)
					webView.dispatchTouchEvent(downEvent)
					webView.dispatchTouchEvent(upEvent)
					downEvent.recycle()
					upEvent.recycle()
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
	 * Uses [runCatching] around getCookie to guard against crashes on malformed cookie data.
	 */
	private fun syncCookiesFromWebView() {
		val httpUrl = targetUrl.toHttpUrlOrNull() ?: return
		val cookieManager = CookieManager.getInstance()
		val cookieString = runCatching { cookieManager.getCookie(targetUrl) }.getOrNull() ?: return
		val cookies = cookieString.split(";").mapNotNull { raw ->
			AndroidCookieJar.parseWebViewCookie(httpUrl, raw)
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
		private const val MAX_SCRIPT_INJECTIONS = 25
		private const val COOKIE_CHECK_INTERVAL = 300L
		private const val CF_CLEARANCE = "cf_clearance"
	}
}
