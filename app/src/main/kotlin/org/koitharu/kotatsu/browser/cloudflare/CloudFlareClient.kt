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
import org.koitharu.kotatsu.core.network.webview.tapChallengeWidget
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper

class CloudFlareClient(
	private val cookieJar: MutableCookieJar,
	private val callback: CloudFlareCallback,
	adBlock: AdBlock,
	private val targetUrl: String,
) : BrowserClient(callback, adBlock) {

	private val oldClearance = getClearance()
	private val handler = Handler(Looper.getMainLooper())
	private var webViewRef: WebView? = null
	private var checkPassedFired = false
	private var isDisposed = false

	/** Last raw `CookieManager` string already written to the jar, so a quiet poll costs nothing. */
	private var lastSyncedCookies: String? = null

	private val cookieCheckRunnable: Runnable = object : Runnable {
		override fun run() {
			if (isIdle) return
			if (syncAndCheckClearance()) return
			handler.postDelayed(this, COOKIE_CHECK_INTERVAL)
		}
	}

	override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
		super.onPageStarted(view, url, favicon)
		if (isDisposed) return
		webViewRef = view
		injectStealthScript(view)
		if (syncAndCheckClearance()) return
		// Turnstile commonly sets the cookie with no further navigation event, so polling — not
		// page callbacks — is what notices a solve.
		handler.removeCallbacks(cookieCheckRunnable)
		handler.postDelayed(cookieCheckRunnable, COOKIE_CHECK_INTERVAL)
	}

	override fun onPageCommitVisible(view: WebView, url: String) {
		super.onPageCommitVisible(view, url)
		callback.onPageLoaded()
	}

	override fun onPageFinished(webView: WebView, url: String) {
		super.onPageFinished(webView, url)
		if (isDisposed) return
		webViewRef = webView
		callback.onPageLoaded()
		if (syncAndCheckClearance()) return
		tryAutoSolve(webView)
	}

	override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
		super.doUpdateVisitedHistory(view, url, isReload)
		if (isDisposed) return
		syncAndCheckClearance()
	}

	fun reset() {
		checkPassedFired = false
		lastSyncedCookies = null
		handler.removeCallbacks(cookieCheckRunnable)
	}

	/**
	 * Stop polling for good. The runnable re-posts itself every [COOKIE_CHECK_INTERVAL] ms and holds
	 * both the WebView and the callback (the activity), so without this it outlives the screen the
	 * user just closed.
	 */
	fun dispose() {
		isDisposed = true
		handler.removeCallbacks(cookieCheckRunnable)
		webViewRef = null
	}

	private val isIdle: Boolean
		get() = isDisposed || checkPassedFired

	private fun syncAndCheckClearance(): Boolean {
		syncCookiesFromWebView()
		return checkClearance()
	}

	private fun checkClearance(): Boolean {
		if (isIdle) return checkPassedFired
		// Only a *fresh* clearance means the challenge was just solved. A pre-existing
		// (stale) cf_clearance must NOT count — otherwise the activity reports success on
		// open, closes, the network retry fails against the stale cookie, and the captcha
		// is raised again immediately: an endless loop even "after solving manually".
		val clearance = getClearance()
		if (clearance.isNullOrEmpty() || clearance == oldClearance) {
			return false
		}
		checkPassedFired = true
		handler.removeCallbacks(cookieCheckRunnable)
		callback.onCheckPassed()
		return true
	}

	private fun injectStealthScript(view: WebView?) {
		if (view == null) return
		try {
			view.evaluateJavascript(CaptchaSolverScript.STEALTH_SCRIPT, null)
		} catch (e: Exception) {
			e.printStackTraceDebug()
		}
	}

	private fun tryAutoSolve(view: WebView) {
		if (isIdle) return
		try {
			view.evaluateJavascript(CaptchaSolverScript.SOLVE_SCRIPT) {
				syncAndCheckClearance()
			}
			view.tapChallengeWidget(isObsolete = { isIdle })
		} catch (e: Exception) {
			e.printStackTraceDebug()
		}
	}

	/**
	 * Pull the cookies the WebView earned into OkHttp's jar.
	 *
	 * Parsing goes through [AndroidCookieJar.parseWebViewCookie]. `CookieManager.getCookie` returns
	 * `name=value` with every attribute stripped, so a bare `Cookie.parse` applies OkHttp's
	 * default-path rule and files the clearance under a second identity scoped to this challenge
	 * URL's directory. Both copies then match later requests and both are sent; Cloudflare reads the
	 * stale one and challenges again — which is why solving here by hand still looped.
	 *
	 * Called from a poll, so it skips the write (and the flush) while the WebView's cookies are
	 * unchanged — otherwise every tick rewrites the persistent jar for nothing.
	 */
	private fun syncCookiesFromWebView() {
		val httpUrl = targetUrl.toHttpUrlOrNull() ?: return
		val cookieManager = CookieManager.getInstance()
		val cookieString = cookieManager.getCookie(targetUrl) ?: return
		if (cookieString == lastSyncedCookies) return
		lastSyncedCookies = cookieString
		val cookies = cookieString.split(";").mapNotNull { raw ->
			AndroidCookieJar.parseWebViewCookie(httpUrl, raw)
		}
		if (cookies.isNotEmpty()) {
			cookieJar.saveFromResponse(httpUrl, cookies)
		}
		AndroidCookieJar.safeFlush(cookieManager)
	}

	private fun getClearance() = CloudFlareHelper.getClearanceCookie(cookieJar, targetUrl)

	private companion object {
		/** Turnstile can set the cookie at any moment; this is how often we look. */
		private const val COOKIE_CHECK_INTERVAL = 300L
	}
}
