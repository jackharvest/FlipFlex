package com.github.jackharvest.flipflex.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast

/**
 * Where an install session reports back to, and the thing that raises the
 * system's "update this app?" screen.
 *
 * ## Why a receiver rather than a callback in Settings
 *
 * The successful outcome of this is *this process being killed and replaced*.
 * There is no activity left to deliver a result to, and there is no point
 * pretending otherwise -- so the status goes to a manifest-declared receiver,
 * which the platform can start whether or not the app is running.
 *
 * ## Why a Toast, which nothing else in this app uses
 *
 * Same reason. Every other message in FlipFlex is drawn by the screen it
 * belongs to, because a Toast on a 240x320 panel covers two rows of a list. But
 * a failed commit can arrive while the app is mid-teardown or in the background
 * behind the installer, and a message that needs a live activity would simply
 * not be shown. This is the one case where nothing else can be relied on.
 *
 * Success is deliberately silent: the app has just been replaced, and the
 * evidence is that it restarts as the new version.
 */
class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The one screen that cannot be skipped without being a system
                // app. NEW_TASK because a receiver has no task of its own to
                // start an activity in.
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    Log.w(TAG, "pending user action with no intent to show")
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirm)
            }

            PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "installed")

            // ABORTED is the user pressing Cancel on that screen, which is an
            // answer rather than a fault and needs no complaint about it.
            PackageInstaller.STATUS_FAILURE_ABORTED -> Log.i(TAG, "cancelled")

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "install failed: status $status, $message")
                Toast.makeText(
                    context,
                    "Update failed.\n${message ?: "status $status"}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private companion object {
        const val TAG = "FlipFlex/install"
    }
}
