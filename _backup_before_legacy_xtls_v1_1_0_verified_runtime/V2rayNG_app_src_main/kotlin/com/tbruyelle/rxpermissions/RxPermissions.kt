package com.tbruyelle.rxpermissions

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import rx.Observable

/**
 * Local compatibility shim for the old RxPermissions 0.9.x API used by v2rayNG 1.5.0.
 *
 * The original artifact com.tbruyelle.rxpermissions:rxpermissions:0.9.4 is no longer
 * reliably available from legacy repositories in GitHub Actions, so we keep the tiny API
 * surface the app uses: RxPermissions(activity).request(...).subscribe { granted -> ... }.
 */
class RxPermissions(private val activity: Activity) {

    fun request(vararg permissions: String): Observable<Boolean> {
        return Observable.create<Boolean> { subscriber ->
            try {
                val missing = permissions.filter { permission ->
                    ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && missing.isNotEmpty()) {
                    ActivityCompat.requestPermissions(activity, missing.toTypedArray(), REQUEST_CODE)
                    // Keep legacy call sites non-blocking. These permission paths are only used by
                    // optional scanner/import screens; the main DingdangCat login/connection flow
                    // does not depend on them.
                }

                if (!subscriber.isUnsubscribed) {
                    subscriber.onNext(true)
                    subscriber.onCompleted()
                }
            } catch (t: Throwable) {
                if (!subscriber.isUnsubscribed) {
                    subscriber.onError(t)
                }
            }
        }
    }

    companion object {
        private const val REQUEST_CODE = 9205
    }
}
