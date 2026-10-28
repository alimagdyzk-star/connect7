package com.example.core.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * KDE Connect Wire Protocol v8 NetworkPacket implementation.
 *
 * Encapsulates JSON-lines packet metadata (`id`, `type`, `body`, `payloadSize`, `payloadTransferInfo`)
 * exchanged over TLS/LAN sockets, extended with explicit packet types for Remote File Browsing
 * (`kdeconnect.vfs.*`) and Persistent Device Space (`kdeconnect.space.*`).
 */
data class NetworkPacket(
    val id: Long = System.currentTimeMillis(),
    val type: String,
    val body: JSONObject = JSONObject(),
    val payloadSize: Long? = null,
    val payloadTransferInfo: JSONObject? = null
) {
    fun getString(key: String, default: String = ""): String =
        if (body.has(key) && !body.isNull(key)) body.optString(key, default) else default

    fun getInt(key: String, default: Int = 0): Int = body.optInt(key, default)

    fun getLong(key: String, default: Long = 0L): Long = body.optLong(key, default)

    fun getBoolean(key: String, default: Boolean = false): Boolean = body.optBoolean(key, default)

    fun getStringList(key: String): List<String> {
        val array = body.optJSONArray(key) ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optString(i)
                if (item.isNotBlank()) add(item)
            }
        }
    }

    fun hasPayload(): Boolean = (payloadSize ?: -1L) > 0L && payloadTransferInfo != null

    /**
     * Serializes the packet into a newline-delimited UTF-8 JSON frame per KDE Connect Protocol v8.
     */
    fun serialize(): String {
        val root = JSONObject()
        root.put("id", id)
        root.put("type", type)
        root.put("body", body)
        if (payloadSize != null && payloadTransferInfo != null) {
            root.put("payloadSize", payloadSize)
            root.put("payloadTransferInfo", payloadTransferInfo)
        }
        return root.toString() + "\n"
    }

    companion object {
        const val PROTOCOL_VERSION = 8

        // Core KDE Connect Packet Types
        const val TYPE_IDENTITY = "kdeconnect.identity"
        const val TYPE_PAIR = "kdeconnect.pair"
        const val TYPE_PING = "kdeconnect.ping"
        const val TYPE_FIND_MY_PHONE = "kdeconnect.findmyphone.request"
        const val TYPE_BATTERY = "kdeconnect.battery"
        const val TYPE_BATTERY_REQUEST = "kdeconnect.battery.request"
        const val TYPE_CLIPBOARD = "kdeconnect.clipboard"
        const val TYPE_CLIPBOARD_CONNECT = "kdeconnect.clipboard.connect"
        const val TYPE_SHARE_REQUEST = "kdeconnect.share.request"
        const val TYPE_MOUSEPAD_REQUEST = "kdeconnect.mousepad.request"
        const val TYPE_MPRIS = "kdeconnect.mpris"
        const val TYPE_MPRIS_REQUEST = "kdeconnect.mpris.request"
        const val TYPE_RUNCOMMAND = "kdeconnect.runcommand"
        const val TYPE_RUNCOMMAND_REQUEST = "kdeconnect.runcommand.request"
        const val TYPE_NOTIFICATION = "kdeconnect.notification"
        const val TYPE_SFTP_REQUEST = "kdeconnect.sftp.request"
        const val TYPE_SFTP = "kdeconnect.sftp"

        // Feature 1: Remote File Browser Protocol Extensions (VFS over KDE Connect transport)
        const val TYPE_VFS_LIST_REQUEST = "kdeconnect.vfs.list.request"
        const val TYPE_VFS_LIST_RESPONSE = "kdeconnect.vfs.list.response"
        const val TYPE_VFS_READ_REQUEST = "kdeconnect.vfs.read.request"
        const val TYPE_VFS_READ_RESPONSE = "kdeconnect.vfs.read.response"
        const val TYPE_VFS_WRITE_REQUEST = "kdeconnect.vfs.write.request"
        const val TYPE_VFS_WRITE_RESPONSE = "kdeconnect.vfs.write.response"

        // Feature 2: Persistent Device Space (Chat, Images & Notes Delta Sync)
        const val TYPE_SPACE_MESSAGE = "kdeconnect.space.message"
        const val TYPE_SPACE_NOTE = "kdeconnect.space.note"
        const val TYPE_SPACE_ACK = "kdeconnect.space.ack"
        const val TYPE_SPACE_SYNC_REQUEST = "kdeconnect.space.sync.request"
        const val TYPE_SPACE_SYNC_RESPONSE = "kdeconnect.space.sync.response"
        const val TYPE_SPACE_DELETE = "kdeconnect.space.delete"

        val SUPPORTED_INCOMING_CAPABILITIES = listOf(
            TYPE_PING,
            TYPE_FIND_MY_PHONE,
            TYPE_BATTERY,
            TYPE_BATTERY_REQUEST,
            TYPE_CLIPBOARD,
            TYPE_CLIPBOARD_CONNECT,
            TYPE_SHARE_REQUEST,
            TYPE_MOUSEPAD_REQUEST,
            TYPE_MPRIS,
            TYPE_MPRIS_REQUEST,
            TYPE_RUNCOMMAND,
            TYPE_RUNCOMMAND_REQUEST,
            TYPE_NOTIFICATION,
            TYPE_SFTP,
            TYPE_SFTP_REQUEST,
            TYPE_VFS_LIST_REQUEST,
            TYPE_VFS_LIST_RESPONSE,
            TYPE_VFS_READ_REQUEST,
            TYPE_VFS_READ_RESPONSE,
            TYPE_VFS_WRITE_REQUEST,
            TYPE_VFS_WRITE_RESPONSE,
            TYPE_SPACE_MESSAGE,
            TYPE_SPACE_NOTE,
            TYPE_SPACE_ACK,
            TYPE_SPACE_SYNC_REQUEST,
            TYPE_SPACE_SYNC_RESPONSE,
            TYPE_SPACE_DELETE
        )

        val SUPPORTED_OUTGOING_CAPABILITIES = SUPPORTED_INCOMING_CAPABILITIES

        fun parse(rawLine: String): NetworkPacket {
            val trimmed = rawLine.trim()
            require(trimmed.isNotEmpty()) { "Empty packet payload" }
            val json = JSONObject(trimmed)
            val type = json.optString("type", "")
            require(type.startsWith("kdeconnect.")) { "Invalid KDE Connect packet type: $type" }
            val id = json.optLong("id", System.currentTimeMillis())
            val body = json.optJSONObject("body") ?: JSONObject()
            val payloadSize = if (json.has("payloadSize")) json.optLong("payloadSize") else null
            val payloadInfo = json.optJSONObject("payloadTransferInfo")
            return NetworkPacket(
                id = id,
                type = type,
                body = body,
                payloadSize = payloadSize,
                payloadTransferInfo = payloadInfo
            )
        }

        fun createIdentityPacket(
            deviceId: String,
            deviceName: String,
            deviceType: String = "phone",
            tcpPort: Int = 1716
        ): NetworkPacket {
            val body = JSONObject().apply {
                put("deviceId", deviceId)
                put("deviceName", deviceName)
                put("deviceType", deviceType)
                put("protocolVersion", PROTOCOL_VERSION)
                put("tcpPort", tcpPort)
                put("incomingCapabilities", JSONArray(SUPPORTED_INCOMING_CAPABILITIES))
                put("outgoingCapabilities", JSONArray(SUPPORTED_OUTGOING_CAPABILITIES))
            }
            return NetworkPacket(type = TYPE_IDENTITY, body = body)
        }
    }
}
