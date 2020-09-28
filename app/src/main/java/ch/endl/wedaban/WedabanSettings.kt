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
import android.content.SharedPreferences
import java.lang.IllegalStateException
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import androidx.preference.PreferenceManager

class WedabanSettings(context: Context) {
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    inner class PrefsProperty<T>(val name: String, val defaultValue: T): ReadWriteProperty<Any, T> {
        override fun getValue(thisRef: Any, property: KProperty<*>): T =
                if (prefs.all.get(name) != null) prefs.all.get(name) as T else defaultValue

        override fun setValue(thisRef: Any, property: KProperty<*>, value: T) {
            with (prefs.edit()) {
                when (value) {
                    is String -> putString(name, value)
                    else -> throw IllegalStateException("Unexpected property value type")
                }.apply()
            }
        }
    }

    val baseUrl by PrefsProperty(BASE_URL, "")
    val backupName by PrefsProperty(BACKUP_NAME, "")
    val username by PrefsProperty(USERNAME, "")
    val password by PrefsProperty(PASSWORD, "")
    val debug by PrefsProperty(DEBUG, false)

    companion object {
        const val BASE_URL = "baseUrl"
        const val BACKUP_NAME = "backupName"
        const val USERNAME = "username"
        const val PASSWORD = "password"
        const val DEBUG = "debug"
    }
}