package org.koitharu.kotatsu.core.network.tls

import android.util.Log
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.prefs.AppSettings
import java.net.Proxy
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

/**
 * Owns the shared bogdanfinn/tls-client session used for all manga source traffic.
 * Browser TLS fingerprints (JA3 / HTTP/2) are applied here so every parser benefits.
 */
@Singleton
class TlsClientManager @Inject constructor(
	private val settings: AppSettings,
) {

	private val lock = ReentrantLock()

	@Volatile
	private var session: NativeTlsSession? = null

	@Volatile
	var isAvailable: Boolean = false
		private set

	fun ensureReady(): Boolean = lock.withLock {
		if (isAvailable && session != null) {
			return true
		}
		return try {
			val lib = GoTlsClientLibrary.load()
			session = NativeTlsSession(
				lib = lib,
				tlsClientIdentifier = TLS_PROFILE,
				insecureSkipVerify = settings.isSSLBypassEnabled,
			)
			isAvailable = true
			if (BuildConfig.DEBUG) {
				Log.i(TAG, "tls-client ready with profile=$TLS_PROFILE")
			}
			true
		} catch (e: Throwable) {
			isAvailable = false
			session = null
			Log.e(TAG, "Failed to initialize tls-client; falling back to OkHttp", e)
			false
		}
	}

	internal fun <T> withSession(block: (NativeTlsSession) -> T): T = lock.withLock {
		val active = session ?: error("tls-client session is not ready")
		block(active)
	}

	fun currentProxyUrl(): String? {
		val type = settings.proxyType
		if (type == Proxy.Type.DIRECT) {
			return null
		}
		val address = settings.proxyAddress?.takeIf { it.isNotEmpty() } ?: return null
		val port = settings.proxyPort
		if (port !in 0..0xFFFF) {
			return null
		}
		val scheme = when (type) {
			Proxy.Type.HTTP -> "http"
			Proxy.Type.SOCKS -> "socks5"
			else -> return null
		}
		val login = settings.proxyLogin
		val password = settings.proxyPassword
		val auth = if (!login.isNullOrEmpty() && password != null) {
			"${encode(login)}:${encode(password)}@"
		} else {
			""
		}
		return "$scheme://$auth$address:$port"
	}

	private fun encode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

	companion object {
		private const val TAG = "TlsClientManager"
		const val TLS_PROFILE: String = ChromeTlsIdentity.PROFILE
	}
}
