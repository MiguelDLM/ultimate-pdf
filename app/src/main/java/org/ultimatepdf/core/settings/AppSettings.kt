package org.ultimatepdf.core.settings

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

enum class ThemeMode(val key: String) { SYSTEM("system"), LIGHT("light"), DARK("dark") }

/** App language; [tag] is a BCP-47 tag, empty for "follow the system". */
enum class AppLanguage(val tag: String) { SYSTEM(""), ENGLISH("en"), SPANISH("es") }

/** Periodicity for checking app updates. */
enum class UpdateFrequency(val key: String, val intervalMillis: Long) {
    DAILY("daily", 24L * 60L * 60L * 1000L),
    WEEKLY("weekly", 7L * 24L * 60L * 60L * 1000L)
}

/**
 * User preferences. Stored in SharedPreferences because the language must be read
 * synchronously in `attachBaseContext`, before any UI exists.
 */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var themeMode by mutableStateOf(
        ThemeMode.entries.firstOrNull { it.key == prefs.getString(KEY_THEME, null) } ?: ThemeMode.SYSTEM
    )
        private set

    val language: AppLanguage get() = languageOf(prefs)

    var autoUpdateEnabled by mutableStateOf(prefs.getBoolean(KEY_AUTO_UPDATE_ENABLED, true))
        private set

    var updateFrequency by mutableStateOf(
        UpdateFrequency.entries.firstOrNull { it.key == prefs.getString(KEY_UPDATE_FREQUENCY, null) } ?: UpdateFrequency.DAILY
    )
        private set

    var lastUpdateCheckTime by mutableStateOf(prefs.getLong(KEY_LAST_UPDATE_CHECK_TIME, 0L))
        private set

    fun updateTheme(mode: ThemeMode) {
        themeMode = mode
        prefs.edit().putString(KEY_THEME, mode.key).apply()
    }

    /** Persists the language; the caller recreates the activity to apply it. */
    fun updateLanguage(language: AppLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.tag).commit()
    }

    fun updateAutoUpdateEnabled(enabled: Boolean) {
        autoUpdateEnabled = enabled
        prefs.edit().putBoolean(KEY_AUTO_UPDATE_ENABLED, enabled).apply()
    }

    fun updateFrequency(frequency: UpdateFrequency) {
        updateFrequency = frequency
        prefs.edit().putString(KEY_UPDATE_FREQUENCY, frequency.key).apply()
    }

    fun recordUpdateChecked(timestamp: Long = System.currentTimeMillis()) {
        lastUpdateCheckTime = timestamp
        prefs.edit().putLong(KEY_LAST_UPDATE_CHECK_TIME, timestamp).apply()
    }

    companion object {
        private const val PREFS = "settings"
        private const val KEY_THEME = "theme"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_AUTO_UPDATE_ENABLED = "auto_update_enabled"
        private const val KEY_UPDATE_FREQUENCY = "update_frequency"
        private const val KEY_LAST_UPDATE_CHECK_TIME = "last_update_check_time"

        private fun languageOf(prefs: android.content.SharedPreferences): AppLanguage {
            val tag = prefs.getString(KEY_LANGUAGE, "") ?: ""
            return AppLanguage.entries.firstOrNull { it.tag == tag } ?: AppLanguage.SYSTEM
        }

        /** Returns [base] configured with the chosen app language (unchanged for "system"). */
        fun wrapWithLanguage(base: Context): Context {
            val language = languageOf(base.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
            if (language == AppLanguage.SYSTEM) return base
            val locale = Locale.forLanguageTag(language.tag)
            Locale.setDefault(locale)
            val config = Configuration(base.resources.configuration).apply { setLocales(LocaleList(locale)) }
            return base.createConfigurationContext(config)
        }
    }
}
