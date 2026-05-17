/*
 * Copyright 2026 Nikolay Vlasov (https://github.com/nikkiw)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.nikkiw.taskbridge.checkpoint

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * An implementation of [TaskBridgeCheckpointStore] using Android Jetpack DataStore (Preferences).
 *
 * This provides persistent storage for task checkpoints across app restarts.
 *
 * @param file The file location where DataStore will persist data.
 * @param scope CoroutineScope for DataStore internal operations.
 */
class DataStoreTaskBridgeCheckpointStore(
    file: File,
    scope: CoroutineScope,
) : TaskBridgeCheckpointStore {
    private val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file },
        )

    override suspend fun load(key: String): String? = dataStore.data.first()[preferenceKey(key)]

    override suspend fun save(
        key: String,
        lastEventId: String,
    ) {
        dataStore.edit { preferences ->
            preferences[preferenceKey(key)] = lastEventId
        }
    }

    override suspend fun clear(key: String) {
        dataStore.edit { preferences ->
            preferences.remove(preferenceKey(key))
        }
    }

    private fun preferenceKey(key: String) =
        stringPreferencesKey(
            URLEncoder.encode(key, StandardCharsets.UTF_8.name()),
        )
}
