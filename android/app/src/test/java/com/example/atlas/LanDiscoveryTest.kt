package com.example.atlas

import com.example.atlas.acquisition.wifi.*
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LanDiscoveryTest {
    @Test fun targetIsLimitedToPrivateSmallSubnetAndExcludesPhone() {
        val target = LanDiscoveryPolicy.target(InetAddress.getByName("192.168.10.42") as java.net.Inet4Address, 24)!!
        assertEquals(253, target.hosts.size)
        assertEquals("192.168.10.1", target.hosts.first())
        assertEquals("192.168.10.254", target.hosts.last())
        assertFalse(target.hosts.contains("192.168.10.42"))
        assertNull(LanDiscoveryPolicy.target(InetAddress.getByName("192.168.10.42") as java.net.Inet4Address, 23))
        assertNull(LanDiscoveryPolicy.target(InetAddress.getByName("8.8.8.8") as java.net.Inet4Address, 24))
    }

    @Test fun onlySuccessfulConnectionsBecomeObservedHosts() = runBlocking {
        val target = LanTarget("10.0.0.1", 30, listOf("10.0.0.2", "10.0.0.3"))
        val attempted = ConcurrentHashMap.newKeySet<String>()
        val result = LanDiscovery(LanProbe { address, port, timeout ->
            assertEquals(LanDiscoveryPolicy.timeoutMillis, timeout)
            attempted.add("$address:$port")
            if (address == "10.0.0.2" && port == 443) LanProbeResult.CONNECTED
            else LanProbeResult.TIMEOUT
        }).scan(target) {}
        assertEquals(2 * LanDiscoveryPolicy.ports.size, attempted.size)
        assertEquals(2, result.checked)
        assertEquals(listOf(LanHost("10.0.0.2", listOf(443))), result.hosts)
        assertEquals(0, result.probeErrors)
    }

    @Test fun failedProbesAreReportedRatherThanSilentlyCountedAsAbsent() = runBlocking {
        val target = LanTarget("10.0.0.1", 30, listOf("10.0.0.2"))
        val result = LanDiscovery(LanProbe { _, _, _ -> LanProbeResult.ERROR }).scan(target) {}
        assertTrue(result.hosts.isEmpty())
        assertEquals(LanDiscoveryPolicy.ports.size, result.probeErrors)
    }

    @Test fun securityDenialDoesNotBecomeAnEmptySuccessfulScan() = runTest {
        val target = LanTarget("10.0.0.1", 30, listOf("10.0.0.2"))
        try {
            LanDiscovery(LanProbe { _, _, _ -> throw SecurityException("Permission denied") }).scan(target) {}
            fail("Expected permission denial to propagate")
        } catch (_: SecurityException) { /* A denied probe must fail visibly. */ }
    }

    @Test fun serviceEvidenceMergesByInSubnetAddressWithoutInventingDeviceType() {
        val target = LanTarget("192.168.10.1", 24, listOf("192.168.10.2", "192.168.10.3"),
            gatewayAddress = "192.168.10.3")
        val services = listOf(
            LanServiceObservation("192.168.10.2", "Office-Printer.local", "Office Printer", "_ipp._tcp."),
            LanServiceObservation("192.168.10.2", "Office-Printer.local", "Office Printer", "_ipp._tcp."),
            LanServiceObservation("192.168.10.3", null, "Media Room", "_http._tcp."),
            LanServiceObservation("192.168.11.4", "Outside", "Outside", "_ipp._tcp.")
        )
        val result = LanDeviceEvidence.merge(target, listOf(LanHost("192.168.10.2", listOf(80))), services)
        assertEquals(listOf("192.168.10.2", "192.168.10.3"), result.map { it.address })
        assertEquals(listOf(80), result[0].respondingPorts)
        assertEquals(listOf("Office-Printer.local"), result[0].hostnames)
        assertEquals(listOf("Print service"), result[0].advertisedRoles)
        assertEquals(1, result[0].services.size)
        assertEquals(listOf("Web service"), result[1].advertisedRoles)
        assertEquals("Default gateway (Android route)", result[1].networkRole)
        assertTrue(result[1].hostnames.isEmpty())
        assertEquals("Print service", LanDeviceEvidence.roleFor("_ipp._tcp.local."))
        assertEquals("Web service", LanDeviceEvidence.roleFor("._http._tcp"))
        assertNull(LanDeviceEvidence.roleFor("_unknown._tcp"))
    }

    @Test fun advertisedNamesAreBoundedAndControlsRemoved() {
        val target = LanTarget("10.0.0.1", 30, listOf("10.0.0.2"))
        val result = LanDeviceEvidence.merge(target, emptyList(),
            listOf(LanServiceObservation("10.0.0.2", "A\n\u202EB", "x".repeat(100), "_smb._tcp")))
        assertEquals("AB", result.single().hostnames.single())
        assertEquals(80, result.single().services.single().serviceName.length)
    }

    @Test fun androidRouteCanReportGatewayWithoutOpenPort() {
        val target = LanTarget("192.168.1.42", 24, listOf("192.168.1.1"), gatewayAddress = "192.168.1.1")
        val result = LanDeviceEvidence.merge(target, emptyList(), emptyList())
        assertEquals("192.168.1.1", result.single().address)
        assertEquals("Default gateway (Android route)", result.single().networkRole)
        assertTrue(result.single().respondingPorts.isEmpty())
    }
}
