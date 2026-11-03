package com.example.atlas

import com.example.atlas.acquisition.wifi.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TcpPortScannerTest {
    @Test fun policyRejectsHostnamesInvalidPortsAndUnboundedRanges() {
        assertNull(TcpScanPolicy.validate(TcpScanRequest("192.168.1.1", 1, 4096)))
        assertNotNull(TcpScanPolicy.validate(TcpScanRequest("example.com", 1, 10)))
        assertNotNull(TcpScanPolicy.validate(TcpScanRequest("256.1.1.1", 1, 10)))
        assertNotNull(TcpScanPolicy.validate(TcpScanRequest("192.168.1.1", 0, 10)))
        assertNotNull(TcpScanPolicy.validate(TcpScanRequest("192.168.1.1", 10, 9)))
        assertNotNull(TcpScanPolicy.validate(TcpScanRequest("192.168.1.1", 1, 4097)))
    }

    @Test fun scannerReportsOnlySuccessfulConnectionsAsOpen() = runBlocking {
        val visited = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val scanner = TcpPortScanner(TcpProbe { address, timeout ->
            assertEquals(TcpScanPolicy.TIMEOUT_MILLIS, timeout)
            visited.add(address.port)
            when (address.port) {
                80, 443 -> TcpProbeResult.OPEN
                81 -> TcpProbeResult.TIMEOUT
                82 -> TcpProbeResult.ERROR
                else -> TcpProbeResult.NO_CONNECTION
            }
        })
        var latest: TcpScanProgress? = null
        val result = scanner.scan(TcpScanRequest("192.168.1.1", 1, 512)) { latest = it }
        assertEquals((1..512).toSet(), visited)
        assertEquals(listOf(80, 443), result.openPorts)
        assertEquals(512, result.tested)
        assertEquals(508, result.noConnection)
        assertEquals(1, result.timedOut)
        assertEquals(1, result.errors)
        assertEquals(result, latest)
    }
}
