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

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.net.URI

class WedabanTransportService : Service() {

    private var transport: WedabanTransport? = null

    override fun onCreate() {
        if (transport == null) {
            val settings = WedabanSettings(this)
            val params = with (settings) {
                WedabanTransportParameters(
                        backupName,
                        URI(baseUrl),
                        username,
                        password,
                        debug)
            }
            transport = WedabanTransport(this, params)
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return transport?.getBinder()
    }
}