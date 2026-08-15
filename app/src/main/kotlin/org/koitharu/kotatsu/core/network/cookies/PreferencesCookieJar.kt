package org.koitharu.kotatsu.core.network.cookies

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.WorkerThread
import androidx.collection.ArrayMap
import androidx.core.content.edit
import androidx.core.util.Predicate
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug

private const val PREFS_NAME = "cookies"

class PreferencesCookieJar(
	context: Context,
) : MutableCookieJar {

	private val cache = ArrayMap<String, CookieWrapper>()
	private val prefs = createSecurePreferences(context)
	private var isLoaded = false

	init {
		migrateLegacyPreferences(context)
	}

	@WorkerThread
	@Synchronized
	override fun loadForRequest(url: HttpUrl): List<Cookie> {
		loadPersistent()
		val expired = HashSet<String>()
		val result = ArrayList<Cookie>()
		for ((key, cookie) in cache) {
			if (cookie.isExpired()) {
				expired += key
			} else if (cookie.cookie.matches(url)) {
				result += cookie.cookie
			}
		}
		if (expired.isNotEmpty()) {
			cache.removeAll(expired)
			removePersistent(expired)
		}
		return result
	}

	@WorkerThread
	@Synchronized
	override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
		if (cookies.isEmpty()) {
			return
		}
		loadPersistent()
		val wrapped = cookies.map { CookieWrapper(it) }
		// A cookie is keyed by name + domain + path + scheme, so the *same* logical cookie can be
		// stored twice when it arrives once from the network (`Path=/; Secure`) and once from the
		// WebView (attributes hidden). Both copies then match the request and both are sent, which is
		// fatal for `cf_clearance`: Cloudflare reads the stale one and re-challenges. Superseding
		// every other entry with the same name and domain keeps exactly one, and heals stores that
		// were already polluted by earlier versions.
		val superseded = HashSet<String>()
		for (cookie in wrapped) {
			val key = cookie.key()
			for ((existingKey, existing) in cache) {
				if (existingKey != key &&
					existing.cookie.name == cookie.cookie.name &&
					existing.cookie.domain == cookie.cookie.domain
				) {
					superseded += existingKey
				}
			}
		}
		prefs.edit(commit = true) {
			for (key in superseded) {
				cache.remove(key)
				remove(key)
			}
			for (cookie in wrapped) {
				val key = cookie.key()
				cache[key] = cookie
				putString(key, cookie.encode())
			}
		}
	}

	@Synchronized
	@WorkerThread
	override fun removeCookies(url: HttpUrl, predicate: Predicate<Cookie>?) {
		loadPersistent()
		val toRemove = HashSet<String>()
		for ((key, cookie) in cache) {
			if (cookie.isExpired() || cookie.cookie.matches(url)) {
				if (predicate == null || predicate.test(cookie.cookie)) {
					toRemove += key
				}
			}
		}
		if (toRemove.isNotEmpty()) {
			cache.removeAll(toRemove)
			removePersistent(toRemove)
		}
	}

	override suspend fun clear(): Boolean {
		cache.clear()
		withContext(Dispatchers.IO) {
			prefs.edit(commit = true) { clear() }
		}
		return true
	}

	@Synchronized
	private fun loadPersistent() {
		if (!isLoaded) {
			val map = prefs.all
			cache.ensureCapacity(map.size)
			for ((k, v) in map) {
				val cookie = try {
					CookieWrapper(v as String)
				} catch (e: Exception) {
					e.printStackTraceDebug()
					continue
				}
				cache[k] = cookie
			}
			isLoaded = true
		}
	}

	private fun removePersistent(keys: Collection<String>) {
		prefs.edit(commit = true) {
			for (key in keys) {
				remove(key)
			}
		}
	}

	companion object {

		private fun createSecurePreferences(context: Context): SharedPreferences = try {
			val masterKey = MasterKey.Builder(context)
				.setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
				.build()
			EncryptedSharedPreferences.create(
				context,
				PREFS_NAME,
				masterKey,
				EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
				EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
			)
		} catch (e: Exception) {
			e.printStackTraceDebug()
			context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
		}

		private fun migrateLegacyPreferences(context: Context) {
			val legacy = context.getSharedPreferences("${PREFS_NAME}_legacy", Context.MODE_PRIVATE)
			val plain = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
			val source = when {
				plain.all.isNotEmpty() -> plain
				else -> return
			}
			try {
				val secure = createSecurePreferences(context)
				if (secure === source) {
					return
				}
				secure.edit(commit = true) {
					for ((key, value) in source.all) {
						if (value is String && !secure.contains(key)) {
							putString(key, value)
						}
					}
				}
				legacy.edit(commit = true) {
					for ((key, value) in source.all) {
						if (value is String) {
							putString(key, value)
						}
					}
				}
				source.edit(commit = true) { clear() }
			} catch (e: Exception) {
				e.printStackTraceDebug()
			}
		}
	}
}
