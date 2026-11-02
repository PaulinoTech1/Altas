package com.example.atlas.model

enum class RadioType(val title: String) {
    BLUETOOTH("Bluetooth"), NFC("NFC"), WIFI("Wi-Fi"), CELLULAR("Cellular")
}

enum class Availability(val label: String) {
    AVAILABLE("Available"), UNAVAILABLE("Not available"), UNKNOWN("Not checked")
}

enum class PermissionState(val label: String) {
    GRANTED("Granted"), REQUIRED("Permission required"), NOT_REQUIRED("No runtime permission needed")
}

enum class RadioState(val label: String) {
    ENABLED("Enabled"), DISABLED("Disabled"), PERMISSION_REQUIRED("Permission required"),
    NOT_QUERIED("Not queried"), UNAVAILABLE("Not available"), ERROR("Unable to read state")
}

enum class CollectionState(val label: String) {
    NOT_IMPLEMENTED("Not implemented")
}

data class RadioCapability(
    val type: RadioType,
    val availability: Availability = Availability.UNKNOWN,
    val permission: PermissionState = PermissionState.NOT_REQUIRED,
    val state: RadioState = RadioState.NOT_QUERIED,
    val detail: String = "Waiting for capability check",
    val sourceApi: String = "PackageManager",
    val checkedAtEpochMillis: Long? = null,
    val collection: CollectionState = CollectionState.NOT_IMPLEMENTED,
    val lastMeasurementEpochMillis: Long? = null,
    val error: String? = null
)
