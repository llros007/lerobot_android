package com.leneo.ipdevices.model

data class CameraInfo(
    val id: String,
    val kind: String,
    val label: String,
    val facing: String? = null,
    val opened: Boolean = false,
    val allowed: Boolean = true,
    val error: String? = null,
    val path: String,
)

data class SerialInfo(
    val id: String,
    val label: String,
    val vid: Int,
    val pid: Int,
    val serial: String,
    val tcpPort: Int,
    val baud: Int,
    val opened: Boolean = false,
    val error: String? = null,
)

data class BridgeSnapshot(
    val running: Boolean,
    val httpPort: Int,
    val ips: List<String>,
    val cameras: List<CameraInfo>,
    val serial: List<SerialInfo>,
    val message: String = "",
)
