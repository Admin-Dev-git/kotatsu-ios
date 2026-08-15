package org.koitharu.kotatsu.core.network.tls

import org.koitharu.kotatsu.parsers.network.UserAgents

internal object ChromeTlsIdentity {
	/**
	 * Android Chrome, matching the `sec-ch-ua-mobile: ?1` / `sec-ch-ua-platform: "Android"` client
	 * hints sent by `BrowserHeadersInterceptor` and the real WebView metrics. `cf_clearance` is
	 * issued bound to the UA and re-validated against the hints, so a desktop UA here made
	 * Cloudflare reject the clearance it had just issued.
	 */
	const val USER_AGENT = UserAgents.CHROME_MOBILE
}
