package com.aegisedge.os.core.memory

import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * Append-only hash-chained ledger — the "local cryptographic ledger" of the
 * Forensic Enclave. Each line commits to the previous line's hash, so any
 * post-hoc tampering with an incident file or ledger entry breaks the chain
 * and is detectable by [verifyChain]. This is what makes the evidence
 * forensic-grade without any cloud notary.
 */
class ForensicLedger(enclaveDir: File) {

    private val ledgerFile = File(enclaveDir, "ledger.chain").apply {
        parentFile?.mkdirs()
        if (!exists()) writeText("GENESIS|${sha256Hex("aegis-edge-genesis")}\n")
    }

    @Synchronized
    fun append(incidentId: String, videoSha256: String, audioSha256: String, manifestSha256: String) {
        val prevHash = ledgerFile.readLines().last().substringAfterLast('|')
        val payload = listOf(
            Instant.now().toString(), incidentId, videoSha256, audioSha256, manifestSha256, prevHash
        ).joinToString("|")
        val entryHash = sha256Hex(payload)
        ledgerFile.appendText("$payload|$entryHash\n")
    }

    /** Recomputes the chain; returns the id of the first corrupt entry, or null if intact. */
    @Synchronized
    fun verifyChain(): String? {
        val lines = ledgerFile.readLines()
        var prevHash = lines.first().substringAfterLast('|')
        for (line in lines.drop(1)) {
            val fields = line.split('|')
            val payload = fields.dropLast(1).joinToString("|")
            if (fields[5] != prevHash || sha256Hex(payload) != fields.last()) return fields[1]
            prevHash = fields.last()
        }
        return null
    }

    fun entries(): List<String> = ledgerFile.readLines().drop(1)

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
