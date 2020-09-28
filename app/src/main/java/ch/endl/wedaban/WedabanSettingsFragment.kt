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

import android.os.Bundle

import android.app.AlertDialog
import androidx.preference.CheckBoxPreference

import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.uiThread

import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

class WedabanSettingsFragment: PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)
        val backupName = EditTextPreference(context)
        backupName.key = WedabanSettings.BACKUP_NAME
        backupName.title = "Backup name for this device"
        backupName.dialogTitle = backupName.title
        screen.addPreference((backupName))

        val baseUrl = EditTextPreference(context)
        baseUrl.key = WedabanSettings.BASE_URL
        baseUrl.title = "WebDAV server URL"
        baseUrl.dialogTitle = baseUrl.title
        baseUrl.setOnPreferenceChangeListener() { _, newValue ->
            var result = false
              try {
                  URI(newValue as String)
                  result = true
              } catch (ex: URISyntaxException) {
                  showAlertDialog("Wront URL Format", "URL '${newValue}' cannot be parsed: ${ex}")
              }
            result
        }
        screen.addPreference(baseUrl)

        val username = EditTextPreference(context)
        username.key = WedabanSettings.USERNAME
        username.title = "User name"
        username.dialogTitle = username.title
        screen.addPreference(username)

        val password = EditTextPreference(context)
        password.key = WedabanSettings.PASSWORD
        password.title = "Password"
        password.dialogTitle = password.title
        screen.addPreference(password)

        val debug = CheckBoxPreference(context)
        debug.key = WedabanSettings.DEBUG
        debug.title = "Extra debug logging"
        screen.addPreference(debug)

        val testConnection = Preference(context)
        testConnection.key = "testConnection"
        testConnection.title = "Test connection and apply"
        testConnection.setOnPreferenceClickListener {
            doAsync {
                var title: String
                var message: String
                try {
                    val sardine = OkHttpSardine()
                    sardine.setCredentials(username.text, password.text)
                    val res = sardine.list(baseUrl.text, 0)
                    if (res.size == 1) {
                        val baseUri = URI(baseUrl.text)
                        val uri = URI(
                                baseUri.scheme,
                                baseUri.host,
                                "${baseUri.path}/${WedabanTransport.generateDeviceId()}",
                                null)
                        WedabanTransport.prepareServerStorage(
                                sardine, backupName.text, uri.toString(), debug.isChecked())
                        title = "Connection Succeeded"
                        message = "Connection to WebDAV server succeeded!"
                    } else {
                        title = "Connection Failed"
                        message = "Unexpected WebDAV server response: ${res}"
                    }
                } catch (ex: IOException) {
                    title = "Connection Failed"
                    message = "Failed to connect to WebDAV server: ${ex}"
                }

                uiThread {
                    showAlertDialog(title, message)
                }
            }
            true
        }
        screen.addPreference(testConnection)
        preferenceScreen = screen
    }

    private fun showAlertDialog(title: String, message: String) {
        val dialog = AlertDialog.Builder(context).create()
        dialog.setTitle(title)
        dialog.setMessage(message)
        dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "OK") { _, _ ->
            dialog.dismiss()
        }
        dialog.show()
    }
}