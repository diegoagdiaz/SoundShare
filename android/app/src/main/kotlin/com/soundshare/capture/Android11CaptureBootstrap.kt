package com.soundshare.capture

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioRecord
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.view.Display
import android.view.Surface
import java.io.IOException

/** Habilita la captura del shell en Android 11 sin mostrar su actividad en la pantalla del teléfono. */
@TargetApi(30)
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
internal object Android11CaptureBootstrap {
    fun open(createAndStartRecorder: () -> AudioRecord, exec: (Array<String>) -> String): AudioRecord {
        // Sin este soporte Android puede ignorar --display y abrir la actividad en la pantalla física.
        check(exec(arrayOf("pm", "has-feature", "android.software.activities_on_secondary_displays")).trim() == "true") {
            "Android 11 no admite iniciar la captura fuera de pantalla en este dispositivo"
        }
        val drainThread = HandlerThread("ss-capture-bootstrap")
        var reader: ImageReader? = null
        var display: VirtualDisplay? = null
        var recorder: AudioRecord? = null
        var failure: Throwable? = null

        fun recordFailure(error: Throwable) {
            val original = failure
            if (original == null) failure = error else if (original !== error) original.addSuppressed(error)
        }
        fun clean(action: () -> Unit) {
            try {
                action()
            } catch (error: Throwable) {
                recordFailure(error)
            }
        }

        try {
            drainThread.start()
            val handler = Handler(drainThread.looper)
            val images = ImageReader.newInstance(128, 128, PixelFormat.RGBA_8888, 2)
            reader = images
            images.setOnImageAvailableListener({ available ->
                // release()/close() pueden coincidir con el último frame de la actividad.
                runCatching { available.acquireLatestImage()?.close() }
            }, handler)
            val privateDisplay = createDisplay(images.surface, handler)
            display = privateDisplay
            check(privateDisplay.display.displayId > Display.DEFAULT_DISPLAY
                && privateDisplay.display.flags and Display.FLAG_PRIVATE != 0) {
                "Android 11 no proporcionó una pantalla privada para iniciar la captura"
            }

            val output = exec(arrayOf(
                "am", "start", "-W", "--display", privateDisplay.display.displayId.toString(),
                "-f", "0x18000000", "-n", "${ShellIdentity.PACKAGE}/.HeapDumpActivity",
            ))
            // am puede informar un error de lanzamiento aunque termine con código cero.
            check(!output.contains("Error", ignoreCase = true)
                && output.lineSequence().any { it.trim() == "Status: ok" }) {
                "Android 11 no permitió iniciar la captura fuera de pantalla: ${output.trim()}"
            }
            recorder = createAndStartRecorder()
        } catch (error: Throwable) {
            recordFailure(error)
        } finally {
            // Los displays privados destruyen sus tareas al liberarse; solo quitamos el que creamos.
            clean { display?.release() }
            clean { reader?.setOnImageAvailableListener(null, null) }
            clean { reader?.close() }
            clean {
                drainThread.quitSafely()
                drainThread.join(500)
                if (drainThread.isAlive) {
                    drainThread.quit()
                    drainThread.join(250)
                }
                check(!drainThread.isAlive) { "no se pudo cerrar el hilo auxiliar de captura" }
            }
        }

        failure?.let { error ->
            // Un grabador ya iniciado también debe cerrarse si falló la limpieza del arranque.
            try {
                recorder?.release()
            } catch (releaseError: Throwable) {
                error.addSuppressed(releaseError)
            }
            throw error
        }
        return checkNotNull(recorder)
    }

    private fun createDisplay(surface: Surface, handler: Handler): VirtualDisplay {
        try {
            val context = checkNotNull(Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").invoke(null) as? Context) {
                "el capturador no tiene contexto de shell"
            }
            val configClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
            val builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
            val builder = builderClass.getConstructor(
                String::class.java, Integer.TYPE, Integer.TYPE, Integer.TYPE,
            ).newInstance("SoundShare capture bootstrap", 128, 128, 160)
            builderClass.getMethod("setFlags", Integer.TYPE)
                .invoke(builder, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
            builderClass.getMethod("setSurface", Surface::class.java).invoke(builder, surface)
            val config = builderClass.getMethod("build").invoke(builder)

            // DisplayManager(Context) consulta recursos que el contexto mínimo no implementa.
            val globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val global = globalClass.getMethod("getInstance").invoke(null)
            val display = globalClass.getMethod(
                "createVirtualDisplay", Context::class.java, MediaProjection::class.java,
                configClass, VirtualDisplay.Callback::class.java, Handler::class.java,
            ).invoke(global, context, null, config, null, handler)
            return checkNotNull(display as? VirtualDisplay) {
                "Android 11 no permitió crear el display privado del capturador"
            }
        } catch (error: ReflectiveOperationException) {
            throw IOException("Android 11 no admite el arranque de captura fuera de pantalla", error)
        }
    }
}
