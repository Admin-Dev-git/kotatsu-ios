package org.koitharu.kotatsu.core.network.cookies

import androidx.annotation.WorkerThread
import androidx.core.util.Predicate
import okhttp3.Cookie
import okhttp3.HttpUrl

/**
 * Keeps [AndroidCookieJar] as the primary store (shared with WebView after CAPTCHA)
 * and mirrors cookies to encrypted preferences so sessions survive restarts reliably.
 */
class PersistentCookieJar(
	private val primary: MutableCookieJar,
	private val backup: MutableCookieJar,
) : MutableCookieJar {

	@WorkerThread
	override fun loadForRequest(url: HttpUrl): List<Cookie> {
		val fromPrimary = primary.loadForRequest(url)
		val fromBackup = backup.loadForRequest(url)
		if (fromBackup.isEmpty()) {
			return fromPrimary.distinctByName()
		}
		if (fromPrimary.isEmpty()) {
			val restored = fromBackup.distinctByName()
			primary.saveFromResponse(url, restored)
			return restored
		}
		// One cookie per name, and the live store wins. Merging by name + domain + path instead let a
		// stale `cf_clearance` travel alongside the fresh one in a single Cookie header: Cloudflare
		// reads the first value, rejects it and issues another challenge — which is what made the
		// captcha come back no matter how many times it was solved.
		val merged = LinkedHashMap<String, Cookie>(fromPrimary.size + fromBackup.size)
		for (cookie in fromPrimary.distinctByName()) {
			merged[cookie.name] = cookie
		}
		val missingInPrimary = ArrayList<Cookie>()
		for (cookie in fromBackup) {
			// Only names the live store does not know about are restored from the backup. Writing back
			// a name it already holds would overwrite a fresh value with a superseded one.
			if (!merged.containsKey(cookie.name)) {
				merged[cookie.name] = cookie
				missingInPrimary += cookie
			}
		}
		if (missingInPrimary.isNotEmpty()) {
			primary.saveFromResponse(url, missingInPrimary)
		}
		return merged.values.toList()
	}

	@WorkerThread
	override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
		if (cookies.isEmpty()) {
			return
		}
		primary.saveFromResponse(url, cookies)
		backup.saveFromResponse(url, cookies)
	}

	@WorkerThread
	override fun removeCookies(url: HttpUrl, predicate: Predicate<Cookie>?) {
		primary.removeCookies(url, predicate)
		backup.removeCookies(url, predicate)
	}

	override suspend fun clear(): Boolean {
		backup.clear()
		return primary.clear()
	}

	/**
	 * Keeps one cookie per name, preferring a non-blank value. A single store should never hand out
	 * two values for one name, but a store polluted by an earlier version of the app still can, and a
	 * duplicated name on the wire invalidates the cookie for Cloudflare. An emptied cookie is the
	 * residue of a purge, so it must never win over a real value.
	 */
	private fun List<Cookie>.distinctByName(): List<Cookie> {
		if (size < 2) return this
		val byName = LinkedHashMap<String, Cookie>(size)
		for (cookie in this) {
			val existing = byName[cookie.name]
			if (existing == null || (existing.value.isBlank() && cookie.value.isNotBlank())) {
				byName[cookie.name] = cookie
			}
		}
		return byName.values.toList()
	}
}
