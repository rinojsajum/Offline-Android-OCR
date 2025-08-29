package com.zendalona.zTextGrab;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import java.util.Set;


public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings, new SettingsFragment())
                .commit();
    }

    public static class SettingsFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {
        private static final String TAG = "SettingsFragment"; // Added TAG for logging

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.main_preferences, rootKey);
        }

        @Override
        public void onResume() {
            super.onResume();
            // Register the listener for shared preference changes
            getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
            Log.d(TAG, "onResume: SharedPreferenceChangeListener registered.");
        }

        @Override
        public void onPause() {
            super.onPause();
            // Unregister the listener for shared preference changes to prevent memory leaks
            getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
            Log.d(TAG, "onPause: SharedPreferenceChangeListener unregistered.");
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            // Listen for changes to the language selection preference
            if (key.equals(getString(R.string.key_language_for_tesseract_multi))) {
                Set<String> selectedLanguages = sharedPreferences.getStringSet(key, null);
                Log.d(TAG, "onSharedPreferenceChanged: Language selection changed. New value: " + selectedLanguages);

                // This log will help us confirm if the preference is correctly saving changes.
                // The actual download logic is handled in MainActivity's onResume/initializeOCR
                // when returning from this activity.
            }
        }

        @Override
        public boolean onPreferenceTreeClick(Preference preference) {
            Log.d(TAG, "onPreferenceTreeClick: Preference clicked: " + preference.getKey());

            // Handle About preference click
            if (preference.getKey().equals("key_about")) {
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle("About z.TextGrab")
                        .setMessage(getString(R.string.about_text)) // text from strings.xml
                        .setPositiveButton("OK", null)
                        .show();
                return true; // tell Android we handled this click
            }

            // If the clicked preference is the language selection, log its state
            if (preference.getKey().equals(getString(R.string.key_language_for_tesseract_multi))) {
                MultiSelectListPreference langPreference = (MultiSelectListPreference) preference;
                Log.d(TAG, "Language Preference clicked. Current values: " + langPreference.getValues());
            }

            return super.onPreferenceTreeClick(preference);
        }

    }

    public static class AdvanceSettingsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.image_enhancement_preferences, rootKey);
        }
    }

    public static class VariableSettingsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.tesseract_parameter_variable_preference, rootKey);
        }
    }
}
