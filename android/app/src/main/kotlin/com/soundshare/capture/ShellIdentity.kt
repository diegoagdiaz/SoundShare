package com.soundshare.capture

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Application
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Process

/**
 * Un proceso lanzado con app_process no tiene "aplicación", así que Android no sabe a qué paquete
 * atribuirle la grabación y la rechaza. Registramos una Application mínima que se presenta como
 * el paquete del shell (com.android.shell, uid 2000), que es quien realmente somos.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
object ShellIdentity {
    const val PACKAGE = "com.android.shell"

    private open class ShellContext : ContextWrapper(null) {
        override fun getPackageName(): String = PACKAGE
        override fun getOpPackageName(): String = PACKAGE
        override fun getAttributionTag(): String? = null
        override fun getApplicationContext(): Context = this

        // Android 14+ consulta el dispositivo virtual al crear AudioRecord.
        // Este capturador pertenece al teléfono real (id 0), no a un dispositivo virtual.
        // No delegamos en ContextWrapper: un proceso de shell no tiene contexto base.
        @TargetApi(34)
        override fun getDeviceId(): Int = 0
    }

    // Android 12 pide atribución al crear AudioRecord; la base nula no puede proporcionarla.
    @TargetApi(31)
    private class ShellContext31 : ShellContext() {
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.myUid()).setPackageName(PACKAGE).build()
    }

    fun install() {
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        activityThread.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)

        val app = Application()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }
            .invoke(app, if (Build.VERSION.SDK_INT >= 31) ShellContext31() else ShellContext())
        activityThread.getDeclaredField("mInitialApplication").apply { isAccessible = true }.set(thread, app)
    }
}
