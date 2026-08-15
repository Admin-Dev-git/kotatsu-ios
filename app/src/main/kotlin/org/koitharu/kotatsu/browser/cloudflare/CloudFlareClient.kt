package org.koitharu.kotatsu.browser.cloudflare

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.browser.BrowserClient
import org.koitharu.kotatsu.core.network.cookies.AndroidCookieJar
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.core.network.webview.CaptchaSolverScript
import org.koitharu.kotatsu.core.network.webview.adblock.AdBlock
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper

class CloudFlareClient(
	private val cookieJar: MutableCookieJar,
	private val callback: CloudFlareCallback,
	adBlock: AdBlock,
	private val targetUrl: String,
	private val userAgent: String = "",
) : BrowserClient(callback, adBlock) {

	private val handler = Handler(Looper.getMainLooper())
	private val oldClearance = CloudFlareHelper.getClearanceCookie(cookieJar, targetUrl)
	private var webViewRef: WebView? = null
	private var checkPassedFired = false

	private val cookieCheckRunnable: Runnable = object : Runnable {
		override fun run() {
			if (checkPassedFired) return
			syncCookiesFromWebView(webViewRef)
			if (checkClearance(webViewRef)) {
				return
			}
			webViewRef?.let { tryAutoSolve(it) }
			handler.postDelayed(this, 300L)
		}
	}

	override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
		super.onPageStarted(view, url, favicon)
		webViewRef = view
		injectStealthScript(view)
		syncCookiesFromWebView(view)
		if (checkClearance(view)) return
		handler.removeCallbacks(cookieCheckRunnable)
		handler.postDelayed(cookieCheckRunnable, 300L)
	}

	override fun onPageCommitVisible(view: WebView, url: String) {
		super.onPageCommitVisible(view, url)
		callback.onPageLoaded()
	}

	override fun onPageFinished(webView: WebView, url: String) {
		super.onPageFinished(webView, url)
		webViewRef = webView
		callback.onPageLoaded()
		syncCookiesFromWebView(webView)
		if (checkClearance(webView)) return
		// No *new* clearance yet. If the page is not a challenge at all, the existing cookie is
		// evidently being accepted, so treat that as done instead of leaving the user staring at a
		// loaded page forever.
		webView.evaluateJavascript(CaptchaSolverScript.DETECT_CHALLENGE_SCRIPT) { result ->
			if (checkPassedFired) return@evaluateJavascript
			if (result?.contains("true") != true &&
				!CloudFlareHelper.getClearanceCookie(cookieJar, targetUrl).isNullOrBlank()
			) {
				firePassed()
			} else {
				tryAutoSolve(webView)
			}
		}
	}

	override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
		super.doUpdateVisitedHistory(view, url, isReload)
		syncCookiesFromWebView(view)
		checkClearance(view)
	}

	fun reset() {
		checkPassedFired = false
		handler.removeCallbacks(cookieCheckRunnable)
	}

	private fun checkClearance(view: WebView?): Boolean {
		if (checkPassedFired) return true
		syncCookiesFromWebView(view)
		// Only a fresh, non-blank cookie proves the challenge was passed. Accepting the cookie that
		// was already rejected is what let the app declare success and get challenged again.
		val clearance = CloudFlareHelper.getClearanceCookie(cookieJar, targetUrl)
		if (!clearance.isNullOrBlank() && clearance != oldClearance) {
			firePassed()
			return true
		}
		return false
	}

	private fun firePassed() {
		if (checkPassedFired) return
		checkPassedFired = true
		handler.removeCallbacks(cookieCheckRunnable)
		callback.onCheckPassed()
	}

	private fun injectStealthScript(view: WebView?) {
		if (view == null) return
		try {
			view.evaluateJavascript(CaptchaSolverScript.stealthScript(userAgent), null)
		} catch (e: Exception) {
			e.printStackTraceDebug()
		}
	}

	private fun tryAutoSolve(view: WebView) {
		if (checkPassedFired) return
		try {
			view.evaluateJavascript(CaptchaSolverScript.SOLVE_SCRIPT) {
				syncCookiesFromWebView(view)
				checkClearance(view)
			}
			dispatchHardwareTouch(view)
		} catch (e: Exception) {
			e.printStackTraceDebug()
		}
	}

	private fun dispatchHardwareTouch(view: WebView) {
		try {
			view.evaluateJavascript(CaptchaSolverScript.GET_WIDGET_COORDINATES_SCRIPT) { res ->
				if (checkPassedFired) return@evaluateJavascript
				val coords = res?.trim('"')?.replace("\\", "")?.split(',')
				if (coords != null && coords.size == 2) {
					val xDp = coords[0].toFloatOrNull() ?: return@evaluateJavascript
					val yDp = coords[1].toFloatOrNull() ?: return@evaluateJavascript
					val density = view.resources.displayMetrics.density
					val xPx = xDp * density
					val yPx = yDp * density
					val downTime = android.os.SystemClock.uptimeMillis()
					val eventTime = android.os.SystemClock.uptimeMillis()
					val downEvent = android.view.MotionEvent.obtain(downTime, eventTime, android.view.MotionEvent.ACTION_DOWN, xPx, yPx, 0)
					val upEvent = android.view.MotionEvent.obtain(downTime, eventTime + 100, android.view.MotionEvent.ACTION_UP, xPx, yPx, 0)
					view.dispatchTouchEvent(downEvent)
					view.dispatchTouchEvent(upEvent)
					downEvent.recycle()
					upEvent.recycle()
				}
			}
		} catch (e: Exception) {
			e.printStackTraceDebug()
		}
	}

	private fun syncCookiesFromWebView(view: WebView?) {
		val httpUrl = targetUrl.toHttpUrlOrNull() ?: return
		val cookieManager = CookieManager.getInstance()
		val cookieString = runCatching { cookieManager.getCookie(targetUrl) }.getOrNull() ?: return
		val cookies = cookieString.split(";").mapNotNull { raw ->
			AndroidCookieJar.parseWebViewCookie(httpUrl, raw)
		}
		if (cookies.isNotEmpty()) {
			cookieJar.saveFromResponse(httpUrl, cookies)
		}
		AndroidCookieJar.safeFlush(cookieManager)
	}
}
