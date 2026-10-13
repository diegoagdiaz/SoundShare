package com.soundshare

/** UDP no autentica: cada dirección queda como candidato hasta comprobar su secreto por TCP. */
internal class LanCandidates(paired: List<LanStore.Pc>) {
    private val pairedById = paired.associateBy { it.id }
    private val found = mutableMapOf<String, LinkedHashMap<Pair<String, Int>, LanStore.Pc>>()

    fun add(reply: LanProtocol.Reply, address: String) {
        val pc = pairedById[reply.pcId] ?: return
        val candidates = found.getOrPut(pc.id) { linkedMapOf() }
        val key = address to reply.port
        if (key !in candidates && candidates.size >= MAX_PER_PC) {
            // Una respuesta de la dirección autenticada anteriormente conserva su lugar aun con ruido UDP.
            if (address != pc.address || reply.port != pc.port) return
            candidates.remove(candidates.keys.last())
        }
        candidates[key] = pc.copy(name = reply.name, port = reply.port, address = address)
    }

    fun values(): List<LanStore.Pc> = found.values.flatMap { it.values }.sortedWith(
        compareByDescending<LanStore.Pc> { it.lastUsed }.thenByDescending {
            val known = pairedById.getValue(it.id)
            it.address == known.address && it.port == known.port
        },
    )

    companion object { private const val MAX_PER_PC = 8 }
}
