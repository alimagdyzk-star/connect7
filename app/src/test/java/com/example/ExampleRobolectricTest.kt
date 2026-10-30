package com.example

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.example.core.diagnostics.DiagnosticLogger
import com.example.core.diagnostics.LogCategory
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var context: Context
    private lateinit var dbA: ApexDatabase
    private lateinit var dbB: ApexDatabase
    private lateinit var repoA: ApexRepository
    private lateinit var repoB: ApexRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbA = ApexDatabase.createInMemory(context)
        dbB = ApexDatabase.createInMemory(context)

        val cryptoA = CryptoIdentityManager(context, profilePrefix = "test_peer_a_")
        val cryptoB = CryptoIdentityManager(context, profilePrefix = "test_peer_b_")
        cryptoA.localDeviceName = "Phone Alpha"
        cryptoB.localDeviceName = "Workstation Beta"

        repoA = ApexRepository(context, dbA, cryptoA)
        repoB = ApexRepository(context, dbB, cryptoB)
    }

    @After
    fun tearDown() {
        repoA.lanEngine.stop()
        repoB.lanEngine.stop()
        dbA.close()
        dbB.close()
    }

    @Test
    fun `app_name matches ApexConnect branding and cold boot contains zero fake demo devices`() = runBlocking {
        assertEquals("ApexConnect", context.getString(R.string.app_name))
        val initialDevices = repoA.observeDevices().first()
        assertTrue("Cold startup must have zero fake demo devices", initialDevices.isEmpty())
    }

    @Test
    fun `clean Apple UI renders live local identity radar and navigates across all 5 tabs including Settings`() {
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitForIdle()

            // 1. Verify This Phone card, Active Wi-Fi capsule, and Live Discovery Platter render (no fake mock peers)
            composeRule.onNodeWithTag("active_device_capsule").assertIsDisplayed()
            composeRule.onNodeWithTag("local_identity_card").assertIsDisplayed()
            composeRule.onNodeWithTag("empty_lan_discovery_card").assertIsDisplayed()

            composeRule.onNodeWithTag("hub_lazy_column")
                .performScrollToNode(hasTestTag("spotlight_remote_files_card"))
            composeRule.onNodeWithTag("spotlight_remote_files_card").assertIsDisplayed()
            composeRule.onNodeWithTag("spotlight_device_space_card").assertIsDisplayed()

            // 2. Navigate to Remote Files tab and verify clean status card
            composeRule.onNodeWithTag("spotlight_remote_files_card").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("remote_files_screen").assertIsDisplayed()
            composeRule.onNodeWithTag("files_status_card").assertIsDisplayed()

            // 3. Navigate to Space tab and verify clean Connect a Device prompt
            composeRule.onNodeWithTag("nav_tab_space").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("device_space_screen").assertIsDisplayed()
            composeRule.onNodeWithTag("space_no_device_card").assertIsDisplayed()

            // 4. Navigate to Settings tab and verify Diagnostic Log Recording & Export controls
            composeRule.onNodeWithTag("nav_tab_settings").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()
            composeRule.onNodeWithTag("settings_log_toggle").assertIsDisplayed()
            composeRule.onNodeWithTag("settings_screen")
                .performScrollToNode(hasTestTag("export_logs_saf_button"))
            composeRule.onNodeWithTag("export_logs_saf_button").assertIsDisplayed()

            // 5. Navigate to Controls tab and return to Devices via Open Devices button
            composeRule.onNodeWithTag("nav_tab_tools").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("plugins_tools_screen").assertIsDisplayed()
            composeRule.onNodeWithTag("tools_open_devices_button").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("local_identity_card").assertIsDisplayed()
        }
    }

    @Test
    fun `DiagnosticLogger records events redacts passwords and builds exportable report`() {
        val logger = DiagnosticLogger.getInstance(context)
        logger.setLoggingEnabled(true)
        logger.setVerbosePacketsEnabled(true)
        logger.info(
            category = LogCategory.FILES,
            message = "Received SFTP packet",
            details = """{"ip":"192.168.1.25","port":1739,"password":"super_secret_sftp_pwd"}"""
        )

        val report = logger.buildFullDiagnosticReport(
            localDeviceName = "Phone Alpha",
            localDeviceId = "node_alpha_123",
            localFingerprint = "AA:BB:CC:DD",
            localIps = listOf("192.168.1.10"),
            activeTcpPort = 1716,
            activeUdpPort = 1716,
            multicastHeld = true,
            sharedFolderNames = listOf("Downloads"),
            devices = emptyList()
        )

        assertTrue(report.contains("APEXCONNECT DIAGNOSTIC & PROTOCOL REPORT"))
        assertTrue(report.contains("Phone Alpha"))
        assertTrue(report.contains("192.168.1.10"))

        val shareIntent = logger.createShareableReportIntent(report)
        assertTrue("Expected FileProvider share intent to succeed: ${shareIntent.exceptionOrNull()}", shareIntent.isSuccess)

        val tempExportFile = File(context.cacheDir, "saf_export_verify.txt")
        val safRes = logger.exportReportToSafUri(Uri.fromFile(tempExportFile), report)
        assertTrue("Expected SAF URI export to write report: ${safRes.exceptionOrNull()}", safRes.isSuccess)
        assertTrue(tempExportFile.readText().contains("APEXCONNECT DIAGNOSTIC & PROTOCOL REPORT"))
    }

    @Test
    fun `NetworkPacket round-trip serialization preserves v7 protocol compatibility and payloadTransferInfo`() {
        assertEquals(7, NetworkPacket.PROTOCOL_VERSION)
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
    fun `real two-peer X509 mutual TLS handshake, KDE Connect pairing, remote directory browse, and SHA-256 download`() = runBlocking {
        repoA.lanEngine.start()
        repoB.lanEngine.start()

        // Deterministically await actual ServerSocket bind on both peers (prevents multi-core CI port race!)
        repoA.lanEngine.awaitReady(5000L)
        repoB.lanEngine.awaitReady(5000L)
        assertTrue(
            "Both peers must bind distinct TCP ports (A=${repoA.lanEngine.activeTcpPort}, B=${repoB.lanEngine.activeTcpPort})",
            repoA.lanEngine.activeTcpPort > 0 &&
                repoB.lanEngine.activeTcpPort > 0 &&
                repoA.lanEngine.activeTcpPort != repoB.lanEngine.activeTcpPort
        )

        val peerBIdentityPacket = NetworkPacket.createIdentityPacket(
            deviceId = repoB.identityManager.localDeviceId,
            deviceName = repoB.identityManager.localDeviceName,
            deviceType = "desktop",
            tcpPort = repoB.lanEngine.activeTcpPort
        )

        // 1. Establish real TCP -> Inverted Mutual X.509 TLS link between Peer A and Peer B!
        val connected = repoA.lanEngine.initiateOutgoingConnectionToPeer(
            remoteIp = "127.0.0.1",
            peerTcpPort = repoB.lanEngine.activeTcpPort,
            udpIdentityPacket = peerBIdentityPacket
        )
        assertTrue("Expected real X.509 mutual TLS handshake to succeed", connected)

        // Wait until both Room databases register the encrypted peer session
        var peerBOnA: PairedDeviceEntity? = null
        var peerAOnB: PairedDeviceEntity? = null
        repeat(40) {
            peerBOnA = repoA.observeDevices().first().firstOrNull { it.deviceId == repoB.identityManager.localDeviceId }
            peerAOnB = repoB.observeDevices().first().firstOrNull { it.deviceId == repoA.identityManager.localDeviceId }
            if (peerBOnA != null && peerAOnB != null) return@repeat
            delay(60)
        }
        assertNotNull("Peer A should see Peer B over TLS", peerBOnA)
        assertNotNull("Peer B should see Peer A over TLS", peerAOnB)

        // Both sides MUST compute the exact same 8-hex-char SHA-256 verification code!
        assertEquals(peerBOnA!!.verificationCode, peerAOnB!!.verificationCode)

        // 2. Unpaired file browse must be rejected
        val unpairedBrowse = repoA.listRemoteDirectory(repoB.identityManager.localDeviceId, "/")
        assertTrue(unpairedBrowse.isFailure)

        // 3. Real KDE Connect Pairing Handshake over TLS (`requestPairing` -> `acceptPairing`)
        repoA.requestPairing(repoB.identityManager.localDeviceId)
        repeat(30) {
            val stateOnB = repoB.observeDevices().first()
                .firstOrNull { it.deviceId == repoA.identityManager.localDeviceId }?.pairState
            if (stateOnB == PairState.PAIR_REQUESTED_BY_PEER) return@repeat
            delay(60)
        }
        assertEquals(
            PairState.PAIR_REQUESTED_BY_PEER,
            repoB.observeDevices().first().first { it.deviceId == repoA.identityManager.localDeviceId }.pairState
        )

        repoB.acceptPairing(repoA.identityManager.localDeviceId)
        repeat(30) {
            val stateOnA = repoA.observeDevices().first()
                .firstOrNull { it.deviceId == repoB.identityManager.localDeviceId }?.pairState
            if (stateOnA == PairState.PAIRED) return@repeat
            delay(60)
        }
        assertEquals(
            PairState.PAIRED,
            repoA.observeDevices().first().first { it.deviceId == repoB.identityManager.localDeviceId }.pairState
        )

        // 4. Write a real file inside Peer B's exported Documents folder and browse/download it from Peer A over TLS!
        val docsDir = File(repoB.lanEngine.authorizedVaultRoot, "Documents").apply { mkdirs() }
        val realRemoteFile = File(docsDir, "live_tls_report.txt")
        val expectedContent = "Verified real encrypted transfer over KDE Connect TLS 1.3!"
        realRemoteFile.writeText(expectedContent)

        val dirResult = repoA.listRemoteDirectory(repoB.identityManager.localDeviceId, "/Documents")
        assertTrue("Expected directory listing over TLS: ${dirResult.exceptionOrNull()}", dirResult.isSuccess)
        val (path, entries) = dirResult.getOrThrow()
        assertEquals("/Documents", path)
        val targetEntry = entries.firstOrNull { it.name == "live_tls_report.txt" }
        assertNotNull(targetEntry)

        val downloadedResult = repoA.downloadRemoteFile(
            deviceId = repoB.identityManager.localDeviceId,
            entry = targetEntry!!,
            safDestinationUri = null
        )
        assertTrue("Expected verified TLS file download: ${downloadedResult.exceptionOrNull()}", downloadedResult.isSuccess)
        val localDownloadedFile = downloadedResult.getOrThrow()
        assertNotNull(localDownloadedFile)
        assertEquals(expectedContent, localDownloadedFile!!.readText())
    }

    @Test
    fun `persistent device space queues offline, deduplicates UUIDs, and resolves LWW notes`() = runBlocking {
        // 1. Offline queuing & per-device isolation
        val offlineAlpha = PairedDeviceEntity(
            deviceId = "offline_peer_1",
            deviceName = "Offline Laptop",
            deviceType = "laptop",
            platformOs = "Linux",
            ipAddress = "192.168.1.99",
            tcpPort = 1716,
            pairState = PairState.PAIRED,
            isReachable = false,
            certificateFingerprint = "AA:BB:CC",
            verificationCode = "1234 5678"
        )
        val secondPeer = offlineAlpha.copy(deviceId = "offline_peer_2", deviceName = "Second Phone")
        dbA.deviceDao().upsertDevices(listOf(offlineAlpha, secondPeer))

        repoA.sendTextMessage("offline_peer_1", "Queued while offline")
        val alphaMsgs = repoA.observeMessages("offline_peer_1").first()
        val betaMsgs = repoA.observeMessages("offline_peer_2").first()
        assertEquals(1, alphaMsgs.size)
        assertEquals(SyncStatus.QUEUED_OFFLINE, alphaMsgs.first().syncStatus)
        assertTrue(betaMsgs.isEmpty())

        // 2. Duplicate incoming UUID deduplication
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
        repoA.handleIncomingPacketFromPeer("offline_peer_1", incomingPkt, null)
        repoA.handleIncomingPacketFromPeer("offline_peer_1", incomingPkt, null)
        assertEquals(2, repoA.observeMessages("offline_peer_1").first().size)

        // 3. Deterministic Last-Write-Wins (LWW) Note Conflict Resolution
        val olderNote = NetworkPacket(
            type = NetworkPacket.TYPE_SPACE_NOTE,
            body = JSONObject().apply {
                put("uuid", "shared_note_1")
                put("authorDeviceId", "offline_peer_1")
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
                put("authorDeviceId", "offline_peer_1")
                put("title", "Updated Spec")
                put("content", "Version 2 wins")
                put("revision", 2)
                put("createdAt", 2000L)
                put("updatedAt", 5000L)
            }
        )
        repoA.handleIncomingPacketFromPeer("offline_peer_1", newerNote, null)
        repoA.handleIncomingPacketFromPeer("offline_peer_1", olderNote, null)

        val notes = repoA.observeNotes("offline_peer_1").first()
        assertEquals(1, notes.size)
        assertEquals("Updated Spec", notes.first().title)
        assertEquals("Version 2 wins", notes.first().content)
        assertFalse(notes.first().isDeletedTombstone)
    }
}
