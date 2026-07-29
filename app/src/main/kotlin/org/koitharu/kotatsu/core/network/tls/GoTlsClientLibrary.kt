package org.koitharu.kotatsu.core.network.tls

import com.sun.jna.Library
import com.sun.jna.Native

/**
 * JNA bindings for bogdanfinn/tls-client CFFI (`libtls_client_go`).
 * Payloads and responses are JSON strings.
 */
internal interface GoTlsClientLibrary : Library {
	fun request(payload: String): String
	fun destroySession(payload: String): String
	fun getCookiesFromSession(payload: String): String
	fun destroyAll(): String

	companion object {
		private val utf8Options = mapOf(Library.OPTION_STRING_ENCODING to "UTF-8")

		fun load(): GoTlsClientLibrary =
			Native.load("tls_client_go", GoTlsClientLibrary::class.java, utf8Options) as GoTlsClientLibrary
	}
}
