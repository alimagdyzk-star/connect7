package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.core.network.LanLinkEngine
import com.example.core.protocol.NetworkPacket
import com.example.core.security.CryptoIdentityManager
import com.example.data.local.ApexDatabase
import com.example.data.local.PairState
import com.example.data.local.PairedDeviceEntity
import com.example.data.local.SyncStatus
import com.example.data.repository.ApexRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var context: Context
    private lateinit var database: ApexDatabase
    private lateinit var repository: ApexRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = ApexDatabase.createInMemory(context)
        val crypto = CryptoIdentityManager(context)
        repository = ApexRepository(context, database, crypto)
        repository.lanEngine.start()
    }

    @After
    fun tearDown() {
        repository.lanEngine.stop()
        database.close()
    }

    @Test
    fun `app_name matches ApexConnect branding`() {
        assertEquals("ApexConnect", context.getString(R.string.app_name))
    }

    @Test
    fun `apple redesigned UI navigates cleanly across Devices, Browse Files, Space Messages & Notes, and Controls`() {
        composeRule.waitForIdle()

        // 1. Verify clean Hub screen renders with Active Device capsule & Continuity Workspace cards
        composeRule.onNodeWithTag("active_device_capsule").assertIsDisplayed()
        composeRule.onNodeWithTag("spotlight_remote_files_card").assertIsDisplayed()
        composeRule.onNodeWithTag("spotlight_device_space_card").assertIsDisplayed()

        // 2. Navigate to Apple Files-style Remote Browser via Spotlight card
        composeRule.onNodeWithTag("spotlight_remote_files_card").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("remote_files_screen").assertIsDisplayed()

        // 3. Navigate to iMessage & Apple Notes Space tab, send a message, and switch to Notes segment
        composeRule.onNodeWithTag("nav_tab_space").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("device_space_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_message_input").performTextInput("Hello from Apple UI test")
        composeRule.onNodeWithTag("send_message_button").performClick()
        composeRule.waitForIdle()

        // Switch segmented control to Notes
        composeRule.onNodeWithTag("subtab_notes").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("new_note_fab").assertIsDisplayed()

        // 4. Navigate to Control Center / Trackpad tab
        composeRule.onNodeWithTag("nav_tab_tools").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("plugins_tools_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("remote_touchpad_surface").assertIsDisplayed()
    }

    @Test
    fun `NetworkPacket round-trip serialization preserves v8 fields and payloadTransferInfo`() {
        val original = NetworkPacket(
            id = 1700000123L,
            type = NetworkPacket.TYPE_SHARE_REQUEST,
            body = JSONObject().put("filename", "diagram.png"),
            payloadSize = 4096L,
            payloadTransferInfo = JSONObject().put("port", 1739)
        )
        val wire = original.serialize()
        val parsed = NetworkPacket.parse(wire)
        assertEquals(1700000123L, parsed.id)
        assertEquals(NetworkPacket.TYPE_SHARE_REQUEST, parsed.type)
        assertEquals("diagram.png", parsed.getString("filename"))
        assertEquals(4096L, parsed.payloadSize)
        assertEquals(1739, parsed.payloadTransferInfo?.optInt("port"))
        assertTrue(parsed.hasPayload())
    }

    @Test
    fun `remote file browser enforces pairing, capability checks, directory listing, and verified download`() = runBlocking {
        delay(150)

        val pairedWs = PairedDeviceEntity(
            deviceId = LanLinkEngine.WORKSTATION_PEER_ID,
            deviceName = "Test Workstation",
            deviceType = "desktop",
            platformOs = "Linux",
            ipAddress = "127.0.0.1",
            tcpPort = repository.lanEngine.activeTcpPort,
            pairState = PairState.UNPAIRED,
            isReachable = true,
            certificateFingerprint = "AA:BB:CC",
            verificationCode = "1234 - 5678",
            supportsRemoteFs = true,
            isLoopbackNode = true
        )
        database.deviceDao().upsertDevice(pairedWs)

        val unpairedBrowse = repository.listRemoteDirectory(pairedWs.deviceId, "/")
        assertTrue(unpairedBrowse.isFailure)

        database.deviceDao().updatePairState(pairedWs.deviceId, PairState.PAIRED)
        val rootListing = repository.listRemoteDirectory(pairedWs.deviceId, "/")
        assertTrue("Expected root listing to succeed: ${rootListing.exceptionOrNull()}", rootListing.isSuccess)
        val (canonicalPath, entries) = rootListing.getOrThrow()
        assertEquals("/", canonicalPath)
        assertTrue(entries.any { it.name == "Documents" && it.isDirectory })
        val readmeEntry = entries.firstOrNull { it.name == "KDE_Connect_SFTP_Export.md" }
        assertNotNull(readmeEntry)

        val memOut = ByteArrayOutputStream()
        val shaResult = repository.lanEngine.streamRemoteFileDownload(
            ipAddress = "127.0.0.1",
            tcpPort = repository.lanEngine.activeTcpPort,
            remoteRelativePath = readmeEntry!!.relativePath,
            destinationOutput = memOut,
            onProgress = { _, _ -> }
        )
        assertTrue(shaResult.isSuccess)
        assertTrue(memOut.toString(Charsets.UTF_8.name()).contains("KDE Connect v8 Authorized Export Root"))

        val tvPeer = pairedWs.copy(
            deviceId = "tv_no_fs",
            pairState = PairState.PAIRED,
            supportsRemoteFs = false
        )
        database.deviceDao().upsertDevice(tvPeer)
        val unsupportedRes = repository.listRemoteDirectory("tv_no_fs", "/")
        assertTrue(unsupportedRes.exceptionOrNull() is UnsupportedOperationException)
    }

    @Test
    fun `persistent device space isolates per-device messages, queues offline, deduplicates UUIDs, and resolves note conflicts`() = runBlocking {
        delay(120)

        val offlinePeer = PairedDeviceEntity(
            deviceId = "peer_alpha",
            deviceName = "Offline Laptop",
            deviceType = "laptop",
            platformOs = "Linux",
            ipAddress = "127.0.0.1",
            tcpPort = repository.lanEngine.activeTcpPort,
            pairState = PairState.PAIRED,
            isReachable = false,
            certificateFingerprint = "11:22:33",
            verificationCode = "ABCD - EF01",
            isLoopbackNode = true
        )
        val secondPeer = offlinePeer.copy(deviceId = "peer_beta", deviceName = "Second Phone")
        database.deviceDao().upsertDevices(listOf(offlinePeer, secondPeer))

        repository.sendTextMessage("peer_alpha", "Queued while offline")
        val alphaMsgs = repository.observeMessages("peer_alpha").first()
        val betaMsgs = repository.observeMessages("peer_beta").first()
        assertEquals(1, alphaMsgs.size)
        assertEquals(SyncStatus.QUEUED_OFFLINE, alphaMsgs.first().syncStatus)
        assertTrue(betaMsgs.isEmpty())

        repository.toggleDeviceReachability("peer_alpha")
        val syncedMsgs = repository.observeMessages("peer_alpha").first()
        assertEquals(SyncStatus.SYNCED, syncedMsgs.first().syncStatus)

        val incomingPkt = NetworkPacket(
            type = NetworkPacket.TYPE_SPACE_MESSAGE,
            body = JSONObject().apply {
                put("uuid", "dup_uuid_999")
                put("contentType", "TEXT")
                put("text", "Hello once")
                put("createdAt", 1000L)
                put("updatedAt", 1000L)
            }
        )
        repository.handleIncomingPacketFromPeer("peer_alpha", incomingPkt, null)
        repository.handleIncomingPacketFromPeer("peer_alpha", incomingPkt, null)
        val afterDup = repository.observeMessages("peer_alpha").first()
        assertEquals(2, afterDup.size)

        val olderNote = NetworkPacket(
            type = NetworkPacket.TYPE_SPACE_NOTE,
            body = JSONObject().apply {
                put("uuid", "shared_note_1")
                put("authorDeviceId", "peer_alpha")
                put("title", "Initial Draft")
                put("content", "Version 1")
                put("revision", 1)
                put("createdAt", 2000L)
                put("updatedAt", 2000L)
            }
        )
        val newerNote = NetworkPacket(
            type = NetworkPacket.TYPE_SPACE_NOTE,
            body = JSONObject().apply {
                put("uuid", "shared_note_1")
                put("authorDeviceId", "peer_alpha")
                put("title", "Updated Spec")
                put("content", "Version 2 wins")
                put("revision", 2)
                put("createdAt", 2000L)
                put("updatedAt", 5000L)
            }
        )
        repository.handleIncomingPacketFromPeer("peer_alpha", newerNote, null)
        repository.handleIncomingPacketFromPeer("peer_alpha", olderNote, null)

        val notes = repository.observeNotes("peer_alpha").first()
        assertEquals(1, notes.size)
        assertEquals("Updated Spec", notes.first().title)
        assertEquals("Version 2 wins", notes.first().content)
        assertFalse(notes.first().isDeletedTombstone)
    }
}
