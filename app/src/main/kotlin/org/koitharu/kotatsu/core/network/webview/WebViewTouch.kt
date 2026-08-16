package org.koitharu.kotatsu.core.network.webview

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView
import androidx.annotation.MainThread
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug

/**
 * Ask the page where its challenge widget sits and tap it with a synthesised finger.
 *
 * Turnstile ignores a scripted `element.click()`: inside its cross-origin iframe the call carries no
 * user activation and never reaches the widget's own document. A [MotionEvent] pair dispatched into
 * the WebView does, so this is the strategy that actually ticks the checkbox.
 *
 * The WebView must have a non-zero size ([org.koitharu.kotatsu.core.util.ext.layoutOffscreen]),
 * otherwise [CaptchaSolverScript.GET_WIDGET_COORDINATES_SCRIPT] measures an empty rect and reports
 * nothing to aim at.
 *
 * @param isObsolete consulted on the JS callback; return `true` to drop a tap that is no longer wanted
 *                   (challenge already solved, screen closed).
 */
@MainThread
fun WebView.tapChallengeWidget(isObsolete: () -> Boolean = { false }) {
	runCatching {
		evaluateJavascript(CaptchaSolverScript.GET_WIDGET_COORDINATES_SCRIPT) { result ->
			if (isObsolete()) return@evaluateJavascript
			// The result arrives as a JSON string: "\"123.4,567.8\"".
			val coords = result?.trim('"')?.replace("\\", "")?.split(',') ?: return@evaluateJavascript
			if (coords.size != 2) return@evaluateJavascript
			val x = coords[0].toFloatOrNull() ?: return@evaluateJavascript
			val y = coords[1].toFloatOrNull() ?: return@evaluateJavascript
			dispatchWidgetTap(x, y)
		}
	}.onFailure { it.printStackTraceDebug() }
}

/**
 * Dispatch a finger-like down/up pair at ([xCss], [yCss]), in CSS pixels as the page reports them.
 * Pressure and touch size are set because a pointer event with none of them looks synthetic.
 */
@MainThread
fun WebView.dispatchWidgetTap(xCss: Float, yCss: Float) {
	val density = resources.displayMetrics.density
	val properties = arrayOf(
		MotionEvent.PointerProperties().apply {
			id = 0
			toolType = MotionEvent.TOOL_TYPE_FINGER
		},
	)
	val coords = arrayOf(
		MotionEvent.PointerCoords().apply {
			x = xCss * density
			y = yCss * density
			pressure = TAP_PRESSURE
			size = TAP_SIZE
			touchMajor = TAP_TOUCH_AXIS
			touchMinor = TAP_TOUCH_AXIS
		},
	)
	val downTime = SystemClock.uptimeMillis()
	val down = MotionEvent.obtain(
		downTime, downTime, MotionEvent.ACTION_DOWN,
		1, properties, coords, 0, 0, 1.0f, 1.0f, 0, 0,
		InputDevice.SOURCE_TOUCHSCREEN, 0,
	)
	val up = MotionEvent.obtain(
		downTime, downTime + TAP_DURATION_MS, MotionEvent.ACTION_UP,
		1, properties, coords, 0, 0, 1.0f, 1.0f, 0, 0,
		InputDevice.SOURCE_TOUCHSCREEN, 0,
	)
	try {
		dispatchTouchEvent(down)
		dispatchTouchEvent(up)
	} catch (e: Exception) {
		e.printStackTraceDebug()
	} finally {
		down.recycle()
		up.recycle()
	}
}

/** A hair under a tenth of a second — long enough to read as a tap, short enough not to be a hold. */
private const val TAP_DURATION_MS = 120L
private const val TAP_PRESSURE = 0.8f
private const val TAP_SIZE = 0.2f
private const val TAP_TOUCH_AXIS = 24.0f
