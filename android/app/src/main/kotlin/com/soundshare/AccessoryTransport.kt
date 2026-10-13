package com.soundshare

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.*
import java.util.concurrent.atomic.AtomicInteger

/** Descriptor persistente por cable; los modos abren sesiones lógicas, no lectores USB nuevos. */
class AccessoryTransport(private val context: Context, private val onReady: () -> Unit) {
    private val usb = context.getSystemService(UsbManager::class.java)
    // Un pedido por cable; UsbAccessory.equals() lee la serie y sin permiso lanza SecurityException.
    private var requested = false
    private class Link(val descriptor: ParcelFileDescriptor, val output: OutputStream)
    private var link: Link? = null
    private var current: Session? = null
    @Volatile private var closed = false
    @Volatile private var failed = false
    private val ids = AtomicInteger(java.security.SecureRandom().nextInt().let { if (it == 0) 1 else it })
    private val writeLock = Any()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                PERMISSION -> if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false)) {
                    prepare(); onReady()
                } else Log.i(TAG,"permiso AOA rechazado; PLAYER sigue por adb")
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> synchronized(this@AccessoryTransport) {
                    release(); requested = false; failed = false
                }
            }
        }
    }
    init {
        val filter = IntentFilter(PERMISSION).apply { addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED) }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver,filter)
    }

    @Synchronized fun prepare() {
        if (closed || failed || link != null) return
        val accessory = usb.accessoryList?.firstOrNull { it.manufacturer == "SoundShare" && it.model == "SoundShare" && it.version == "1" } ?: return
        if (!usb.hasPermission(accessory)) {
            if (!requested) {
                requested = true
                // El sistema rellena los extras del resultado. Intent explícito, sólo para esta app.
                val permission = PendingIntent.getBroadcast(context,0,Intent(PERMISSION).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                usb.requestPermission(accessory,permission)
            }
            return
        }
        try {
            val fd = usb.openAccessory(accessory) ?: throw IOException("openAccessory devolvió null")
            val opened = Link(fd, FileOutputStream(fd.fileDescriptor))
            link = opened
            val input = LargeReads(FileInputStream(fd.fileDescriptor))
            Thread({
                try {
                    // Este hilo entrega PCM al Player urgente: evitar una espera adicional del scheduler.
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                    while (synchronized(this) { !closed && link === opened }) {
                        val frame = AccessoryFrame.read(input)
                        synchronized(this) {
                            // La lectura puede terminar después del cierre y de una reconexión.
                            if (link !== opened) return@synchronized
                            val session = current?.takeIf { it.id == frame.session } ?: return@synchronized
                            when (frame.kind) {
                                AccessoryFrame.FORMAT, AccessoryFrame.PCM -> session.receive(frame.payload)
                                AccessoryFrame.END -> session.finish(true)
                                else -> throw IOException("tipo AOA inesperado")
                            }
                        }
                    }
                } catch (e: Exception) {
                    synchronized(this) {
                        if (!closed && link === opened) Log.w(TAG,"lector AOA: ${e.message}; fallback adb")
                    }
                } finally {
                    synchronized(this) {
                        if (link === opened) { failed = true; release() }
                    }
                }
            },"ss-accessory-read").apply { isDaemon = true; start() }
            Log.i(TAG,"accesorio abierto; lecturas USB de 16384 bytes")
        } catch (e: Exception) { failed = true; release(); Log.w(TAG,"AOA no disponible: ${e.message}") }
    }

    @Synchronized fun connect(): Session? {
        prepare()
        if (link == null || failed || closed) return null
        current?.finish()
        val id = ids.getAndIncrement().let { if (it == 0) ids.getAndIncrement() else it }
        return Session(id).also { current = it }
    }
    @Synchronized fun disable(session: Session) {
        if (current !== session) return
        failed = true; release()
    }
    private fun send(kind: Int, session: Session, opened: Link, payload: ByteArray) {
        val bytes = AccessoryFrame.encode(kind,session.id,payload)
        synchronized(writeLock) {
            val out = synchronized(this) {
                if (closed || link !== opened || current !== session ||
                    (kind != AccessoryFrame.END && session.input.isEnded)) throw IOException("sesión AOA cerrada")
                opened.output
            }
            out.write(bytes)
        }
    }
    @Synchronized private fun release() {
        current?.finish(); current = null
        val fd = link?.descriptor; link = null
        // close() tampoco tiene garantía de desbloquear read(): jamás esperamos al lector USB.
        Thread({ runCatching { fd?.close() } },"ss-accessory-close").apply { isDaemon = true; start() }
    }
    @Synchronized fun close() { closed = true; context.unregisterReceiver(receiver); release() }

    inner class Session internal constructor(val id: Int) : Closeable {
        private val opened = checkNotNull(link)
        val input = AccessoryInbox()
        val peerEnded get() = input.peerEnded
        fun receive(payload: ByteArray) = input.receive(payload)
        fun finish(peer: Boolean = false) = input.finish(peer)
        val output = object : OutputStream() {
            override fun write(b: Int) = throw IOException("AOA requiere bloques")
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (input.isEnded) throw IOException("sesión AOA cerrada")
                val kind = if (len == 8) AccessoryFrame.HELLO else AccessoryFrame.STATUS
                send(kind,this@Session,opened,b.copyOfRange(off,off+len))
            }
        }
        override fun close() {
            if (!input.finish()) return
            // Un write USB pendiente tampoco bloquea el cambio de modo ni el hilo de UI.
            Thread({ runCatching { send(AccessoryFrame.END,this,opened,ByteArray(0)) } },"ss-accessory-end")
                .apply { isDaemon = true; start() }
        }
    }
    /** Toda lectura del fd pide >=16 KB; los consumidores ven un flujo de bytes normal. */
    internal class LargeReads(private val raw: InputStream) : InputStream() {
        private val buffer = ByteArray(16384)
        private var pos = 0
        private var size = 0
        override fun read(): Int { val b=ByteArray(1);return if(read(b)==-1) -1 else b[0].toInt() and 255 }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len==0) return 0
            if (pos==size) { size=raw.read(buffer,0,buffer.size); pos=0; if(size<0)return -1 }
            val n=minOf(len,size-pos);System.arraycopy(buffer,pos,b,off,n);pos+=n;return n
        }
    }
    companion object { private const val TAG="SoundShare.AOA"; private const val PERMISSION="com.soundshare.USB_PERMISSION" }
}
