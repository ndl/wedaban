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

import android.content.Context
import com.thegrizzlylabs.sardineandroid.Sardine
import io.mockk.every
import io.mockk.mockk
import java.net.URI

open class WedabanTransportUnitTestCommon {
    protected val context: Context = mockk()
    protected val sardine: Sardine = mockk()
    init {
        every {
            sardine.setCredentials("", "", true)
        } returns Unit
    }
    protected val transport = WedabanTransport(
            context,
            WedabanTransportParameters(
                    TEST_BACKUP_NAME,
                    URI(TEST_URL),
                    "",
                    "",
                    false
            ),
            TEST_DEVICE_ID,
            sardine
    )

    companion object {
        const val TEST_DEVICE_ID = "TestDeviceId"
        const val TEST_URL = "http://example.com/webdav"
        const val TEST_URL_WITH_DEVICE_ID = "$TEST_URL/$TEST_DEVICE_ID"
        const val TEST_PACKAGE_NAME = "TestPackage"
        const val TEST_PACKAGE_URL = "$TEST_URL_WITH_DEVICE_ID/$TEST_PACKAGE_NAME"
        const val TEST_FULL_BACKUP_URL = "$TEST_PACKAGE_URL/content"
        const val TEST_BACKUP_NAME = "TestBackup"
        const val TEST_TOKEN = 123L
        const val TEST_TIMESTAMP = 12345L
        const val TEST_SMALL_DATA_SIZE = 5
        const val TEST_LARGE_DATA_SIZE = 2048
    }
}