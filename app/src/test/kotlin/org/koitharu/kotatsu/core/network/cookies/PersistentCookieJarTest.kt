package org.koitharu.kotatsu.core.network.cookies

import androidx.core.util.Predicate
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the cookie duplication that kept the Cloudflare captcha coming back: the same
 * `cf_clearance` stored under two identities (network copy `Path=/; Secure`, WebView copy with the
 * attributes hidden) was merged by name + domain + path, so both values were sent in one request.
 */
class PersistentCookieJarTest {

	private val url = "https://example.org/manga/title/chapter-1".toHttpUrl()

	@Test
	fun `never sends two cookies with the same name`() {
		val jar = PersistentCookieJar(
			primary = FakeCookieJar(clearance(value = "fresh", path = "/")),
			backup = FakeCookieJar(
				clearance(value = "stale", path = "/manga/title", secure = false),
				clearance(value = "older", path = "/", secure = false),
			),
		)
		val loaded = jar.loadForRequest(url)
		assertEquals(1, loaded.count { it.name == "cf_clearance" })
	}

	@Test
	fun `live store wins over the backup`() {
		val jar = PersistentCookieJar(
			primary = FakeCookieJar(clearance(value = "fresh", path = "/")),
			backup = FakeCookieJar(clearance(value = "stale", path = "/manga/title", secure = false)),
		)
		assertEquals("fresh", jar.loadForRequest(url).single { it.name == "cf_clearance" }.value)
	}

	@Test
	fun `a stale backup value is not written back over a fresh one`() {
		val primary = FakeCookieJar(clearance(value = "fresh", path = "/"))
		val jar = PersistentCookieJar(
			primary = primary,
			backup = FakeCookieJar(clearance(value = "stale", path = "/", secure = false)),
		)
		jar.loadForRequest(url)
		assertEquals(listOf("fresh"), primary.cookies.filter { it.name == "cf_clearance" }.map { it.value })
	}

	@Test
	fun `names missing from the live store are restored from the backup`() {
		val primary = FakeCookieJar(clearance(value = "fresh", path = "/"))
		val session = Cookie.Builder()
			.name("session")
			.value("abc")
			.domain("example.org")
			.path("/")
			.build()
		val jar = PersistentCookieJar(primary = primary, backup = FakeCookieJar(session))
		val loaded = jar.loadForRequest(url)
		assertEquals(setOf("cf_clearance", "session"), loaded.map { it.name }.toSet())
		assertTrue(primary.cookies.any { it.name == "session" })
	}

	@Test
	fun `an emptied cookie never wins over a real value`() {
		val jar = PersistentCookieJar(
			primary = FakeCookieJar(
				clearance(value = "", path = "/"),
				clearance(value = "real", path = "/", secure = false),
			),
			backup = FakeCookieJar(clearance(value = "stale", path = "/", secure = false)),
		)
		assertEquals("real", jar.loadForRequest(url).single { it.name == "cf_clearance" }.value)
	}

	@Test
	fun `a webview cookie is scoped to the whole host, not the current directory`() {
		val cookie = AndroidCookieJar.parseWebViewCookie(url, " cf_clearance=abc ")
		assertNotNull(cookie)
		assertEquals("/", cookie!!.path)
		assertEquals("example.org", cookie.domain)
		assertTrue(cookie.secure)
		assertTrue(cookie.matches("https://example.org/".toHttpUrl()))
		assertTrue(cookie.matches("https://cdn.example.org/api/list".toHttpUrl()))
	}

	private fun clearance(value: String, path: String, secure: Boolean = true): Cookie =
		Cookie.Builder()
			.name("cf_clearance")
			.value(value)
			.domain("example.org")
			.path(path)
			.also { if (secure) it.secure() }
			.build()

	private class FakeCookieJar(vararg initial: Cookie) : MutableCookieJar {

		val cookies = initial.toMutableList()

		override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) }

		override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
			for (cookie in cookies) {
				this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
				this.cookies += cookie
			}
		}

		override fun removeCookies(url: HttpUrl, predicate: Predicate<Cookie>?) {
			cookies.removeAll { it.matches(url) && (predicate == null || predicate.test(it)) }
		}

		override suspend fun clear(): Boolean {
			cookies.clear()
			return true
		}
	}
}
