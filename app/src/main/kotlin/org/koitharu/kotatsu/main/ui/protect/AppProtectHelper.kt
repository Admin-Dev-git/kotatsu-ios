package org.koitharu.kotatsu.main.ui.protect

import android.app.Activity
import android.content.Intent
import org.acra.dialog.CrashReportDialog
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.DefaultActivityLifecycleCallbacks
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppProtectHelper @Inject constructor(private val settings: AppSettings) :
	DefaultActivityLifecycleCallbacks {

	private var isUnlocked = settings.appPassword.isNullOrEmpty()
	private var startedActivityCount = 0

	override fun onActivityStarted(activity: Activity) {
		if (activity is ProtectActivity || activity is CrashReportDialog) return
		if (startedActivityCount == 0 && !isUnlocked) {
			val sourceIntent = Intent(activity, activity.javaClass)
			activity.intent?.let {
				sourceIntent.putExtras(it)
				sourceIntent.action = it.action
				sourceIntent.setDataAndType(it.data, it.type)
			}
			activity.startActivity(ProtectActivity.newIntent(activity, sourceIntent))
			activity.finishAfterTransition()
			return
		}
		startedActivityCount++
	}

	override fun onActivityStopped(activity: Activity) {
		if (activity is ProtectActivity || activity is CrashReportDialog) return
		startedActivityCount--
		if (startedActivityCount == 0) {
			isUnlocked = settings.appPassword.isNullOrEmpty()
		}
	}

	override fun onActivityDestroyed(activity: Activity) {
		if (activity !is ProtectActivity && activity.isFinishing && activity.isTaskRoot) {
			isUnlocked = settings.appPassword.isNullOrEmpty()
		}
	}

	fun unlock() {
		isUnlocked = true
	}
}
