package org.koitharu.kotatsu.core.network.tls

import org.koitharu.kotatsu.parsers.network.UserAgents

/**
 * Chrome browser identity that must stay consistent with [TlsClientManager.TLS_PROFILE].
 * Cloudflare correlates TLS fingerprint, User-Agent, and Client Hints.
 */
object ChromeTlsIdentity {

	const val PROFILE = "chrome_133_PSK"
	const val MAJOR = "133"

	/**
	 * Android Chrome, not desktop Chrome. The challenge is solved inside an Android [android.webkit.WebView],
	 * and Turnstile fingerprints far more than the UA string: touch support, `navigator.platform`,
	 * `screen`, the WebGL renderer (an Adreno/Mali string), pointer/hover media queries. A WebView
	 * claiming `Windows NT 10.0` contradicts every one of those, which is scored as automation — so the
	 * challenge is either never cleared or the clearance is rejected on first reuse. Claiming what the
	 * device actually is has nothing to contradict.
	 */
	const val USER_AGENT = UserAgents.CHROME_MOBILE

	const val SEC_CH_UA =
		"\"Not(A:Brand\";v=\"99\", \"Google Chrome\";v=\"$MAJOR\", \"Chromium\";v=\"$MAJOR\""

	const val SEC_CH_UA_MOBILE = "?1"
	const val SEC_CH_UA_PLATFORM = "\"Android\""

	const val ACCEPT_HTML =
		"text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"

	const val ACCEPT_IMAGE =
		"image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"

	const val ACCEPT_LANGUAGE = "en-US,en;q=0.9"
	const val ACCEPT_ENCODING = "gzip, deflate, br, zstd"

	/**
	 * Chrome's typical HTTP header emission order (excluding hop-by-hop headers).
	 * Pseudo-header order is handled by the tls-client profile.
	 */
	val HEADER_ORDER = listOf(
		"sec-ch-ua",
		"sec-ch-ua-mobile",
		"sec-ch-ua-platform",
		"upgrade-insecure-requests",
		"user-agent",
		"accept",
		"sec-fetch-site",
		"sec-fetch-mode",
		"sec-fetch-user",
		"sec-fetch-dest",
		"referer",
		"accept-encoding",
		"accept-language",
		"cookie",
		"content-type",
		"content-length",
		"origin",
	)

	fun buildHeaders(
		existing: Map<String, String>,
		isImage: Boolean,
		hasBody: Boolean,
	): LinkedHashMap<String, String> {
		val out = LinkedHashMap<String, String>()
		fun put(name: String, value: String) {
			out[name] = value
		}

		put("sec-ch-ua", SEC_CH_UA)
		put("sec-ch-ua-mobile", SEC_CH_UA_MOBILE)
		put("sec-ch-ua-platform", SEC_CH_UA_PLATFORM)
		if (!isImage) {
			put("upgrade-insecure-requests", "1")
		}
		put("user-agent", USER_AGENT)

		val accept = existing.entries.firstOrNull { it.key.equals("Accept", true) }?.value
		put("accept", accept ?: if (isImage) ACCEPT_IMAGE else ACCEPT_HTML)

		val referer = existing.entries.firstOrNull { it.key.equals("Referer", true) }?.value
		val hasReferer = !referer.isNullOrEmpty()
		put("sec-fetch-site", if (hasReferer) "same-origin" else "none")
		put("sec-fetch-mode", if (isImage) "no-cors" else "navigate")
		if (!isImage) {
			put("sec-fetch-user", "?1")
		}
		put("sec-fetch-dest", if (isImage) "image" else "document")

		if (hasReferer) {
			put("referer", referer!!)
		}
		put("accept-encoding", ACCEPT_ENCODING)
		put(
			"accept-language",
			existing.entries.firstOrNull { it.key.equals("Accept-Language", true) }?.value
				?: ACCEPT_LANGUAGE,
		)

		val contentType = existing.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
		if (hasBody && !contentType.isNullOrEmpty()) {
			put("content-type", contentType)
		}
		val origin = existing.entries.firstOrNull { it.key.equals("Origin", true) }?.value
		if (!origin.isNullOrEmpty()) {
			put("origin", origin)
		}

		// Preserve auth / custom source headers that Chrome would still send.
		for ((name, value) in existing) {
			val lower = name.lowercase()
			if (lower in SKIP_EXISTING) continue
			if (out.keys.any { it.equals(name, ignoreCase = true) }) continue
			out[name] = value
		}
		return out
	}

	private val SKIP_EXISTING = setOf(
		"accept",
		"accept-encoding",
		"accept-language",
		"user-agent",
		"cookie",
		"content-encoding",
		"connection",
		"host",
		"upgrade-insecure-requests",
		"sec-ch-ua",
		"sec-ch-ua-mobile",
		"sec-ch-ua-platform",
		"sec-fetch-site",
		"sec-fetch-mode",
		"sec-fetch-user",
		"sec-fetch-dest",
		"referer",
		"content-type",
		"origin",
		"x-manga-source",
	)
}
