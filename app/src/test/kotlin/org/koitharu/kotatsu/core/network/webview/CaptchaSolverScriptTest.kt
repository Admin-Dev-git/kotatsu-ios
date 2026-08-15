package org.koitharu.kotatsu.core.network.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural checks on the injected JavaScript.
 *
 * These scripts are plain strings, so a missing `try {` or a missing `})();` compiles fine and then
 * silently fails at runtime inside the WebView — which is exactly how every click strategy in
 * [CaptchaSolverScript.SOLVE_SCRIPT] ended up dead code while the captcha kept re-appearing.
 */
class CaptchaSolverScriptTest {

	private val scripts: Map<String, String> = mapOf(
		"stealthScript" to CaptchaSolverScript.stealthScript("test-agent"),
		"SOLVE_SCRIPT" to CaptchaSolverScript.SOLVE_SCRIPT,
		"CONTINUOUS_SOLVE_SCRIPT" to CaptchaSolverScript.CONTINUOUS_SOLVE_SCRIPT,
		"DETECT_CHALLENGE_SCRIPT" to CaptchaSolverScript.DETECT_CHALLENGE_SCRIPT,
		"GET_WIDGET_COORDINATES_SCRIPT" to CaptchaSolverScript.GET_WIDGET_COORDINATES_SCRIPT,
	)

	@Test
	fun everyScriptIsAClosedIife() {
		for ((name, script) in scripts) {
			val trimmed = script.trim()
			assertTrue("$name must start with an IIFE", trimmed.startsWith("(function()"))
			assertTrue("$name must be a closed and invoked IIFE", trimmed.endsWith("})();"))
		}
	}

	@Test
	fun everyScriptHasBalancedDelimiters() {
		for ((name, script) in scripts) {
			for ((open, close) in PAIRS) {
				assertEquals(
					"$name has unbalanced '$open$close'",
					0,
					script.countUnbalanced(open, close),
				)
			}
		}
	}

	@Test
	fun everyTryHasACatchAndViceVersa() {
		// The shipped defect was a `catch` block with no `try {` opening it, so this has to fail in
		// both directions, not just on a missing handler.
		for ((name, script) in scripts) {
			val code = script.stripCommentsAndStrings()
			val tries = TRY_REGEX.findAll(code).count()
			val handlers = CATCH_REGEX.findAll(code).count() + FINALLY_REGEX.findAll(code).count()
			assertEquals("$name has $tries `try` and $handlers `catch`/`finally`", tries, handlers)
		}
	}

	@Test
	fun stealthScriptDoesNotSpoofDeviceMetrics() {
		// Spoofed screen/platform values contradict the Android UA and client hints we send, and
		// Cloudflare then rejects the cf_clearance it just issued.
		val script = CaptchaSolverScript.stealthScript("test-agent")
		for (forbidden in listOf("'platform'", "window.screen", "hardwareConcurrency", "deviceMemory")) {
			assertTrue(
				"stealthScript must not override $forbidden",
				!script.contains(forbidden),
			)
		}
	}

	/**
	 * Counts unmatched delimiters outside of comments and string literals.
	 * Returns a non-zero value when the script is unbalanced in either direction.
	 */
	private fun String.countUnbalanced(open: Char, close: Char): Int {
		var depth = 0
		var underflow = 0
		for (c in stripCommentsAndStrings()) {
			when (c) {
				open -> depth++
				close -> if (depth == 0) underflow++ else depth--
			}
		}
		return depth + underflow
	}

	private fun String.stripCommentsAndStrings(): String {
		val out = StringBuilder(length)
		var i = 0
		while (i < length) {
			val c = this[i]
			when {
				c == '/' && i + 1 < length && this[i + 1] == '/' -> {
					while (i < length && this[i] != '\n') i++
				}

				c == '/' && i + 1 < length && this[i + 1] == '*' -> {
					i += 2
					while (i + 1 < length && !(this[i] == '*' && this[i + 1] == '/')) i++
					i += 2
				}

				c == '\'' || c == '"' -> {
					val quote = c
					i++
					while (i < length && this[i] != quote) {
						if (this[i] == '\\') i++
						i++
					}
					i++
					out.append("''")
				}

				else -> {
					out.append(c)
					i++
				}
			}
		}
		return out.toString()
	}

	private companion object {
		private val PAIRS = listOf('{' to '}', '(' to ')', '[' to ']')
		private val TRY_REGEX = Regex("""\btry\s*\{""")
		private val CATCH_REGEX = Regex("""\bcatch\s*\(""")
		private val FINALLY_REGEX = Regex("""\bfinally\s*\{""")
	}
}
