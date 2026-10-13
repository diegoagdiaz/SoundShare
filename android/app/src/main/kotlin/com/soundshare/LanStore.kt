package com.soundshare

import android.content.Context

/** Identidad estable y PCs conocidas. Solo loopback puede reemplazar un secreto. */
class LanStore(context: Context) {
    private val prefs = context.getSharedPreferences("lan", Context.MODE_PRIVATE)
    data class Pc(val id: String, val secret: String, val name: String, val port: Int,
                  val address: String?, val lastUsed: Long)

    val phoneId: String = synchronized(LanStore::class.java) {
        prefs.getString("phone_id", null)?.takeIf { LanProtocol.validHex(it, 16) }
            ?: LanProtocol.hex(LanAuth.randomBytes(16)).also { prefs.edit().putString("phone_id", it).commit() }
    }

    @Synchronized fun paired(): List<Pc> = (prefs.getStringSet("pcs", emptySet()) ?: emptySet()).mapNotNull { id ->
        val secret = prefs.getString("${id}_secret", null) ?: return@mapNotNull null
        val port = prefs.getInt("${id}_port", 0)
        if (!LanProtocol.validHex(id, 16) || !LanProtocol.validHex(secret, 32) || port !in 1..65535) return@mapNotNull null
        Pc(id, secret, prefs.getString("${id}_name", "PC") ?: "PC", port,
            prefs.getString("${id}_address", null), prefs.getLong("${id}_used", 0))
    }.sortedByDescending { it.lastUsed }

    @Synchronized fun pair(value: LanProtocol.Pairing) {
        val ids = (prefs.getStringSet("pcs", emptySet()) ?: emptySet()).toMutableSet()
        ids.add(value.pcId)
        prefs.edit().putStringSet("pcs", ids).putString("${value.pcId}_secret", value.secret)
            .putString("${value.pcId}_name", value.name).putInt("${value.pcId}_port", value.port)
            .putLong("${value.pcId}_used", System.currentTimeMillis()).apply()
    }

    /** Solo se llama después de comprobar el secreto del servidor y recibir CONTROL. */
    @Synchronized fun authenticated(id: String, address: String, port: Int, name: String) {
        prefs.edit().putString("${id}_address", address).putInt("${id}_port", port)
            .putString("${id}_name", name).putLong("${id}_used", System.currentTimeMillis()).apply()
    }
}
