package com.example.core.security

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest

data class SharedSafTree(
    val alias: String,
    val treeUriString: String
)

data class SafResolvedNode(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long,
    val mimeType: String,
    val documentUri: Uri
)

/**
 * Zero-permission Android Storage Access Framework (`DocumentsContract`) folder exporter.
 *
 * Mirrors KDE Connect Android's "Filesystem Expose" storage locations so paired peers can browse
 * real user-authorized folders (e.g., Downloads, Pictures, Documents) over encrypted TLS without
 * requesting prohibited `MANAGE_EXTERNAL_STORAGE` permissions.
 */
class SafSharedFoldersManager(
    private val context: Context,
    profilePrefix: String = ""
) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "${profilePrefix}apexconnect_saf_shares",
        Context.MODE_PRIVATE
    )

    private val _sharedTrees = MutableStateFlow(loadSavedTrees())
    val sharedTrees: StateFlow<List<SharedSafTree>> = _sharedTrees.asStateFlow()

    fun addSharedTreeUri(treeUri: Uri): SharedSafTree? {
        runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
        }
        val rawTitle = queryTreeDisplayName(treeUri)
        val cleanBase = rawTitle
            .replace('/', '_')
            .replace('\\', '_')
            .trim()
            .ifBlank { "SharedPhoneFolder" }

        val current = _sharedTrees.value.toMutableList()
        if (current.any { it.treeUriString == treeUri.toString() }) {
            return current.first { it.treeUriString == treeUri.toString() }
        }

        var alias = cleanBase
        var suffix = 2
        while (current.any { it.alias.equals(alias, ignoreCase = true) } ||
            alias.equals("Documents", true) ||
            alias.equals("Pictures", true) ||
            alias.equals("Downloads", true)
        ) {
            alias = "${cleanBase}_$suffix"
            suffix++
        }

        val added = SharedSafTree(alias = alias, treeUriString = treeUri.toString())
        current.add(added)
        saveTrees(current)
        _sharedTrees.value = current
        return added
    }

    fun removeSharedTree(alias: String) {
        val current = _sharedTrees.value.filterNot { it.alias == alias }
        saveTrees(current)
        _sharedTrees.value = current
    }

    fun findTreeForPath(normalizedPath: String): Pair<SharedSafTree, List<String>>? {
        val segments = normalizedPath.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return null
        val rootSeg = segments.first()
        val tree = _sharedTrees.value.firstOrNull { it.alias == rootSeg } ?: return null
        return tree to segments.drop(1)
    }

    fun listSafDirectory(tree: SharedSafTree, subSegments: List<String>, fullNormalizedPath: String): Result<List<SafResolvedNode>> =
        runCatching {
            val treeUri = Uri.parse(tree.treeUriString)
            val targetDocId = resolveDocumentId(treeUri, subSegments)
                ?: throw IllegalArgumentException("Folder not found: $fullNormalizedPath")

            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, targetDocId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )

            val out = mutableListOf<SafResolvedNode>()
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (cursor.moveToNext()) {
                    val docId = if (idIdx >= 0) cursor.getString(idIdx) else continue
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else continue
                    if (name.isBlank()) continue
                    val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx).orEmpty() else "application/octet-stream"
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val size = if (!isDir && sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                    val mod = if (modIdx >= 0 && !cursor.isNull(modIdx)) cursor.getLong(modIdx) else System.currentTimeMillis()
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    val rel = "${fullNormalizedPath.trimEnd('/')}/$name"
                    out.add(
                        SafResolvedNode(
                            name = name,
                            relativePath = rel,
                            isDirectory = isDir,
                            sizeBytes = size,
                            lastModifiedEpochMs = mod,
                            mimeType = if (isDir) "inode/directory" else mime,
                            documentUri = docUri
                        )
                    )
                }
            }
            out.sortedWith(compareByDescending<SafResolvedNode> { it.isDirectory }.thenBy { it.name.lowercase() })
        }

    fun openSafFileForRead(tree: SharedSafTree, subSegments: List<String>): Result<Triple<String, Long, Pair<String, () -> InputStream?>>> =
        runCatching {
            require(subSegments.isNotEmpty()) { "Invalid file path." }
            val treeUri = Uri.parse(tree.treeUriString)
            val docId = resolveDocumentId(treeUri, subSegments)
                ?: throw IllegalArgumentException("File not found in shared folder.")
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            var size = 0L
            var name = subSegments.last()
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE),
                null,
                null,
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0) ?: name
                    size = if (!c.isNull(1)) c.getLong(1) else 0L
                }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var counted = 0L
            context.contentResolver.openInputStream(docUri)?.use { input ->
                val buf = ByteArray(16 * 1024)
                var r = input.read(buf)
                while (r > 0) {
                    digest.update(buf, 0, r)
                    counted += r
                    r = input.read(buf)
                }
            } ?: throw IllegalStateException("Cannot read SAF document.")
            val shaHex = CryptoIdentityManager.bytesToHex(digest.digest())
            val finalSize = if (size > 0L) size else counted
            Triple(name, finalSize, shaHex to { context.contentResolver.openInputStream(docUri) })
        }

    fun writeSafFile(tree: SharedSafTree, dirSegments: List<String>, safeFileName: String, bytes: ByteArray): Result<String> =
        runCatching {
            val treeUri = Uri.parse(tree.treeUriString)
            val parentDocId = resolveDocumentId(treeUri, dirSegments)
                ?: throw IllegalArgumentException("Target directory does not exist.")
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
            val createdUri = DocumentsContract.createDocument(
                context.contentResolver,
                parentUri,
                "application/octet-stream",
                safeFileName
            ) ?: throw IllegalStateException("Android SAF refused document creation in folder.")
            context.contentResolver.openOutputStream(createdUri)?.use { out ->
                out.write(bytes)
                out.flush()
            } ?: throw IllegalStateException("Cannot open created SAF file output stream.")
            CryptoIdentityManager.bytesToHex(CryptoIdentityManager.sha256(bytes))
        }

    private fun resolveDocumentId(treeUri: Uri, subSegments: List<String>): String? {
        var currentDocId = DocumentsContract.getTreeDocumentId(treeUri)
        for (segment in subSegments) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, currentDocId)
            var matchedId: String? = null
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null,
                null,
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val cid = c.getString(0) ?: continue
                    val cname = c.getString(1) ?: continue
                    if (cname == segment) {
                        matchedId = cid
                        break
                    }
                }
            }
            currentDocId = matchedId ?: return null
        }
        return currentDocId
    }

    private fun queryTreeDisplayName(treeUri: Uri): String {
        return runCatching {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            var label = ""
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { c ->
                if (c.moveToFirst()) label = c.getString(0).orEmpty()
            }
            label.ifBlank { docId.substringAfterLast(':').substringAfterLast('/') }
        }.getOrDefault("PhoneStorage")
    }

    private fun loadSavedTrees(): List<SharedSafTree> {
        val raw = prefs.getString(KEY_SAVED_TREES, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val alias = obj.optString("alias")
                    val uri = obj.optString("uri")
                    if (alias.isNotBlank() && uri.isNotBlank()) {
                        add(SharedSafTree(alias, uri))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveTrees(trees: List<SharedSafTree>) {
        val arr = JSONArray()
        trees.forEach { t ->
            arr.put(JSONObject().put("alias", t.alias).put("uri", t.treeUriString))
        }
        prefs.edit().putString(KEY_SAVED_TREES, arr.toString()).apply()
    }

    companion object {
        private const val KEY_SAVED_TREES = "shared_saf_trees_json"
    }
}
