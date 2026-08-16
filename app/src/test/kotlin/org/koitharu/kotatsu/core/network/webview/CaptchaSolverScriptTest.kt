package org.koitharu.kotatsu.core.network.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The captcha solver's JavaScript is a set of string constants, so a broken script compiles fine and
 * fails silently at runtime: `evaluateJavascript` swallows the SyntaxError, the WebView keeps its real
 * fingerprint, no widget is ever clicked, and the only visible symptom is the captcha coming back.
 * Two such defects shipped — [CaptchaSolverScript.SOLVE_SCRIPT] once lost its `try {`, and the stealth
 * script embedded a `JSON.stringify(ua)` template Kotlin never interpolated. These tests are the cheap
 * structural check that would have caught both without a JS engine on the test classpath.
 */
class CaptchaSolverScriptTest {

	private val androidUserAgent =
		"Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
			"Chrome/133.0.0.0 Mobile Safari/537.36"

	private val scripts: Map<String, String>
		get() = mapOf(
			"STEALTH_SCRIPT" to CaptchaSolverScript.stealthScript(androidUserAgent),
			"SOLVE_SCRIPT" to CaptchaSolverScript.SOLVE_SCRIPT,
			"CONTINUOUS_SOLVE_SCRIPT" to CaptchaSolverScript.CONTINUOUS_SOLVE_SCRIPT,
			"DETECT_CHALLENGE_SCRIPT" to CaptchaSolverScript.DETECT_CHALLENGE_SCRIPT,
			"GET_WIDGET_COORDINATES_SCRIPT" to CaptchaSolverScript.GET_WIDGET_COORDINATES_SCRIPT,
		)

	@Test
	fun `every script is a closed immediately-invoked function`() {
		for ((name, script) in scripts) {
			assertTrue("$name must start with an IIFE", script.trimStart().startsWith("(function()"))
			assertTrue("$name must be invoked and terminated", script.trimEnd().endsWith("})();"))
		}
	}

	@Test
	fun `every script has balanced brackets outside strings and comments`() {
		for ((name, script) in scripts) {
			assertEquals("$name has unbalanced {}", 0, script.depthOf('{', '}'))
			assertEquals("$name has unbalanced ()", 0, script.depthOf('(', ')'))
			assertEquals("$name has unbalanced []", 0, script.depthOf('[', ']'))
		}
	}

	@Test
	fun `no script leaks an uninterpolated kotlin template`() {
		for ((name, script) in scripts) {
			assertFalse("$name contains an unresolved \${...} template", script.contains("\${"))
		}
	}

	@Test
	fun `stealth script embeds the user agent as a quoted javascript literal`() {
		val script = CaptchaSolverScript.stealthScript(androidUserAgent)
		assertTrue(
			"the UA must reach the script as a string literal, not as bare source",
			script.contains("var reportedUA = \"$androidUserAgent\";"),
		)
	}

	@Test
	fun `stealth script escapes quotes and backslashes in the user agent`() {
		val hostile = """Chrome/1.0 "quoted" \ back\slash"""
		val script = CaptchaSolverScript.stealthScript(hostile)
		assertTrue(
			"a quote in the UA must not terminate the literal",
			script.contains("""var reportedUA = "Chrome/1.0 \"quoted\" \\ back\\slash";"""),
		)
		assertEquals("escaping must keep the script balanced", 0, script.depthOf('{', '}'))
	}

	/**
	 * Net bracket depth, skipping string literals, template literals, regex literals and comments.
	 * A non-zero result means the script cannot parse.
	 */
	private fun String.depthOf(open: Char, close: Char): Int {
		var depth = 0
		var i = 0
		// Tracks whether a '/' starts a regex literal or is a division operator.
		var lastMeaningful = ' '
		while (i < length) {
			val c = this[i]
			when {
				c == '/' && i + 1 < length && this[i + 1] == '/' -> {
					i = indexOf('\n', i).takeIf { it >= 0 } ?: length
					continue
				}

				c == '/' && i + 1 < length && this[i + 1] == '*' -> {
					i = indexOf("*/", i).takeIf { it >= 0 }?.plus(2) ?: length
					continue
				}

				c == '\'' || c == '"' || c == '`' -> {
					i = skipQuoted(i, c)
					lastMeaningful = 'x'
					continue
				}

				c == '/' && lastMeaningful in REGEX_PRECEDERS -> {
					i = skipRegex(i)
					lastMeaningful = 'x'
					continue
				}

				c == open -> depth++
				c == close -> depth--
			}
			if (!c.isWhitespace()) {
				lastMeaningful = c
			}
			i++
		}
		return depth
	}

	/** Index just past the closing quote of the literal starting at [start]. */
	private fun String.skipQuoted(start: Int, quote: Char): Int {
		var i = start + 1
		while (i < length) {
			when (this[i]) {
				'\\' -> i++
				quote -> return i + 1
			}
			i++
		}
		return length
	}

	/** Index just past the closing `/` (and flags) of the regex literal starting at [start]. */
	private fun String.skipRegex(start: Int): Int {
		var i = start + 1
		var inClass = false
		while (i < length) {
			when (this[i]) {
				'\\' -> i++
				'[' -> inClass = true
				']' -> inClass = false
				'/' -> if (!inClass) {
					i++
					while (i < length && this[i].isLetter()) i++
					return i
				}

				'\n' -> return i
			}
			i++
		}
		return length
	}

	private companion object {
		/** Characters after which a `/` can only begin a regex literal, never a division. */
		private val REGEX_PRECEDERS = charArrayOf('(', ',', '=', ':', '[', '!', '&', '|', '?', '{', '}', ';', ' ')
	}
}
