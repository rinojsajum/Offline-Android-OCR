package com.zendalona.zTextGrab;

import android.os.Bundle;
import android.preference.MultiSelectListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;

import androidx.annotation.Nullable;

import java.util.Set;


public class LanguageSetupFragment extends PreferenceFragment {

    // A variable to hold the selected language codes in memory
    private Set<String> selectedLanguageCodes = null;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.setup_preferences);

        // Find the language preference by its key
        final MultiSelectListPreference languagePreference = (MultiSelectListPreference) findPreference("key_ocr_language_preference");

        if (languagePreference != null) {
            // Set a listener that fires *immediately* when the user's selection changes
            languagePreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    // The newValue is the Set of selected language codes (e.g., ["eng", "ben"])
                    // We store this directly in our memory variable.
                    selectedLanguageCodes = (Set<String>) newValue;
                    return true; // Return true to allow the preference to be saved
                }
            });
        }
    }

    /**
     * A public method that the Activity can call to get the most recent selection.
     */
    public Set<String> getSelectedLanguageCodes() {
        // If the user hasn't changed anything, read the current value just in case.
        if (selectedLanguageCodes == null) {
            MultiSelectListPreference pref = (MultiSelectListPreference) findPreference("key_ocr_language_preference");
            if (pref != null) {
                return pref.getValues();
            }
            return null;
        }
        return selectedLanguageCodes;
    }
}