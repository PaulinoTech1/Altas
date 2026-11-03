# Atlas TCP port checks

The Wi-Fi page has two independent tools. The AP survey displays Android-provided radio scan results. The TCP tool makes connect attempts to one explicitly entered IPv4 address through an available Wi-Fi network. An AP address (BSSID) is not a TCP target.

The operator enters the target and port range, confirms authorization, and starts each scan. There is no background discovery, automatic target selection, subnet expansion, banner grabbing, or payload exchange. The tool accepts at most 4,096 ports per run in the range 1–65,535. It uses at most 16 concurrent sockets with a 450 ms connect timeout. Stop and leaving the Wi-Fi page cancel the worker; a blocked connect can take up to its timeout to return. Results are held only in memory.

"Connected" means a TCP handshake succeeded from this phone over the selected Wi-Fi network. "No connection" includes connection failures and is not proof that the port is closed. Timeouts can reflect filtering, loss, or reachability problems. Errors are counted separately. A successful build and fake-probe unit tests do not verify network behavior on a device.

The app adds the normal Android `INTERNET` permission. It uses `ConnectivityManager.registerNetworkCallback` to find a Wi-Fi network and that network's socket factory to prevent a silent cellular scan. If Wi-Fi is unavailable, the scan fails visibly. Network changes during a run can produce errors; restart explicitly after reconnecting.

The worker uses the Android/JVM socket API. Native code was not added because no representative device benchmark has shown a benefit that justifies the added memory-safety and maintenance cost.

## Validation still needed

On an authorized test host, test known listening and non-listening ports, stop behavior, Wi-Fi loss and reconnect, a 4,096-port run, and impact on browsing, battery, and the host. Record measured duration and false classifications before making performance claims.
