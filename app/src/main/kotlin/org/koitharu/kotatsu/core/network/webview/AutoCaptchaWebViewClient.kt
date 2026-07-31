package org.koitharu.kotatsu.core.network.webview

import android.graphics.Bitmap
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellableContinuation
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/**
 * A [WebViewClient] that automatically solves CloudFlare JS challenges.
 *
 * On each page load it:
 * 1. Checks if the `cf_clearance` cookie has changed (challenge solved)
 * 2. If not solved, injects [CaptchaSolverScript.SOLVE_SCRIPT] to auto-click challenge elements
 * 3. Resumes the continuation when the challenge is solved
 */
internal class AutoCaptchaWebViewClient(
	private val cookieJar: MutableCookieJar,
	private val targetUrl: String,
	private val continuation: Continuation<Unit>,
) : WebViewClient() {

	private val oldClearance = CloudFlareHelper.getClearanceCookie(cookieJar, targetUrl)

	@Volatile
	private var scriptInjectCount = 0

	override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
		super.onPageStarted(view, url, favicon)
		checkClearance(view)
	}

	override fun onPageFinished(view: WebView?, url: String?) {
		super.onPageFinished(view, url)
		if (isResumed) return

		// Check clearance first — the page might have already solved the challenge
		checkClearance(view)
		if (isResumed) return

		// Inject the auto-solve script
		view?.let { injectSolverScript(it) }
	}

	override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
		super.doUpdateVisitedHistory(view, url, isReload)
		// URL changed — check if we got redirected after solving
		checkClearance(view)
	}

	private fun checkClearance(view: WebView?) {
		if (isResumed) return
		val clearance = CloudFlareHelper.getClearanceCookie(cookieJar, targetUrl)
		if (clearance != null && clearance != oldClearance) {
			resumeOnce(view)
		}
	}

	private fun injectSolverScript(webView: WebView) {
		if (scriptInjectCount >= MAX_SCRIPT_INJECTIONS) return
		scriptInjectCount++

		try {
			// First check if this is actually a challenge page
			webView.evaluateJavascript(CaptchaSolverScript.DETECT_CHALLENGE_SCRIPT) { result ->
				val isChallenge = result?.contains("true") == true
				if (isChallenge) {
					// Inject the solver script
					webView.evaluateJavascript(CaptchaSolverScript.SOLVE_SCRIPT) { solveResult ->
						// After solving attempt, check clearance again
						webView.postDelayed({
							checkClearance(webView)
						}, SOLVE_CHECK_DELAY_MS)
					}
				}
			}
		} catch (e: Exception) {
			e.printStackTraceDebug()
		}
	}

	private val isResumed: Boolean
		get() = continuation is CancellableContinuation && !continuation.isActive

	private fun resumeOnce(view: WebView?) {
		if (continuation is CancellableContinuation) {
			if (continuation.isActive) {
				view?.webViewClient = WebViewClient() // reset to default
				continuation.resume(Unit)
			}
		} else {
			view?.webViewClient = WebViewClient()
			continuation.resume(Unit)
		}
	}

	companion object {
		private const val MAX_SCRIPT_INJECTIONS = 10
		private const val SOLVE_CHECK_DELAY_MS = 2_000L
	}
}
