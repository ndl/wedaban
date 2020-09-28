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

import android.app.backup.*
import android.app.backup.BackupTransport.*
import android.content.Context
import android.content.pm.PackageInfo
import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import assertk.assertThat
import assertk.assertions.*
import ch.endl.wedaban.WedabanTransport.Companion.propMapOf
import ch.endl.wedaban.WedabanTransport.Companion.propSetOf
import com.thegrizzlylabs.sardineandroid.DavResource
import io.mockk.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

import org.junit.runner.RunWith
import org.junit.Test as TestJ4
import org.robolectric.RobolectricTestRunner
import java.io.*
import java.lang.IllegalStateException
import java.lang.reflect.*
import javax.xml.namespace.QName
import java.util.zip.GZIPOutputStream
import kotlin.reflect.KClass


class WedabanTransportUnitTest: WedabanTransportUnitTestCommon() {
    protected fun setupEmptyListResponse() {
        every {
            sardine.list(TEST_URL, 1, propSetOf("name", "token"))
        } returns listOf<DavResource>()
    }

    @Test
    fun `getAvailableRestoreSets() fails with empty resources`() {
        setupEmptyListResponse()
        val restoreSets = transport.availableRestoreSets
        assertThat(restoreSets).isEmpty()
    }

    protected fun setupMixedListResponse() {
        every {
            sardine.list(TEST_URL, 1, propSetOf("name", "token"))
        } returns listOf<DavResource>(
                DavResource.Builder("/webdav/url-with-no-props")
                        .build(),
                DavResource.Builder("/webdav/url-with-empty-props")
                        .withCustomProps(propMapOf("name" to "", "token" to ""))
                        .build(),
                DavResource.Builder("/webdav/$TEST_DEVICE_ID")
                        .withCustomProps(propMapOf("name" to TEST_BACKUP_NAME, "token" to "${TEST_TOKEN}"))
                        .build()
        )
    }

    @Test
    fun `getAvailableRestoreSets() succeeds with correct resources`() {
        setupMixedListResponse()
        val restoreSets = transport.availableRestoreSets
        assertThat(restoreSets).isEqualToWithGivenProperties(arrayOf(
                RestoreSet(TEST_BACKUP_NAME, TEST_DEVICE_ID, TEST_TOKEN),
                RestoreSet::name, RestoreSet::device, RestoreSet::token))
    }

    private fun setupEmptyDeviceIdListResponse() {
        every {
            sardine.list(TEST_URL_WITH_DEVICE_ID, 0, propSetOf("name", "token"))
        } returns listOf<DavResource>()
    }

    @Test
    fun `getCurrentRestoreSet fails with empty resource`() {
        setupEmptyDeviceIdListResponse()
        assertThat(transport.currentRestoreSet).isEqualTo(0L)
    }

    @Test
    fun `configurationIntent() returns null`() {
        assertThat(transport.configurationIntent()).isNull()
    }

    @Test
    fun `currentDestinationString() returns expected URL`() {
        assertThat(transport.currentDestinationString()).isEqualTo(
                "http://example.com/webdav/TestDeviceId")
    }

    @Test
    fun `dataManagementIntent() returns null`() {
        assertThat(transport.dataManagementIntent()).isNull()
    }

    @Test
    fun `dataManagementLabel() returns expected value`() {
        assertThat(transport.dataManagementLabel()).isEqualTo("")
    }

    private fun setupUnmeteredWifi() {
        val connMgr = mockk<ConnectivityManager>()
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connMgr
        every { connMgr.activeNetworkInfo.isConnected } returns true
        every { connMgr.isActiveNetworkMetered } returns false
    }

    @Test
    fun `requestBackupTime() returns 0 on unmetered WiFi`() {
        setupUnmeteredWifi()
        assertThat(transport.requestBackupTime()).isEqualTo(0L)
    }

    private fun setupMeteredWifi() {
        val connMgr = mockk<ConnectivityManager>()
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connMgr
        every { connMgr.activeNetworkInfo.isConnected } returns true
        every { connMgr.isActiveNetworkMetered } returns true
    }

    @Test
    fun `requestBackupTime() returns non-zero on metered WiFi`() {
        setupMeteredWifi()
        assertThat(transport.requestBackupTime()).isGreaterThan(0L)
    }

    protected fun setupTestDeviceIdEmptyPropsListResponse() {
        every {
            sardine.list(TEST_URL_WITH_DEVICE_ID, 0, propSetOf("name", "token"))
        } returns listOf<DavResource>(
                DavResource.Builder("/webdav/$TEST_DEVICE_ID").build()
        )
    }

    @Test
    fun `initializeDevice() removes old dir, creates new one and sets props`() {
        val propMatch = { it: HashMap<QName, String> ->
            it[QName("SAR:", "name")] == TEST_BACKUP_NAME &&
                    it[QName("SAR:", "token")]!!.toLong() > 0
        }
        every { sardine.delete(TEST_URL_WITH_DEVICE_ID) } returns Unit
        every { sardine.exists(TEST_URL_WITH_DEVICE_ID) } returns false
        every { sardine.exists(TEST_URL) } returns true
        every { sardine.createDirectory(TEST_URL_WITH_DEVICE_ID) } returns Unit
        every { sardine.patch(TEST_URL_WITH_DEVICE_ID, match(propMatch)) } returns listOf<DavResource>()
        setupTestDeviceIdEmptyPropsListResponse()
        assertThat(transport.initializeDevice()).isEqualTo(TRANSPORT_OK)
        assertThat(transport.finishBackup()).isEqualTo(TRANSPORT_OK)
        verify(exactly = 1) { sardine.delete(TEST_URL_WITH_DEVICE_ID) }
        verify(exactly = 1) { sardine.createDirectory(TEST_URL_WITH_DEVICE_ID) }
        verify(exactly = 1) { sardine.patch(TEST_URL_WITH_DEVICE_ID, match(propMatch)) }
    }

    @Test
    fun `initializeDevice() fails if delete() and exists() fail`() {
        every { sardine.delete(TEST_URL_WITH_DEVICE_ID) } throws IOException()
        every { sardine.exists(TEST_URL_WITH_DEVICE_ID) } throws IOException()
        assertThat(transport.initializeDevice()).isEqualTo(TRANSPORT_ERROR)
    }

    @Test
    fun `initializeDevice() fails if exists() fails`() {
        every { sardine.delete(TEST_URL_WITH_DEVICE_ID) } returns Unit
        every { sardine.exists(TEST_URL_WITH_DEVICE_ID) } throws IOException()
        assertThat(transport.initializeDevice()).isEqualTo(TRANSPORT_ERROR)
    }

    @Test
    fun `initializeDevice() succeeds if delete() fails but directory doesn't exist`() {
        every { sardine.delete(TEST_URL_WITH_DEVICE_ID) } throws IOException()
        every { sardine.exists(TEST_URL_WITH_DEVICE_ID) } returns false
        every { sardine.exists(TEST_URL) } returns true
        every { sardine.createDirectory(TEST_URL_WITH_DEVICE_ID) } returns Unit
        every { sardine.patch(TEST_URL_WITH_DEVICE_ID, any()) } returns listOf<DavResource>()
        setupTestDeviceIdEmptyPropsListResponse()
        assertThat(transport.initializeDevice()).isEqualTo(TRANSPORT_OK)
        assertThat(transport.finishBackup()).isEqualTo(TRANSPORT_OK)
    }

    @Test
    fun `initializeDevice() fails if createDirectory() fails`() {
        every { sardine.delete(TEST_URL_WITH_DEVICE_ID) } returns Unit
        every { sardine.exists(TEST_URL_WITH_DEVICE_ID) } returns false
        every { sardine.exists(TEST_URL) } returns true
        every { sardine.createDirectory(TEST_URL_WITH_DEVICE_ID) } throws IOException()
        assertThat(transport.initializeDevice()).isEqualTo(TRANSPORT_ERROR)
    }

    @Test
    fun `initializeDevice() fails if patch() fails`() {
        every { sardine.delete(TEST_URL_WITH_DEVICE_ID) } returns Unit
        every { sardine.exists(TEST_URL_WITH_DEVICE_ID) } returns false
        every { sardine.exists(TEST_URL) } returns true
        every { sardine.createDirectory(TEST_URL_WITH_DEVICE_ID) } returns Unit
        setupTestDeviceIdEmptyPropsListResponse()
        every { sardine.patch(TEST_URL_WITH_DEVICE_ID, any()) } throws IOException()
        assertThat(transport.initializeDevice()).isEqualTo(TRANSPORT_ERROR)
    }

    @Test
    fun `Incremental performBackup() fails when exists() fails`() {
        val packageInfo: PackageInfo = mockk()
        packageInfo.packageName = TEST_PACKAGE_NAME
        val parcelFd = mockk<ParcelFileDescriptor>()
        every { sardine.exists(TEST_PACKAGE_URL) } throws IOException()
        every { parcelFd.closeWithError(any()) } returns Unit
        assertThat(transport.performBackup(
                packageInfo,
                parcelFd,
                BackupTransport.FLAG_NON_INCREMENTAL))
                .isEqualTo(BackupTransport.TRANSPORT_ERROR)
    }

    @Test
    fun `Non-incremental performBackup() removes data for existing URL`() {
        val packageInfo: PackageInfo = mockk()
        packageInfo.packageName = TEST_PACKAGE_NAME
        val parcelFd = mockk<ParcelFileDescriptor>()
        every { sardine.exists(TEST_PACKAGE_URL) } returns true
        every { sardine.delete(TEST_PACKAGE_URL) } returns Unit
        every { sardine.createDirectory(TEST_PACKAGE_URL) } throws IOException()
        every { parcelFd.closeWithError(any()) } returns Unit
        assertThat(transport.performBackup(
                packageInfo,
                parcelFd,
                BackupTransport.FLAG_NON_INCREMENTAL))
                .isEqualTo(BackupTransport.TRANSPORT_ERROR)
        verify(exactly = 1) { sardine.delete(TEST_PACKAGE_URL) }
        verify(exactly = 1) { sardine.createDirectory(TEST_PACKAGE_URL) }
    }

    @Test
    fun `Incremental performBackup() fails for missing URL`() {
        val packageInfo: PackageInfo = mockk()
        packageInfo.packageName = TEST_PACKAGE_NAME
        every { sardine.exists(TEST_PACKAGE_URL) } returns false
        assertThat(transport.performBackup(
                packageInfo,
                mockk(),
                BackupTransport.FLAG_INCREMENTAL))
                .isEqualTo(
                        BackupTransport.TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED)
    }

    @Test
    fun `clearBackupData() succeeds for existing URL`() {
        val packageInfo: PackageInfo = mockk()
        packageInfo.packageName = TEST_PACKAGE_NAME
        every { sardine.delete(TEST_PACKAGE_URL) } returns Unit
        assertThat(transport.clearBackupData(packageInfo)).isEqualTo(TRANSPORT_OK)
    }

    @Test
    fun `clearBackupData() fails when delete() fails`() {
        val packageInfo: PackageInfo = mockk()
        packageInfo.packageName = TEST_PACKAGE_NAME
        every { sardine.delete(TEST_PACKAGE_URL) } throws IOException()
        assertThat(transport.clearBackupData(packageInfo)).isEqualTo(TRANSPORT_ERROR)
    }

    @Test
    fun `requestFullBackupTime() returns 0 on unmetered WiFi`() {
        setupUnmeteredWifi()
        assertThat(transport.requestFullBackupTime()).isEqualTo(0L)
    }

    @Test
    fun `requestFullBackupTime() returns non-zero on metered WiFi`() {
        setupMeteredWifi()
        assertThat(transport.requestFullBackupTime()).isGreaterThan(0L)
    }

    @Test
    fun `Full performBackup() succeeds`() {
        val packageInfo: PackageInfo = mockk()
        packageInfo.packageName = TEST_PACKAGE_NAME
        every { sardine.exists(TEST_PACKAGE_URL) } returns false
        every { sardine.createDirectory(TEST_PACKAGE_URL) } returns Unit
        every {
            sardine.patch(TEST_PACKAGE_URL, propMapOf("backupType" to "kv"))
        } returns listOf<DavResource>()
        every {
            sardine.list(TEST_PACKAGE_URL, 0, false, true)
        } returns listOf(
                DavResource.Builder("/webdav/$TEST_PACKAGE_NAME")
                        .build()
        )
        every {
            sardine.list(TEST_PACKAGE_URL, 0, propSetOf())
        } returns listOf(
                DavResource.Builder("/webdav/$TEST_PACKAGE_NAME")
                        .build()
        )
        // TODO: this call and the one below expose internal implementation details
        // of the code, put 'any()' matches here as soon as full integration
        // test is implemented that checks we can recover the data back?
        every {
            sardine.put("$TEST_PACKAGE_URL/a2V5Ag", any<ByteArray>())
        } returns Unit
        every {
            sardine.patch(TEST_PACKAGE_URL, propMapOf("KV_a2V5AQ" to "AAECAwQ"), listOf())
        } returns listOf<DavResource>()
        val inFd = mockkClass(ParcelFileDescriptor::class) {
            every { this@mockkClass.fileDescriptor } returns mockk()
            every { this@mockkClass.close() } returns Unit
        }
        mockkConstructor(BackupDataInput::class) {
            every {
                anyConstructed<BackupDataInput>().readNextHeader()
            } returnsMany listOf(true, true, false)
            every {
                anyConstructed<BackupDataInput>().key
            } returnsMany listOf("key\u0001", "key\u0002")
            every {
                anyConstructed<BackupDataInput>().dataSize
            } returnsMany listOf(TEST_SMALL_DATA_SIZE, TEST_LARGE_DATA_SIZE)
            every {
                anyConstructed<BackupDataInput>().readEntityData(
                        any(), 0, any()
                )
            } answers {
                val data = arg<ByteArray>(0)
                assertThat(data.size).isEqualTo(TEST_SMALL_DATA_SIZE)
                arg<ByteArray>(0).forEachIndexed( { ind, _ -> data[ind] = (ind % 256).toByte() })
                0
            } andThen {
                val data = arg<ByteArray>(0)
                assertThat(data.size).isEqualTo(TEST_LARGE_DATA_SIZE)
                arg<ByteArray>(0).forEachIndexed( { ind, _ -> data[ind] = (ind % 256).toByte() })
                0
            }
            assertThat(
                    transport.performBackup(
                            packageInfo,
                            inFd,
                            BackupTransport.FLAG_NON_INCREMENTAL
                    )
            ).isEqualTo(BackupTransport.TRANSPORT_OK)
        }
        verify(exactly = 1) { sardine.put("$TEST_PACKAGE_URL/a2V5Ag", genByteArray(TEST_LARGE_DATA_SIZE, true)) }
        verify(exactly = 1) { sardine.patch(TEST_PACKAGE_URL, propMapOf("KV_a2V5AQ" to "AAECAwQ"), listOf()) }
    }

    private fun setupRetrieveKvPropResponse() {
        every {
            sardine.list(TEST_PACKAGE_URL, 0, false, true)
        } returns listOf(
                DavResource.Builder("/webdav/$TEST_PACKAGE_NAME")
                        .withCustomProps(propMapOf("KV_a2V5AQ" to ""))
                        .build()
        )
        every {
            sardine.list(TEST_PACKAGE_URL, 0, propSetOf("KV_a2V5AQ"))
        } returns listOf(
                DavResource.Builder("/webdav/$TEST_PACKAGE_NAME")
                        .withCustomProps(propMapOf("KV_a2V5AQ" to "AAECAwQ"))
                        .build()
        )
    }

    @Test
    fun `Incremental performBackup() succeeds`() {
        val packageInfo: PackageInfo = mockk()
        packageInfo.packageName = TEST_PACKAGE_NAME
        every { sardine.exists(TEST_PACKAGE_URL) } returns true
        setupRetrieveKvPropResponse()
        every { sardine.exists("$TEST_PACKAGE_URL/a2V5Ag") } returns true
        every { sardine.delete("$TEST_PACKAGE_URL/a2V5Ag") } returns Unit
        every {
            sardine.patch(TEST_PACKAGE_URL, propMapOf("KV_a2V5Aw" to "AAECAwQ"), propSetOf("KV_a2V5AQ").toList())
        } returns listOf<DavResource>()

        val inFd = mockkClass(ParcelFileDescriptor::class) {
            every { this@mockkClass.fileDescriptor } returns mockk()
            every { this@mockkClass.close() } returns Unit
        }
        mockkConstructor(BackupDataInput::class) {
            every {
                anyConstructed<BackupDataInput>().readNextHeader()
            } returnsMany listOf(true, true, true, false)
            every {
                anyConstructed<BackupDataInput>().key
            } returnsMany listOf("key\u0001", "key\u0002", "key\u0003")
            every {
                anyConstructed<BackupDataInput>().dataSize
            } returnsMany listOf(-1, -1, TEST_SMALL_DATA_SIZE)
            every {
                anyConstructed<BackupDataInput>().readEntityData(
                        any(), 0, any()
                )
            } answers {
                val data = arg<ByteArray>(0)
                assertThat(data.size).isEqualTo(TEST_SMALL_DATA_SIZE)
                arg<ByteArray>(0).forEachIndexed({ ind, _ -> data[ind] = (ind % 256).toByte() })
                0
            }

            assertThat(
                    transport.performBackup(
                            packageInfo,
                            inFd,
                            BackupTransport.FLAG_INCREMENTAL
                    )
            ).isEqualTo(BackupTransport.TRANSPORT_OK)
        }
        verify(exactly = 1) { sardine.delete("$TEST_PACKAGE_URL/a2V5Ag") }
        verify(exactly = 1) { sardine.patch(TEST_PACKAGE_URL, propMapOf("KV_a2V5Aw" to "AAECAwQ"), propSetOf("KV_a2V5AQ").toList())}
    }

    private fun genByteArray(size: Int, compress: Boolean = false, prefixSize: Boolean = true): ByteArray {
        val data = ByteArray(size, { (it % 256).toByte() })
        return if (compress) {
            ByteArrayOutputStream().use {
                DataOutputStream(GZIPOutputStream(it)).use {
                    if (prefixSize) {
                        it.writeInt(size)
                    }
                    it.write(data)
                }
                it.toByteArray()
            }
        } else data
    }

    private fun createTempBackupFile(): Pair<InputStream, ParcelFileDescriptor> {
        val file = File.createTempFile("WedabanTest-", ".tmp")
        file.deleteOnExit()
        val outStream = file.outputStream()
        var data = genByteArray(TEST_LARGE_DATA_SIZE)
        outStream.write(data)
        outStream.close()
        val inStream = file.inputStream()
        val socket: ParcelFileDescriptor = mockk()
        val dupFd: ParcelFileDescriptor = mockk()
        every { socket.dup() } returns dupFd
        every { dupFd.fileDescriptor } returns inStream.fd
        return Pair(inStream, socket)
    }

    @Test
    fun `performFullBackup() succeeds`() {
        val packageInfo: PackageInfo = mockk()
        packageInfo.packageName = TEST_PACKAGE_NAME
        val (_, socket) = createTempBackupFile()
        assertThat(transport.performFullBackup(packageInfo, socket))
                .isEqualTo(TRANSPORT_OK)
        // Second call should fail because backup is already in progress.
        assertThat(transport.performFullBackup(packageInfo, socket))
                .isEqualTo(TRANSPORT_ERROR)
        every { sardine.exists(TEST_PACKAGE_URL) } returns true
        every {
            sardine.patch(TEST_PACKAGE_URL,
                    propMapOf("backupType" to "full")) } returns listOf()
        every { sardine.put(TEST_FULL_BACKUP_URL, any<InputStream>()) } returns Unit
        assertThat(transport.sendBackupData(TEST_LARGE_DATA_SIZE))
                .isEqualTo(TRANSPORT_OK)
        assertThat(transport.finishBackup()).isEqualTo(TRANSPORT_OK)
    }

    @Test
    fun `performFullBackup() fails on socket error`() {
        val packageInfo: PackageInfo = mockk()
        packageInfo.packageName = TEST_PACKAGE_NAME
        val socket: ParcelFileDescriptor = mockk()
        every { socket.dup() } throws IOException()
        assertThat(transport.performFullBackup(packageInfo, socket))
                .isEqualTo(TRANSPORT_ERROR)
    }

    @Test
    fun `sendBackupData() fails without performFullBackup() called first`() {
        assertThat(transport.sendBackupData(TEST_LARGE_DATA_SIZE))
                .isEqualTo(TRANSPORT_ERROR)
    }

    @Test
    fun `sendBackupData() fails on file IO error`() {
        val packageInfo: PackageInfo = mockk()
        packageInfo.packageName = TEST_PACKAGE_NAME
        val (inStream, socket) = createTempBackupFile()
        // Close input stream to force IO exception when the transport
        // tries to read from it.
        inStream.close()
        every { sardine.delete(TEST_FULL_BACKUP_URL) } returns Unit
        assertThat(transport.performFullBackup(packageInfo, socket))
                .isEqualTo(TRANSPORT_OK)
        assertThat(transport.sendBackupData(TEST_LARGE_DATA_SIZE))
                .isEqualTo(TRANSPORT_ERROR)
        verify(exactly = 1) { sardine.delete(TEST_FULL_BACKUP_URL) }
    }

    @Test
    fun `sendBackupData() fails on WebDAV IO error`() {
        val packageInfo: PackageInfo = mockk()
        packageInfo.packageName = TEST_PACKAGE_NAME
        val (_, socket) = createTempBackupFile()
        every { sardine.exists(TEST_PACKAGE_URL) } returns false
        every { sardine.createDirectory(TEST_PACKAGE_URL) } returns Unit
        every { sardine.patch(TEST_PACKAGE_URL, any()) } returns listOf()
        every { sardine.put(TEST_FULL_BACKUP_URL, any<InputStream>()) } throws IOException()
        every { sardine.delete(TEST_FULL_BACKUP_URL) } returns Unit
        assertThat(transport.performFullBackup(packageInfo, socket)).isEqualTo(TRANSPORT_OK)
        assertThat(transport.sendBackupData(TEST_LARGE_DATA_SIZE)).isEqualTo(TRANSPORT_OK)
        // The error happens in separate thread so the first sendBackupData() call won't
        // catch it, but subsequent calls to either sendBackupData() or finishBackup() should.
        // However, we need to make sure we're not calling the second 'sendBackupData()' too quick,
        // otherwise the thread might not have had the chance to fail yet.
        // The code to ensure that below is kind of ugly (= private field access) but in turn
        // guarantees that the thread has definitely handled the exception.
        val fullBackupUploadThreadDecl = transport.javaClass.getDeclaredField("fullBackupUploadThread")
        fullBackupUploadThreadDecl.isAccessible = true
        (fullBackupUploadThreadDecl.get(transport) as Thread)?.join()
        assertThat(transport.sendBackupData(TEST_LARGE_DATA_SIZE)).isEqualTo(TRANSPORT_ERROR)
        assertThat(transport.finishBackup()).isEqualTo(TRANSPORT_ERROR)
        verify(exactly = 1) { sardine.delete(TEST_FULL_BACKUP_URL) }
    }

    @Test
    fun `sendBackupData() fails for out of quota uploads`() {
        val packageInfo: PackageInfo = mockk()
        packageInfo.packageName = TEST_PACKAGE_NAME
        val (_, socket) = createTempBackupFile()
        every { sardine.delete(TEST_FULL_BACKUP_URL) } returns Unit
        assertThat(transport.performFullBackup(packageInfo, socket)).isEqualTo(TRANSPORT_OK)
        assertThat(transport.sendBackupData(Int.MAX_VALUE)).isEqualTo(TRANSPORT_QUOTA_EXCEEDED)
    }

    @Test
    fun `startRestore() succeeds`() {
        val packageInfo: PackageInfo = mockk()
        packageInfo.packageName = TEST_PACKAGE_NAME
        val packages = arrayOf(packageInfo)
        every {
            sardine.list(TEST_URL, 1, propSetOf("token"))
        } returns listOf(
                DavResource.Builder("/webdav/${TEST_DEVICE_ID}")
                        .withCustomProps(propMapOf("token" to "$TEST_TOKEN"))
                        .build()
        )
        assertThat(transport.startRestore(TEST_TOKEN, packages))
                .isEqualTo(TRANSPORT_OK)
    }

    @Test
    fun `startRestore() fails when no token`() {
        val packageInfo: PackageInfo = mockk()
        packageInfo.packageName = TEST_PACKAGE_NAME
        val packages = arrayOf(packageInfo)
        every {
            sardine.list(TEST_URL, 1, propSetOf("token"))
        } returns listOf()
        assertThat(transport.startRestore(TEST_TOKEN, packages))
                .isEqualTo(TRANSPORT_ERROR)
    }

    @Test
    fun `startRestore() fails when called twice`() {
        `startRestore() succeeds`()
        val packageInfo: PackageInfo = mockk()
        assertThrows<IllegalStateException> {
            transport.startRestore(TEST_TOKEN, arrayOf(packageInfo))
        }
    }

    @Test
    fun `startRestore() fails with non-matching token`() {
        val packageInfo: PackageInfo = mockk()
        packageInfo.packageName = TEST_PACKAGE_NAME
        val packages = arrayOf(packageInfo)
        every {
            sardine.list(TEST_URL, 1, propSetOf("token"))
        } returns listOf(
                DavResource.Builder("/webdav")
                        .withCustomProps(propMapOf("token" to "321"))
                        .build()
        )
        assertThat(transport.startRestore(TEST_TOKEN, packages))
                .isEqualTo(TRANSPORT_ERROR)
    }

    @Test
    fun `startRestore() fails with invalid token`() {
        val packageInfo: PackageInfo = mockk()
        packageInfo.packageName = TEST_PACKAGE_NAME
        val packages = arrayOf(packageInfo)
        every {
            sardine.list(TEST_URL, 1, propSetOf("token"))
        } returns listOf(
                DavResource.Builder("/webdav")
                        .withCustomProps(propMapOf("token" to "invalid"))
                        .build()
        )
        assertThat(transport.startRestore(TEST_TOKEN, packages))
                .isEqualTo(TRANSPORT_ERROR)
    }

    @Test
    fun `nextRestorePackage() succeeds for KV backup`() {
        `startRestore() succeeds`()
        every {
            sardine.list(TEST_PACKAGE_URL, 0, propSetOf("backupType"))
        } returns listOf(
                DavResource.Builder("/webdav/${TEST_DEVICE_ID}/${TEST_PACKAGE_NAME}")
                        .withCustomProps(propMapOf("backupType" to "kv"))
                        .build()
        )
        mockkConstructor(RestoreDescription::class) {
            assertThat(transport.nextRestorePackage()).isNotEqualTo(
                    RestoreDescription.NO_MORE_PACKAGES
            )
            // TODO: once Mockk supports constructor parameters match
            // (see https://github.com/mockk/mockk/issues/209), check that
            // args are right. Current draft syntax is:
            // verify(exactly = 1) {
            //     constructedWith<RestoreDescription>(
            //         TEST_PACKAGE_NAME,
            //         RestoreDescription.TYPE_KEY_VALUE
            //     )
            // }
        }
    }

    @Test
    fun `nextRestorePackage() fails without startRestore() call`() {
        assertThrows<IllegalStateException> {
            transport.nextRestorePackage()
        }
    }

    private fun <T: Any> mockFinalStaticField(cl: KClass<T>, name: String): T {
        val field = cl.java.getField(name)
        field.isAccessible = true

        // Remove final modifier from field.
        val modifiersField = Field::class.java.getDeclaredField("modifiers")
        modifiersField.isAccessible = true
        modifiersField.setInt(field, field.modifiers and Modifier.FINAL.inv())

        val mock = mockkClass(cl)
        field.set(null, mock)
        return mock
    }

    @Test
    fun `nextRestorePackage() fails without expected props`() {
        `startRestore() succeeds`()
        every {
            sardine.list(TEST_PACKAGE_URL, 0, propSetOf("backupType"))
        } returns listOf(
                DavResource.Builder("/webdav/${TEST_DEVICE_ID}/${TEST_PACKAGE_NAME}")
                        .build()
        )
        mockFinalStaticField(RestoreDescription::class, "NO_MORE_PACKAGES")
        assertThat(transport.nextRestorePackage())
                .isEqualTo(RestoreDescription.NO_MORE_PACKAGES)
    }

    @Test
    fun `nextRestorePackage() fails with invalid props`() {
        `startRestore() succeeds`()
        every {
            sardine.list(TEST_PACKAGE_URL, 0, propSetOf("backupType"))
        } returns listOf(
                DavResource.Builder("/webdav/${TEST_DEVICE_ID}/${TEST_PACKAGE_NAME}")
                        .withCustomProps(propMapOf("backupType" to "invalid"))
                        .build()
        )
        mockFinalStaticField(RestoreDescription::class, "NO_MORE_PACKAGES")
        assertThat(transport.nextRestorePackage())
                .isEqualTo(RestoreDescription.NO_MORE_PACKAGES)
    }

    @Test
    fun `getRestoreData() succeeds`() {
        `nextRestorePackage() succeeds for KV backup`()
        every { sardine.list(TEST_PACKAGE_URL, 1) } returns listOf(
                DavResource.Builder("/webdav/$TEST_DEVICE_ID/$TEST_PACKAGE_NAME/a2V5Ag")
                        .build()
        )
        setupRetrieveKvPropResponse()
        val outFd = mockk<ParcelFileDescriptor>()
        every { outFd.fileDescriptor } returns mockk()
        mockkConstructor(BackupDataOutput::class) {
            every {
                anyConstructed<BackupDataOutput>().writeEntityHeader(
                        "key\u0001",
                        TEST_SMALL_DATA_SIZE
                )
            } returns 0
            every {
                anyConstructed<BackupDataOutput>().writeEntityData(
                        genByteArray(TEST_SMALL_DATA_SIZE),
                        TEST_SMALL_DATA_SIZE
                )
            } returns 0
            val dataStream = ByteArrayInputStream(
                    genByteArray(TEST_LARGE_DATA_SIZE, true)
            )
            every { sardine.get("${TEST_PACKAGE_URL}/a2V5Ag") } returns dataStream
            every {
                anyConstructed<BackupDataOutput>().writeEntityHeader(
                        "key\u0002",
                        TEST_LARGE_DATA_SIZE
                )
            } returns 0
            val data = ByteArrayOutputStream()
            // Check data size to match only the data for the second key.
            every {
                anyConstructed<BackupDataOutput>().writeEntityData(any(), more(TEST_SMALL_DATA_SIZE))
            } answers {
                data.write(arg<ByteArray>(0), 0, arg<Int>(1))
                0
            }
            assertThat(transport.getRestoreData(outFd)).isEqualTo(TRANSPORT_OK)
            assertThat(data.toByteArray()).isEqualTo(genByteArray(TEST_LARGE_DATA_SIZE))
        }
    }

    @Test
    fun `getRestoreData() fails if startRestore() wasn't called`() {
        assertThrows<IllegalStateException>() { transport.getRestoreData(mockk()) }
    }

    @Test
    fun `getRestoreData() fails if getNextPackage() wasn't called`() {
        `startRestore() succeeds`()
        assertThrows<IllegalStateException>() { transport.getRestoreData(mockk()) }
    }

    @Test
    fun `getRestoreData() fails if backup type is wrong`() {
        `nextRestorePackage() succeeds for full backup`()
        assertThrows<IllegalStateException>() { transport.getRestoreData(mockk()) }
    }

    @Test
    fun `getRestoreData() fails on IO error`() {
        `nextRestorePackage() succeeds for KV backup`()
        every { sardine.list(TEST_PACKAGE_URL, 1) } throws IOException()
        assertThat(transport.getRestoreData(mockk())).isEqualTo(TRANSPORT_ERROR)
    }

    @Test
    fun `finishRestore() succeeds`() {
        `startRestore() succeeds`()
        transport.finishRestore()
        `startRestore() succeeds`()
    }

    @Test
    fun `finishRestore() fails without startRestore`() {
        assertThrows<IllegalStateException> {
            transport.finishRestore()
        }
    }

    @Test
    fun `nextRestorePackage() succeeds for full backup`() {
        `startRestore() succeeds`()
        every {
            sardine.list(TEST_PACKAGE_URL, 0, propSetOf("backupType"))
        } returns listOf(
                DavResource.Builder("/webdav/${TEST_DEVICE_ID}/${TEST_PACKAGE_NAME}")
                        .withCustomProps(propMapOf("backupType" to "full"))
                        .build()
        )
        mockkConstructor(RestoreDescription::class) {
            assertThat(transport.nextRestorePackage()).isNotEqualTo(
                    RestoreDescription.NO_MORE_PACKAGES
            )
            // TODO: once Mockk supports constructor parameters match
            // (see https://github.com/mockk/mockk/issues/209), check that
            // args are right. Current draft syntax is:
            // verify(exactly = 1) {
            //     constructedWith<RestoreDescription>(
            //         TEST_PACKAGE_NAME,
            //         RestoreDescription.TYPE_FULL_STREAM
            //     )
            // }
        }
    }

    @Test
    fun `getNextFullRestoreDataChunk() succeeds`() {
        `nextRestorePackage() succeeds for full backup`()
        val file = File.createTempFile("WedabanTest-", ".tmp")
        file.deleteOnExit()
        val outFd = mockk<ParcelFileDescriptor>()
        every { outFd.fileDescriptor } returns file.outputStream().fd
        val dataStream = ByteArrayInputStream(
                genByteArray(TEST_LARGE_DATA_SIZE, true, false)
        )
        every { sardine.get("${TEST_FULL_BACKUP_URL}") } returns dataStream
        var result: Int
        var totalSize = 0
        do {
            result = transport.getNextFullRestoreDataChunk(outFd)
            assertThat(result).isGreaterThanOrEqualTo(NO_MORE_DATA)
            assertThat(result).isNotEqualTo(0)
            if (result == NO_MORE_DATA) {
                break
            }
            totalSize += result
        } while (true)
        assertThat(totalSize).isEqualTo(TEST_LARGE_DATA_SIZE)
        assertThat(file.inputStream().readBytes()).isEqualTo(genByteArray(TEST_LARGE_DATA_SIZE))
        transport.finishRestore()
    }

    @Test
    fun `getNextFullRestoreDataChunk() fails for wrong backup type`() {
        `nextRestorePackage() succeeds for KV backup`()
        assertThrows<IllegalStateException> {
            transport.getNextFullRestoreDataChunk(mockk())
        }
    }

    @Test
    fun `getNextFullRestoreDataChunk() fails for wrong setup`() {
        assertThrows<IllegalStateException> {
            transport.getNextFullRestoreDataChunk(mockk())
        }
    }

    @Test
    fun `getNextFullRestoreDataChunk() fails for IO error`() {
        `nextRestorePackage() succeeds for full backup`()
        every { sardine.get("${TEST_FULL_BACKUP_URL}") } throws IOException()
        assertThat(transport.getNextFullRestoreDataChunk(mockk())).isEqualTo(TRANSPORT_ERROR)
    }

    @Test
    fun `abortFullRestore() succeeds`() {
        `nextRestorePackage() succeeds for full backup`()
        assertThat(transport.abortFullRestore()).isEqualTo(TRANSPORT_OK)
    }

    @Test
    fun `abortFullRestore() fails for wrong backup type`() {
        `nextRestorePackage() succeeds for KV backup`()
        assertThrows<IllegalStateException> {
            transport.abortFullRestore()
        }
    }

    @Test
    fun `abortFullRestore() fails for wrong setup`() {
        assertThrows<IllegalStateException> {
            transport.abortFullRestore()
        }
    }

    @Test
    fun `getBackupQuota() succeeds`() {
        assertThat(transport.getBackupQuota(TEST_PACKAGE_NAME, false)).isGreaterThan(0)
        assertThat(transport.getBackupQuota(TEST_PACKAGE_NAME, true)).isGreaterThan(0)
    }
}

@RunWith(RobolectricTestRunner::class)
class WedabanTransportRoboelectricTest: WedabanTransportUnitTestCommon() {
    private fun setupTestDeviceIdListResponse() {
        every {
            sardine.list("$TEST_URL/$TEST_DEVICE_ID", 0, propSetOf("name", "token"))
        } returns listOf<DavResource>(
                DavResource.Builder("/webdav/$TEST_DEVICE_ID")
                        .withCustomProps(propMapOf("name" to TEST_BACKUP_NAME, "token" to "$TEST_TOKEN"))
                        .build()
        )
    }

    // RestoreSet() constructor is not mocked and Mockk doesn't allow
    // mocking constructors with parameters / setting fields + doesn't
    // allow mocking fields themselves.
    @TestJ4
    fun `getCurrentRestoreSet succeeds with correct resources`() {
        setupTestDeviceIdListResponse()
        assertThat(transport.currentRestoreSet).isEqualTo(TEST_TOKEN)
    }

    private fun setupPackageNameResponse() {
        every { context.getPackageName() } returns "ch.endl.wedaban"
    }

    // flattenToShortString is not mocked.
    // TODO: fix after Mockk supports checking constructors parameters.
    @TestJ4
    fun `name() returns expected value`() {
        setupPackageNameResponse()
        assertThat(transport.name()).isEqualTo(
                "ch.endl.wedaban/.WedabanTransport")
    }

    // flattenToString is not mocked.
    // TODO: fix after Mockk supports checking constructors parameters.
    @TestJ4
    fun `transportDirName() returns expected value`() {
        setupPackageNameResponse()
        assertThat(transport.transportDirName()).isEqualTo(
                "ch.endl.wedaban/ch.endl.wedaban.WedabanTransport")
    }
}