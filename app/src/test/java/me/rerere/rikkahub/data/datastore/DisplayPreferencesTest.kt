package me.rerere.rikkahub.data.datastore

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DisplayPreferencesTest {
    @Test
    fun `reading preferences survive reopening the client store`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val store = DisplayPreferencesStore(context)
        assertEquals(DisplayPreferences(), store.state.value)
        store.update { it.copy(fontSizeRatio = 1.3f, serif = true) }
        assertEquals(DisplayPreferences(1.3f, true), DisplayPreferencesStore(context).state.value)
    }
}
