package com.openautolink.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OalUiScalePreferenceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `default remains normal and each size survives reopening the datastore`() = runBlocking {
        val file = File(temporary.root, "settings.preferences_pb")
        withStore(file) { prefs, _ -> assertEquals(100, prefs.oalUiScalePercent.first()) }
        for (percent in listOf(100, 125, 150)) {
            withStore(file) { prefs, _ -> prefs.setOalUiScalePercent(percent) }
            withStore(file) { prefs, _ -> assertEquals(percent, prefs.oalUiScalePercent.first()) }
        }
    }

    @Test fun `invalid writes and persisted values resolve to a supported safe size`() = runBlocking {
        val file = File(temporary.root, "settings.preferences_pb")
        withStore(file) { prefs, store ->
            for ((invalid, expected) in listOf(Int.MIN_VALUE to 100, 0 to 100,
                112 to 100, 113 to 125, 137 to 125, 138 to 150, Int.MAX_VALUE to 150)) {
                prefs.setOalUiScalePercent(invalid)
                assertEquals(expected, store.data.first()[AppPreferences.OAL_UI_SCALE_PERCENT])
                store.edit { it[AppPreferences.OAL_UI_SCALE_PERCENT] = invalid }
                assertEquals(expected, prefs.oalUiScalePercent.first())
            }
        }
    }

    @Test fun `interface preference never changes projection settings`() = runBlocking {
        val file = File(temporary.root, "settings.preferences_pb")
        withStore(file) { prefs, store ->
            prefs.setAaDpi(240)
            val before = store.data.first().asMap()
            prefs.setOalUiScalePercent(150)
            assertEquals(before, store.data.first().asMap().filterKeys { it != AppPreferences.OAL_UI_SCALE_PERCENT })
        }
    }

    private suspend fun withStore(file: File, block: suspend (AppPreferences, DataStore<Preferences>) -> Unit) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
        val constructor = AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java)
            .apply { isAccessible = true }
        try { block(constructor.newInstance(store), store) } finally { job.cancelAndJoin() }
    }
}
