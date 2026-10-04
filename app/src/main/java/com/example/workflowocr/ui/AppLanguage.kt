package com.example.workflowocr

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

enum class AppLanguage(val languageTag: String, @StringRes val labelRes: Int) {
    ENGLISH("en", R.string.settings_language_english),
    POLISH("pl", R.string.settings_language_polish);

    fun select() {
        // AndroidX persists this choice on older phones; Android 13+ stores it in the system.
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
    }

    companion object {
        fun fromLanguageCode(languageCode: String?): AppLanguage =
            if (languageCode == "pl") POLISH else ENGLISH

        fun initializeDefault() {
            // Pick once from the system language. Never overwrite an existing app preference.
            if (AppCompatDelegate.getApplicationLocales().isEmpty) {
                fromLanguageCode(Resources.getSystem().configuration.locales[0]?.language).select()
            }
        }
    }
}
