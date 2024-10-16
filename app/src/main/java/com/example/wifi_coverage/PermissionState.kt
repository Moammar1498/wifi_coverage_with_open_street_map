package com.example.wifi_coverage

sealed class PermissionState {
    data object Unknown : PermissionState()
    data object Granted : PermissionState()
    data object Denied : PermissionState()
    data class NeedsRequest(val permissions: Array<String>) : PermissionState() {

    }
}