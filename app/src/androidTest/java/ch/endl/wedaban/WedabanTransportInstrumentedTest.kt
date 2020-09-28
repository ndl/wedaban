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

import android.app.backup.BackupDataOutput
import android.app.backup.BackupTransport
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import assertk.assertThat
import assertk.assertions.*
import ch.endl.wedaban.WedabanTransport.Companion.propMapOf
import ch.endl.wedaban.WedabanTransport.Companion.propSetOf
import com.thegrizzlylabs.sardineandroid.DavResource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.io.File

class WedabanTransportInstrumentedTest: WedabanTransportUnitTestCommon() {
}
