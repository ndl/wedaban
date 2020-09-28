// Wedaban - implementation of Android Backup API for backups to WebDAV servers,
// see https://endl.ch/projects/wedaban
//
// Copyright (C) 2019 - 2020 Alexander Tsvyashchenko <android@endl.ch>
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.

package ch.endl.wedaban

import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.BackupTransport
import android.app.backup.RestoreDescription
import android.app.backup.RestoreSet

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo

import android.net.ConnectivityManager

import android.os.ParcelFileDescriptor
import android.os.SystemProperties

import android.util.Log

import com.thegrizzlylabs.sardineandroid.DavResource
import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import com.thegrizzlylabs.sardineandroid.util.SardineUtil
import java.io.*

import java.nio.channels.Pipe

import java.net.URI
import java.nio.channels.Channels

import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

import javax.xml.namespace.QName
import kotlin.collections.HashMap

import kotlin.concurrent.thread

/*
    Backup transport for storing the backups at remote WebDav location.

    The storage scheme is as follows:
    * Top URL for this account:
        * Sub-directories named as <device_id> with the following props set:
            * 'name': user-specified name for this backup set, <device_id> by default.
            * 'token': int token of this backup set (creation timestamp)
            * Sub-directories names as <package_name> with 'backupType' property to some of:
                * 'kv': if this is 'KV (V1) backup
                * 'full': if this is full (V2) backup
                * Files in these sub-directories of one of the following types:
                  * For large 'kv' backups: file name is base64-encoded property name, the content
                    is gzipped property value.
                  * For 'full' backups: file name is 'content', the content is gzipped full backup.

    Note: this assumes that there's always one 'token' per device which seems to be implicitly required in the API,
        otherwise both 'getCurrentRestoreSet()' and package-level backup interfaces don't really make any sense.
*/
class WedabanTransport(
        private val context: Context,
        private val params: WedabanTransportParameters,
        private val currentDeviceId: String,
        private val sardine: Sardine
        ) : BackupTransport() {
    private var fullBackupInputStream: InputStream? = null
    private var fullBackupInputDescriptor: ParcelFileDescriptor? = null
    private var fullBackupSource: InputStream? = null
    private var fullBackupOutputStream: OutputStream? = null
    private var fullBackupUploadThread: Thread? = null
    private var fullBackupSize: Long = 0
    private var fullBackupUploadError = AtomicBoolean(false)

    private var fullTargetPackage: String = ""
    private var fullTargetPackageDirUrl: String = ""
    private var fullTargetPackageDataUrl: String = ""
    private var fullBackupBuffer = ByteArray(0)

    private var restorePackages: Array<PackageInfo>? = null
    private var restorePackageIndex: Int = -1
    private var restoreType: Int = 0
    private var restoreDeviceId: String = ""

    private var fullRestoreInputStream: InputStream? = null
    private var fullRestoreOutputStream: OutputStream? = null
    private var fullRestoreBuffer = ByteArray(0)

    constructor(context: Context, params: WedabanTransportParameters):
            this(context, params, generateDeviceId(), OkHttpSardine())

    init {
        sardine.setCredentials(params.username, params.password, true)
    }

    private val logger = if (params.debug) AndroidLogger() else Logger()

    private inner class BackupUrlInfo {
        val deviceId: String
        val packageName: String?
        val fileName: String?

        constructor(packageName: String? = null,  deviceId: String? = null, fileName: String? = null) {
            if (packageName == null && fileName != null) {
                throw IllegalStateException(
                        "Cannot construct backup URL with fileName '$fileName' but without packageName")
            }
            this.deviceId = deviceId ?: currentDeviceId
            this.packageName = packageName
            this.fileName = fileName
        }

        constructor(url: URI) {
            if (url.path.startsWith(params.baseUrl.path)) {
                val subUrl = if (params.baseUrl.path.length < url.path.length)
                    url.path.substring(startIndex = params.baseUrl.path.length) else ""
                val parts = subUrl.trimStart('/').split("/")
                deviceId = if (parts.size > 0) parts[0] else currentDeviceId
                packageName = if (parts.size > 1) parts[1] else null
                fileName = if (parts.size > 2) parts[2] else null
            } else {
                throw IllegalStateException(
                        "Unexpected backup URL '$url': doesn't match the expected prefix '${params.baseUrl}'")
            }
        }

        val url: String
            get() {
                val path = arrayOf(params.baseUrl.path, deviceId, packageName, fileName)
                        .filter { !it.isNullOrEmpty() }
                        .joinToString("/")
                val uri = URI(params.baseUrl.scheme, params.baseUrl.host, path, null)
                return uri.toString()
            }
    }

    internal fun getRestoreSetFromResource(res: DavResource): RestoreSet? {
        val info = BackupUrlInfo(res.href)
        logger.info("Got the following props for ${res.href}: ${res.customProps}")
        if (BACKUP_NAME_PROP in res.customProps && BACKUP_TOKEN_PROP in res.customProps) {
            val name = res.customProps[BACKUP_NAME_PROP]
            val token = res.customProps[BACKUP_TOKEN_PROP]!!.toLongOrNull()
            if (name != null && token != null) {
                return RestoreSet(name, info.deviceId, token)
            }
        }
        logger.error("Required properties not found for '${res.href}', ignoring this item.")
        return null
    }

    override fun getAvailableRestoreSets(): Array<RestoreSet> {
        val url = params.baseUrl.toString()
        val resources = try {
            sardine.list(url, 1, propSetOf(BACKUP_NAME_PROP, BACKUP_TOKEN_PROP))
        } catch (ex: IOException) {
            logger.error("Couldn't list resources for '$url', the config is likely wrong: $ex")
            return ArrayList<RestoreSet>().toTypedArray()
        }

        var available = ArrayList<RestoreSet>()
        for (res in resources) {
            val rs = getRestoreSetFromResource(res)
            rs?.let { available.add(rs) }
        }
        // TODO: search for current token and if found - move it to the last position?
        // LocalTransport does this but is this important?
        return available.toTypedArray()
    }

    override fun getCurrentRestoreSet(): Long {
        // BackupUrlInfo() constructor with empty packageName and fileName will
        // construct baseUrl + deviceId which is exactly what we need.
        val url = BackupUrlInfo().url
        val resources = try {
            sardine.list(url, 0, propSetOf(BACKUP_NAME_PROP, BACKUP_TOKEN_PROP))
        } catch (ex: IOException) {
            logger.warn("Unable to get resources for '$url': $ex")
            return 0
        }

        for (res in resources) {
            val rs = getRestoreSetFromResource(res)
            rs?.let { return rs.token }
        }

        logger.warn("Couldn't find the current restore set, returning zero token")
        return 0
    }

    override fun name(): String {
        return ComponentName(context, this.javaClass).flattenToShortString()
    }

    override fun configurationIntent(): Intent? {
        // TODO: return intent for setting up the credentials, base URL and backup name
        return null
    }

    override fun currentDestinationString(): String = BackupUrlInfo().url

    override fun dataManagementIntent(): Intent? {
        // TODO: either implement UI for archieve management or at least redirect
        // to NextCloud app with the appropriate directory pre-opened?
        return null
    }

    override fun dataManagementLabel(): String = TRANSPORT_DATA_MANAGEMENT_LABEL

    override fun transportDirName(): String = ComponentName(context, this.javaClass).flattenToString()

    override fun requestBackupTime(): Long {
        val connMgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val isConnectedAndUnmetered = connMgr.activeNetworkInfo.isConnected && !connMgr.isActiveNetworkMetered
        return if (isConnectedAndUnmetered) 0 else BACKUP_RETRY_IF_METERED
    }

    override fun initializeDevice(): Int {
        val info = BackupUrlInfo()
        try {
            sardine.delete(info.url)
        } catch (ex: IOException) {
            try {
                if (sardine.exists(info.url)) {
                    logger.error("Failed to remove '${info.url}'")
                    return TRANSPORT_ERROR
                }
            } catch (ex2: IOException) {
                logger.error("Failed to check if '${info.url}' exists.")
                return TRANSPORT_ERROR
            }
        }
        try {
            prepareServerStorage(sardine, params.name, info.url, params.debug)
        } catch (ex: IOException) {
            logger.error("Failed to initialize device! $ex")
            return TRANSPORT_ERROR
        }
        return TRANSPORT_OK
    }

    // Encapsulation of a single k/v element change
    private data class KVOperation(val key: String, val value: ByteArray?)

    private fun getBackupProps(packageUrl: String): Map<String, String> {
        val propsNames = sardine.list(packageUrl, 0, false, true)
        if (propsNames.size != 1) {
            throw IllegalArgumentException("Unexpected response size for '$packageUrl': ${propsNames.size}")
        }
        logger.info("Got ${propsNames[0].customProps} props for ${packageUrl}")
        val backupPropsNames = HashSet(propsNames[0].customProps.keys.filter {
            it.startsWith(KEY_VALUE_BACKUP_PROP_PREFIX)
        }.map {
            SardineUtil.createQNameWithCustomNamespace(it)
        })
        logger.info("Remaining backup props: ${backupPropsNames}")
        val props = sardine.list(packageUrl, 0, backupPropsNames)
        if (props.size != 1) {
            throw IllegalArgumentException("Unexpected response size for '$packageUrl': ${props.size}")
        }
        return props[0].customProps
    }

    override fun performBackup(packageInfo: PackageInfo, data: ParcelFileDescriptor, flags: Int): Int {
        try {
            val isIncremental = (flags and FLAG_INCREMENTAL) != 0
            val isNonIncremental = (flags and FLAG_NON_INCREMENTAL) != 0

            val backupType = when {
                isIncremental -> "incremental "
                isNonIncremental -> "non-incremental "
                else -> ""
            }
            logger.info("Performing ${backupType}backup for ${packageInfo.packageName}")

            val packageUrl = BackupUrlInfo(packageName = packageInfo.packageName).url
            var hasDataForPackage = sardine.exists(packageUrl)
            if (isIncremental && !hasDataForPackage) {
                logger.warn(
                        "Requested incremental, but transport currently stores no data for the " +
                                "${packageInfo.packageName} package, requesting non-incremental retry."
                )
                return TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED
            }

            if (isNonIncremental && hasDataForPackage) {
                logger.warn("Requested non-incremental, deleting existing data")
                clearBackupData(packageInfo)
                hasDataForPackage = false
            }

            if (!hasDataForPackage) {
                sardine.createDirectory(packageUrl)
                sardine.patch(packageUrl, propMapOf(BACKUP_TYPE_PROP to BACKUP_TYPE_KV))
            }

            // Each 'record' in the restore set is kept in its own file, named by  the record key. Wind through the
            // data file, extracting individual  record operations and building a list of all the updates to apply
            // in this update.
            val changeOps = parseBackupStream(data)
            data.close()

            // Apply the delta operations (in the order that the app provided them).
            val props = getBackupProps(packageUrl)
            val propsToAdd = HashMap<String, String>()
            val propsToRemove = HashSet<String>()
            for (op in changeOps) {
                val kvUrl = BackupUrlInfo(packageName = packageInfo.packageName, fileName = op.key).url
                if (op.value == null) {
                    logger.verbose("Removing property ${op.key}")
                    val fullKeyName = "${KEY_VALUE_BACKUP_PROP_PREFIX}${op.key}"
                    if (fullKeyName in props) {
                        propsToRemove.add(fullKeyName)
                    } else {
                        try {
                            sardine.delete(kvUrl)
                        } catch (ex: IOException) {
                            logger.error("Couldn't delete backup property ${op.key}: " +
                                " not found in KV props and couldn't delete as file either: ${ex}")
                        }
                    }
                } else {
                    if (op.value.size <= BACKUP_PROP_MAX_SIZE) {
                        logger.verbose("Adding property ${op.key} with size ${op.value.size}")
                        propsToAdd[KEY_VALUE_BACKUP_PROP_PREFIX + op.key] = base64EncodeToString(op.value)
                    } else {
                        // We assume that values sizes are modest, so no need to mess with pipes /
                        // threads - and we store them as strings already anyway ...
                        val buffer = okio.Buffer()
                        DataOutputStream(GZIPOutputStream(buffer.outputStream())).use {
                            it.writeInt(op.value.size)
                            it.write(op.value, 0, op.value.size)
                        }
                        logger.info("Writing file-based property '${op.key}' of size ${op.value.size}")
                        sardine.put(kvUrl, buffer.readByteArray())
                    }
                }
            }
            if (propsToAdd.isNotEmpty() || propsToRemove.isNotEmpty()) {
                sardine.patch(
                        packageUrl,
                        propsToAdd.mapKeys { SardineUtil.createQNameWithCustomNamespace(it.key) },
                        propsToRemove.map { SardineUtil.createQNameWithCustomNamespace(it) })
            }
            return TRANSPORT_OK
        } catch (ex: IOException) {
            logger.error("IO error during KV backup: $ex")
            data.closeWithError(ex.toString())
            return TRANSPORT_ERROR
        }
    }

    // Parses a backup stream into individual key/value operations
    @Throws(IOException::class)
    // TODO: clean up the usage of Array / ArrayList throughout this file.
    private fun parseBackupStream(data: ParcelFileDescriptor): ArrayList<KVOperation> {
        val changeOps = ArrayList<KVOperation>()
        val changeSet = BackupDataInput(data.getFileDescriptor())
        while (changeSet.readNextHeader()) {
            val key = changeSet.getKey()
            val base64Key = base64EncodeToString(key)
            val dataSize = changeSet.getDataSize()
            if (DEBUG) {
                logger.verbose("Delta operation with key = $key, size = $dataSize, key64 = $base64Key")
            }
            val buf = if (dataSize >= 0) ByteArray(dataSize) else null
            if (dataSize >= 0) {
                changeSet.readEntityData(buf, 0, dataSize)
            }
            changeOps.add(KVOperation(base64Key, buf))
        }
        return changeOps
    }

    override fun clearBackupData(packageInfo: PackageInfo): Int {
        if (DEBUG) logger.verbose("clearBackupData() pkg = ${packageInfo.packageName}")
        val packageUrl = BackupUrlInfo(packageName = packageInfo.packageName).url
        try {
            sardine.delete(packageUrl)
        } catch (ex: IOException) {
            logger.error("Failed to delete URL '$packageUrl': $ex")
            return TRANSPORT_ERROR
        }
        return TRANSPORT_OK
    }

    override fun finishBackup(): Int {
        if (DEBUG) logger.verbose("finishBackup())")
        if (fullBackupInputStream != null) {
            tearDownFullBackup()
            if (fullBackupUploadError.get()) {
                cancelFullBackup()
                return TRANSPORT_ERROR
            }
        } else {
            logger.info("finishBackup() called not after performing full backup, ignoring.")
        }
        return if (fullBackupUploadError.get()) TRANSPORT_ERROR else TRANSPORT_OK
    }

    private fun tearDownFullBackup() {
        if (DEBUG) logger.verbose("tearing down packup for $fullTargetPackage")
        fullBackupInputStream?.safeClose()
        fullBackupInputStream = null
        fullBackupOutputStream?.safeClose()
        fullBackupOutputStream = null
        fullBackupInputDescriptor = null
        fullBackupUploadThread?.join()
        fullBackupUploadThread = null
    }

    override fun requestFullBackupTime(): Long = requestBackupTime()

    override fun performFullBackup(targetPackage: PackageInfo, socket: ParcelFileDescriptor): Int {
        if (fullBackupInputStream != null) {
            logger.error("Attempt to initiate full backup while one is in progress")
            return TRANSPORT_ERROR
        }

        if (DEBUG)
            logger.info("performFullBackup: $targetPackage")

        try {
            fullBackupSize = 0
            // Make sure we have our own socket fd so that when backup agent closes its descriptor -
            // we still can access it. However, we need to store the descriptor itself because
            // otherwise it might be garbage collected, closing the file along the way.
            fullBackupInputDescriptor = socket.dup()
            fullBackupInputStream = FileInputStream(fullBackupInputDescriptor!!.getFileDescriptor())
        } catch (e: IOException) {
            logger.error("Unable to process socket for full backup")
            return TRANSPORT_ERROR
        }

        fullTargetPackage = targetPackage.packageName
        fullTargetPackageDirUrl = BackupUrlInfo(packageName = targetPackage.packageName).url
        fullTargetPackageDataUrl = BackupUrlInfo(
                packageName = targetPackage.packageName, fileName = FULL_BACKUP_RESOURCE_NAME).url
        fullBackupBuffer = ByteArray(BACKUP_BUFFER_SIZE)
        return TRANSPORT_OK
    }

    override fun sendBackupData(numBytes: Int): Int {
        if (fullBackupInputStream == null) {
            logger.error("Attempted sendBackupData before performFullBackup")
            return TRANSPORT_ERROR
        }

        if (fullBackupUploadError.get()) {
            logger.error("Upload failed while performing full backup, canceling")
            cancelFullBackup()
            return TRANSPORT_ERROR
        }

        fullBackupSize += numBytes.toLong()
        if (fullBackupSize > FULL_BACKUP_SIZE_QUOTA) {
            logger.error("Full backup quota exceeded for package $fullTargetPackage, canceling backup")
            cancelFullBackup()
            return TRANSPORT_QUOTA_EXCEEDED
        }

        if (numBytes > fullBackupBuffer.size) {
            fullBackupBuffer = ByteArray(numBytes)
        }

        if (fullBackupOutputStream == null) {
            // TODO: remove unnecessary logging!
            logger.verbose("Creating piped streams")
            val fullBackupPipe = Pipe.open()
            fullBackupSource = BufferedInputStream(Channels.newInputStream(fullBackupPipe.source()))
            fullBackupSource!!.mark(STREAM_MAX_BUFFER)
            fullBackupOutputStream = GZIPOutputStream(Channels.newOutputStream(fullBackupPipe.sink()))
            logger.verbose("Starting sending thread")
            fullBackupUploadError.set(false)
            fullBackupUploadThread = thread {
                try {
                    logger.verbose("Inside sending thread")
                    logger.verbose("Creating directory ${fullTargetPackageDirUrl}")
                    if (!sardine.exists(fullTargetPackageDirUrl)) {
                        // No need to create it recursively - should be handled by server
                        // storage preparation code already.
                        sardine.createDirectory(fullTargetPackageDirUrl)
                    }
                    logger.verbose("Setting properties")
                    sardine.patch(fullTargetPackageDirUrl, propMapOf(BACKUP_TYPE_PROP to BACKUP_TYPE_FULL))
                    logger.verbose("Uploading the data")
                    // This will overwrite the file if it exists already, which we're fine with.
                    sardine.put(fullTargetPackageDataUrl, fullBackupSource)
                    logger.verbose("Finished data upload")
                } catch (ex: IOException) {
                    logger.error("Failed to upload full backup data to $fullTargetPackageDirUrl: $ex")
                    fullBackupUploadError.set(true)
                } catch (ex: Exception) {
                    logger.error("Unexpected exception while uploading to $fullTargetPackageDirUrl: $ex")
                    fullBackupUploadError.set(true)
                }
                logger.verbose("Finishing sending thread")
                fullBackupSource?.safeClose()
                fullBackupSource = null
                logger.verbose("Exiting sending thread")
            }
        }

        var bytesLeft = numBytes
        while (bytesLeft > 0) {
            logger.verbose("${bytesLeft} bytes to send out of ${numBytes}")
            try {
                logger.verbose("Reading data from backup manager")
                val nRead = fullBackupInputStream!!.read(fullBackupBuffer, 0, bytesLeft)
                logger.verbose("Got ${nRead} bytes")
                if (nRead < 0) {
                    // Something went wrong if we expect data but saw EOD
                    logger.warn("Unexpected EOD; failing backup")
                    cancelFullBackup()
                    return TRANSPORT_ERROR
                }
                logger.verbose("Writing ${nRead} bytes to sending thread")
                fullBackupOutputStream!!.write(fullBackupBuffer, 0, nRead)
                logger.verbose("Done writing bytes to sending thread")
                bytesLeft -= nRead
            } catch (ex: IOException) {
                logger.error("Error handling backup data for $fullTargetPackage: $ex")
                cancelFullBackup()
                return TRANSPORT_ERROR
            } catch (ex: Exception) {
                logger.error("Unexpected generic exception: ${ex}")
                cancelFullBackup()
                return TRANSPORT_ERROR
            }
            logger.verbose("Finished backup manager data dispatching for this batch")
        }
        if (DEBUG) {
            logger.verbose("Stored $numBytes bytes of data")
        }
        logger.verbose("Exiting sendBackupData")
        return TRANSPORT_OK
    }

    override fun cancelFullBackup() {
        if (DEBUG) {
            logger.info("Canceling full backup of $fullTargetPackage")
        }
        if (fullBackupInputStream != null) {
            tearDownFullBackup()
            // Remove the content because we can't be sure it's in valid state.
            try {
                sardine.delete(fullTargetPackageDataUrl)
            } catch (ex: IOException) {
                logger.error("Failed to remove $fullTargetPackageDataUrl: $ex")
            }
        }
    }

    override fun startRestore(token: Long, packages: Array<PackageInfo>): Int {
        if (restorePackages != null) {
            throw IllegalStateException("startRestore() called while " +
                    "restore is already in progress!")
        }

        if (DEBUG)
            logger.verbose(("Start restore for token $token: ${packages.size} matching packages"))
        restorePackages = packages
        restorePackageIndex = -1

        // Convert token to deviceId
        val url = params.baseUrl.toString()
        try {
            val resources = sardine.list(url, 1, propSetOf(BACKUP_TOKEN_PROP))
            for (res in resources) {
                logger.verbose("Considering resource '${res.href}' with props ${res.customProps}")
                if (BACKUP_TOKEN_PROP in res.customProps) {
                    if (res.customProps[BACKUP_TOKEN_PROP]?.toLongOrNull() == token) {
                        restoreDeviceId = BackupUrlInfo(res.href).deviceId
                        logger.verbose("Reconstructed device ID: '${restoreDeviceId}'")
                        return TRANSPORT_OK
                    }
                }
            }
        } catch (ex: IOException) {
            logger.error("Failed to get props at '$url': $ex")
            return TRANSPORT_ERROR
        } catch (ex: Exception) {
            logger.error("Failed to parse custom props at '$url': $ex")
        }

        logger.warn("Couldn't reconstruct device ID for token ${token}, failing restore")
        return TRANSPORT_ERROR
    }

    override fun nextRestorePackage(): RestoreDescription {
        if (restorePackages == null)
            throw IllegalStateException("startRestore not called")

        packagesLoop@ while (++restorePackageIndex < restorePackages!!.size) {
            val name = restorePackages!![restorePackageIndex].packageName
            val packageUrl = BackupUrlInfo(packageName = name, deviceId = restoreDeviceId).url
            try {
                val resources = sardine.list(packageUrl, 0, propSetOf(BACKUP_TYPE_PROP))
                for (res in resources) {
                    if (BACKUP_TYPE_PROP in res.customProps) {
                        when (res.customProps[BACKUP_TYPE_PROP]) {
                            BACKUP_TYPE_KV -> {
                                restoreType = RestoreDescription.TYPE_KEY_VALUE
                                return RestoreDescription(name, restoreType)
                            }
                            BACKUP_TYPE_FULL -> {
                                restoreType = RestoreDescription.TYPE_FULL_STREAM
                                return RestoreDescription(name, restoreType)
                            }
                            else -> {
                                logger.error("No expected props for '$packageUrl', skipping")
                                continue@packagesLoop
                            }
                        }
                    } else {
                        logger.warn("'$packageUrl' doesn't have 'backupType' property, ignoring")
                    }
                }
            } catch (ex: IOException) {
                logger.error("Failed to get restore props at '$packageUrl': $ex")
                return RestoreDescription.NO_MORE_PACKAGES
            } catch (ex: Exception) {
                logger.error("Failed to parse props at '$packageUrl': $ex")
                return RestoreDescription.NO_MORE_PACKAGES
            }
        }

        return RestoreDescription.NO_MORE_PACKAGES
    }

    override fun getRestoreData(outFd: ParcelFileDescriptor): Int {
        if (restorePackages == null)
            throw IllegalStateException("startRestore not called")

        if (restorePackageIndex < 0)
            throw IllegalStateException("nextRestorePackage not called")

        if (restoreType != RestoreDescription.TYPE_KEY_VALUE)
            throw IllegalStateException("Requested KV restore for full backup")

        val name = restorePackages!![restorePackageIndex].packageName
        val packageUrl = BackupUrlInfo(packageName = name, deviceId = restoreDeviceId).url
        try {
            val resources = sardine.list(packageUrl, 1).filter { !it.isDirectory }
            val props = getBackupProps(packageUrl)
            logger.info("Retrieved resources ${resources}, and props ${props}")
            val entries = contentsByKey(resources, props)
            val out = BackupDataOutput(outFd.getFileDescriptor())
            for (entry in entries) {
                logger.info("Handling entry ${entry}")
                if (entry.resource != null) {
                    // TODO: fix once BackupUrlInfo returns URI!
                    val uri = URI(packageUrl).resolve(entry.resource.href)
                    logger.info("Restoring file-based entry '${entry.decodedKey}' from '${uri}'")
                    DataInputStream(GZIPInputStream(sardine.get(uri.toString()))).use {
                        val expectedSize = it.readInt()
                        var actualSize = 0
                        out.writeEntityHeader(entry.decodedKey, expectedSize)
                        val buf = ByteArray(BACKUP_BUFFER_SIZE)
                        read@do {
                            val size = it.read(buf)
                            if (size <= 0) break@read
                            actualSize += size
                            out.writeEntityData(buf, size)
                        } while (true)
                        logger.info("expectedSize = ${expectedSize}, actualSize = ${actualSize}")
                    }
                } else {
                    logger.info("Restoring prop-based entry '${entry.encodedKey}' " +
                            "with value '${props[entry.encodedKey]}'")
                    val value = base64DecodeToBytes(props[entry.encodedKey]!!)
                    out.writeEntityHeader(entry.decodedKey, value.size)
                    out.writeEntityData(value, value.size)
                }
            }
            return TRANSPORT_OK
        } catch (ex: IOException) {
            logger.error("Unable to read backup records for '$packageUrl': $ex")
            return TRANSPORT_ERROR
        }  catch (ex: Exception) {
            logger.error("Unknown exception in getRestoreData, canceling restore. $ex")
            return TRANSPORT_ERROR
        }
    }

    internal inner class DecodedKey: Comparable<DecodedKey> {
        val resource: DavResource?
        val encodedKey: String
        val decodedKey: String
        constructor(resource: DavResource) {
            logger.info("Constructing DecodedKey with resource arg '${resource.href}'")
            this.resource = resource
            this.encodedKey = resource.href.path.split('/').last()
            logger.info("Converted URL '${resource.href}' to '${this.encodedKey}'")
            this.decodedKey = base64DecodeToString(this.encodedKey)
            logger.info("Mapped encoded key '${this.encodedKey}' to '${this.decodedKey}'")
        }
        constructor(key: String) {
            logger.info("Constructing DecodedKey with string arg '${key}'")
            this.resource = null
            this.encodedKey = key
            this.decodedKey = base64DecodeToString(key.substring(KEY_VALUE_BACKUP_PROP_PREFIX.length))
        }
        public override fun compareTo(other: DecodedKey): Int {
            // Sorts in the ascending lexical order by decoded key.
            return decodedKey.compareTo(other.decodedKey)
        }
    }

    // Return a list of the keys sorted lexically by the Base64-decoded key.
    private fun contentsByKey(resources: List<DavResource>, props: Map<String, String>): ArrayList<DecodedKey> {
        val contents = ArrayList<DecodedKey>()
        // Handle file resources, if any.
        for (res in resources) {
            contents.add(DecodedKey(res))
        }
        // Handle props, if any.
        for (prop in props) {
            if (prop.key.startsWith(KEY_VALUE_BACKUP_PROP_PREFIX)) {
                logger.info("Attempting to decode property ${prop.key}")
                contents.add(DecodedKey(prop.key))
            }
        }
        Collections.sort<DecodedKey>(contents)
        return contents
    }

    override fun finishRestore() {
        if (restorePackages == null) {
            throw IllegalStateException("finishRestore() called without startRestore()!")
        }

        if (DEBUG)
            logger.verbose("finishRestore()")
        if (restoreType == RestoreDescription.TYPE_FULL_STREAM) {
            resetFullRestoreState()
        }
        restoreType = 0
        restorePackages = null
        restoreDeviceId = ""
    }

    private fun resetFullRestoreState() {
        fullRestoreInputStream?.safeClose()
        fullRestoreInputStream = null
        fullRestoreOutputStream?.safeClose()
        fullRestoreOutputStream = null
        fullRestoreBuffer = ByteArray(0)
    }

    override fun getNextFullRestoreDataChunk(socket: ParcelFileDescriptor): Int {
        if (restoreType != RestoreDescription.TYPE_FULL_STREAM) {
            throw IllegalStateException("Asked for full restore data for non-stream package")
        }

        // first chunk?
        if (fullRestoreInputStream == null) {
            val name = restorePackages!![restorePackageIndex].packageName
            if (DEBUG)
                logger.info("Starting full restore of $name")
            val contentUrl = currentDestinationString() + "/" + name + "/content"
            try {
                fullRestoreInputStream = GZIPInputStream(sardine.get(contentUrl))
            } catch (ex: IOException) {
                logger.error("Unable to read archive for $name: $ex")
                return TRANSPORT_ERROR
            }
            fullRestoreOutputStream = FileOutputStream(socket.getFileDescriptor())
            fullRestoreBuffer = ByteArray(BACKUP_BUFFER_SIZE)
        }

        var nRead: Int
        try {
            nRead = fullRestoreInputStream!!.read(fullRestoreBuffer)
            logger.info("nRead = ${nRead}")
            if (nRead <= 0) {
                nRead = NO_MORE_DATA
            } else {
                if (DEBUG)
                    logger.info(" delivering restore chunk: $nRead")
                fullRestoreOutputStream!!.write(fullRestoreBuffer, 0, nRead)
            }
        } catch (ex: IOException) {
            logger.error("Full restore chunk reading error: $ex")
            return TRANSPORT_ERROR
        }

        return nRead
    }

    override fun abortFullRestore(): Int {
        if (restoreType != RestoreDescription.TYPE_FULL_STREAM)
            throw IllegalStateException("abortFullRestore() but not currently restoring")
        resetFullRestoreState()
        restoreType = 0
        return TRANSPORT_OK
    }

    override fun getBackupQuota(packageName: String, isFullBackup: Boolean): Long {
        return if (isFullBackup) FULL_BACKUP_SIZE_QUOTA else KEY_VALUE_BACKUP_SIZE_QUOTA
    }

    companion object {
        private open class Logger {
            open fun debug(msg: String) {}
            open fun verbose(msg: String) {}
            open fun info(msg: String) {}
            open fun warn(msg: String) {}
            open fun error(msg: String) {}
        }

        private class AndroidLogger: Logger() {
            override fun debug(msg: String) { Log.d(TAG, msg) }
            override fun verbose(msg: String) { Log.v(TAG, msg) }
            override fun info(msg: String) { Log.i(TAG, msg) }
            override fun warn(msg: String) { Log.w(TAG, msg) }
            override fun error(msg: String) { Log.e(TAG, msg) }
        }

        fun Closeable.safeClose() = try { this.close() } catch (ex: IOException) { }

        internal fun generateDeviceId() = arrayOf(
                "ro.product.model",
                "ro.product.name",
                "ro.product.vendor.model",
                "ro.product.vendor.name",
                "ro.serialno")
                .map { SystemProperties.get(it) }
                .filter { !it.isNullOrEmpty() }
                .joinToString(" | ")

        // Tests cannot use this function if it is marked as 'internal', possibly relevant bug is
        // https://youtrack.jetbrains.com/issue/KT-34944
        fun propSetOf(vararg names: String): Set<QName> =
                HashSet( names.map { SardineUtil.createQNameWithCustomNamespace(it) })

        // Tests cannot use this function if it is marked as 'internal', possibly relevant bug is
        // https://youtrack.jetbrains.com/issue/KT-34944
        fun propMapOf(vararg pairs: Pair<String, String>) =
                pairs.map { Pair(SardineUtil.createQNameWithCustomNamespace(it.first), it.second) }.toMap()

        internal fun base64EncodeToString(value: ByteArray) =
                Base64.getUrlEncoder().withoutPadding().encodeToString(value)

        internal fun base64EncodeToString(value: String) = base64EncodeToString(value.toByteArray())

        internal fun base64DecodeToBytes(value: String) = Base64.getUrlDecoder().decode(value)

        internal fun base64DecodeToString(value: String) = String(base64DecodeToBytes(value))

        internal fun prepareServerStorage(sardine: Sardine, name: String, url: String, debug: Boolean) {
            val logger = if (debug) AndroidLogger() else Logger()
            logger.info("Preparing server storage for '${url}'")
            // We proceed in two passes: first we walk up the URL components until we find the first
            // prefix that does exist and then in the second pass we go back, creating sub-directories
            // one by one. Note that seemingly simpler method (start with root, add sub-directories
            // one by one, check their existence and create if needed) won't work as not every
            // URL prefix is necessarily a valid WebDAV URL, e.g. in http://example.com/webdav.php/Backups
            // it's wrong to start checking for WebDAV directories from http://example.com.
            val uri = URI(url)
            val comps = uri.path.split('/').filter { it.isNotEmpty() }
            var toIndex = comps.size
            while (toIndex > 0) {
                val curUrl = URI(
                        uri.scheme,
                        uri.host,
                        "/" + comps.subList(0, toIndex).joinToString("/"),
                        null
                ).toString()
                if (sardine.exists(curUrl))
                    break
                toIndex -= 1
            }
            if (toIndex == 0) {
                throw IOException("Cannot find any existing prefix for '${url}'!")
            }

            // Create all the necessary directories recursively.
            while (toIndex < comps.size) {
                val curUrl = URI(
                        uri.scheme,
                        uri.host,
                        "/" + comps.subList(0, ++toIndex).joinToString("/"),
                        null
                ).toString()
                sardine.createDirectory(curUrl)
            }

            // Make sure we have the required properties set.
            try {
                val resources = sardine.list(url, 0, propSetOf(BACKUP_NAME_PROP, BACKUP_TOKEN_PROP))
                if (resources.size != 1 ||
                    BACKUP_NAME_PROP !in resources[0].customProps ||
                    BACKUP_TOKEN_PROP !in resources[0].customProps ||
                    resources[0].customProps[BACKUP_NAME_PROP].isNullOrEmpty() ||
                    resources[0].customProps[BACKUP_TOKEN_PROP]!!.toLongOrNull() == null
                ) {
                    throw IOException("Failed to find the expected properties on '${url}'")
                }
            } catch (ex: IOException){
                logger.info("Creating properties for '${url}'")
                sardine.patch(
                        url,
                        propMapOf(
                                BACKUP_NAME_PROP to name,
                                BACKUP_TOKEN_PROP to System.currentTimeMillis().toString())
                )
            }
        }

        private const val TAG = "WedabanTransport"
        private const val DEBUG = true
        private const val TRANSPORT_DATA_MANAGEMENT_LABEL = ""
        private const val BACKUP_TYPE_PROP = "backupType"
        private const val BACKUP_NAME_PROP = "name"
        private const val BACKUP_TOKEN_PROP = "token"
        private const val BACKUP_TYPE_KV = "kv"
        private const val BACKUP_TYPE_FULL = "full"
        private const val BACKUP_RETRY_IF_METERED = 3600.toLong()
        private const val KEY_VALUE_BACKUP_SIZE_QUOTA = (32 * 1024 * 1024).toLong()
        private const val FULL_BACKUP_RESOURCE_NAME = "content"
        private const val KEY_VALUE_BACKUP_PROP_PREFIX = "KV_"
        private const val FULL_BACKUP_SIZE_QUOTA = (256 * 1024 * 1024).toLong()
        private const val BACKUP_BUFFER_SIZE = 8 * 1024
        private const val BACKUP_PROP_MAX_SIZE = 1024
        private const val STREAM_MAX_BUFFER = 2 * BACKUP_BUFFER_SIZE
    }
}
