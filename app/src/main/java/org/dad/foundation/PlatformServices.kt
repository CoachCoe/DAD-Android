package org.dad.foundation

enum class PermissionState { NOT_REQUESTED, ALLOWED, DENIED, UNAVAILABLE }
interface CameraPermission {
    suspend fun status(): PermissionState
    suspend fun request(): PermissionState
}
interface PushRegistration {
    suspend fun register(token: String)
    suspend fun unregister()
}
interface WalletIssuance { suspend fun saveLink(): String }
