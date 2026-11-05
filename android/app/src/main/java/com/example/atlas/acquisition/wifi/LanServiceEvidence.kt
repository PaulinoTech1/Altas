package com.example.atlas.acquisition.wifi

/** Names and roles are claims advertised by another device, not verified identities. */
data class LanServiceObservation(
    val address: String,
    val hostname: String?,
    val serviceName: String,
    val serviceType: String
)

data class LanDeviceEvidence(
    val address: String,
    val respondingPorts: List<Int>,
    val hostnames: List<String>,
    val services: List<LanServiceObservation>,
    val networkRole: String? = null
) {
    val advertisedRoles: List<String> get() = services.mapNotNull { roleFor(it.serviceType) }.distinct()

    companion object {
        fun merge(target: LanTarget, tcpHosts: List<LanHost>, serviceRows: List<LanServiceObservation>): List<LanDeviceEvidence> {
            val allowed = target.hosts.toHashSet()
            val tcp = tcpHosts.filter { it.address in allowed }.associateBy { it.address }
            val services = serviceRows.filter { it.address in allowed }
                .map { it.copy(hostname = clean(it.hostname), serviceName = clean(it.serviceName) ?: "Unnamed service") }
                .distinctBy { Triple(it.address, it.serviceName, it.serviceType) }
                .groupBy { it.address }
            return (tcp.keys + services.keys + listOfNotNull(target.gatewayAddress?.takeIf { it in allowed }))
                .distinct().sortedWith(compareBy { ipValue(it) }).map { address ->
                val rows = services[address].orEmpty()
                LanDeviceEvidence(address, tcp[address]?.respondingPorts.orEmpty(),
                    rows.mapNotNull { it.hostname }.distinct(), rows,
                    if (address == target.gatewayAddress) "Default gateway (Android route)" else null)
            }
        }

        private fun clean(value: String?): String? = value?.filterNot {
            Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() ||
                Character.getType(it) == Character.LINE_SEPARATOR.toInt() ||
                Character.getType(it) == Character.PARAGRAPH_SEPARATOR.toInt()
        }
            ?.trim()?.take(80)?.takeIf { it.isNotBlank() }

        private fun ipValue(address: String): Long = address.split('.').fold(0L) { n, part -> (n shl 8) or part.toLong() }

        fun roleFor(type: String): String? = when (type.trim().trim('.').lowercase().removeSuffix(".local")) {
            "_ipp._tcp", "_ipps._tcp", "_printer._tcp" -> "Print service"
            "_airplay._tcp", "_googlecast._tcp" -> "Media receiver service"
            "_smb._tcp" -> "File sharing service"
            "_workstation._tcp" -> "Workstation service"
            "_ssh._tcp" -> "Remote shell service"
            "_http._tcp" -> "Web service"
            else -> null
        }
    }
}
