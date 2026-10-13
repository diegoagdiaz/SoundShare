package com.soundshare

import org.junit.Assert.*
import org.junit.Test

class LanCandidatesTest {
    private val paired = LanStore.Pc("a".repeat(32), "b".repeat(64), "Mi PC", 47101, "192.168.1.2", 20)

    @Test fun spoofedReplyCannotReplaceAnotherAddressForTheSamePc() {
        val candidates = LanCandidates(listOf(paired))
        candidates.add(LanProtocol.Reply(paired.id, 47101, "PC real"), "192.168.1.3")
        candidates.add(LanProtocol.Reply(paired.id, 65000, "Anuncio falso"), "192.168.1.99")
        candidates.add(LanProtocol.Reply("c".repeat(32), 47101, "PC desconocida"), "192.168.1.4")
        val result = candidates.values()
        assertEquals(listOf("192.168.1.3", "192.168.1.99"), result.map { it.address })
        assertTrue(result.all { it.secret == paired.secret })
        assertEquals("192.168.1.2", paired.address)
        assertEquals("Mi PC", paired.name)
    }

    @Test fun differentPortsRemainCandidatesAndRepeatedPacketsDoNotGrowTheList() {
        val candidates = LanCandidates(listOf(paired))
        repeat(10) { candidates.add(LanProtocol.Reply(paired.id, 47101, "PC"), "192.168.1.3") }
        candidates.add(LanProtocol.Reply(paired.id, 47102, "PC"), "192.168.1.3")
        assertEquals(listOf(47101, 47102), candidates.values().map { it.port })
    }

    @Test fun replyFloodIsBoundedAndCannotExcludeTheLastAuthenticatedEndpoint() {
        val candidates = LanCandidates(listOf(paired))
        repeat(100) { candidates.add(LanProtocol.Reply(paired.id, 47000 + it, "Ruido"), "192.168.1.99") }
        candidates.add(LanProtocol.Reply(paired.id, paired.port, "PC real"), checkNotNull(paired.address))
        assertEquals(8, candidates.values().size)
        assertEquals(paired.address, candidates.values().first().address)
        assertEquals(paired.port, candidates.values().first().port)
    }

    @Test fun mostRecentlyUsedPcKeepsPriorityAcrossAddresses() {
        val older = paired.copy(id = "d".repeat(32), lastUsed = 10)
        val candidates = LanCandidates(listOf(older, paired))
        candidates.add(LanProtocol.Reply(older.id, older.port, "Vieja"), "192.168.1.4")
        candidates.add(LanProtocol.Reply(paired.id, paired.port, "Reciente"), "192.168.1.3")
        assertEquals(listOf(paired.id, older.id), candidates.values().map { it.id })
    }
}
