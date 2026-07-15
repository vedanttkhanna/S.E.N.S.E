package com.aegisedge.os

import com.aegisedge.os.core.memory.ForensicLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ForensicLedgerTest {

    private fun tempEnclave(): File =
        Files.createTempDirectory("aegis_test_enclave").toFile()

    @Test
    fun `chain verifies after multiple appends`() {
        val ledger = ForensicLedger(tempEnclave())
        ledger.append("INC-1", "aa".repeat(32), "bb".repeat(32), "cc".repeat(32))
        ledger.append("INC-2", "dd".repeat(32), "ee".repeat(32), "ff".repeat(32))
        assertNull(ledger.verifyChain())
        assertEquals(2, ledger.entries().size)
    }

    @Test
    fun `tampering with an entry breaks the chain`() {
        val dir = tempEnclave()
        val ledger = ForensicLedger(dir)
        ledger.append("INC-1", "aa".repeat(32), "bb".repeat(32), "cc".repeat(32))
        ledger.append("INC-2", "dd".repeat(32), "ee".repeat(32), "ff".repeat(32))

        val file = File(dir, "ledger.chain")
        file.writeText(file.readText().replace("INC-1", "INC-X"))

        assertEquals("INC-X", ForensicLedger(dir).verifyChain())
    }
}
