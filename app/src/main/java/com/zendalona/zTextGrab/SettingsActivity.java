package com.zendalona.zTextGrab;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;

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

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeActionContentDescription("Back");
            getSupportActionBar().setTitle("Settings");
        }

        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings, new SettingsFragment())
                .commit();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
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
            if (key.equals(getString(R.string.key_grayscale_image_ocr)) ||
                    key.equals(getString(R.string.key_enable_cropping)) ||
                    key.equals(getString(R.string.key_persist_data))) {

                boolean newValue = sharedPreferences.getBoolean(key, false);
                String announcement = newValue ? "Enabled" : "Disabled";

                // Send accessibility announcement
                View rootView = getView();
                if (rootView != null) {
                    rootView.announceForAccessibility(announcement);
                }

                Log.d(TAG, key + " changed: " + announcement);
            }

            if (key.equals(getString(R.string.key_language_for_tesseract_multi))) {
                Set<String> selectedLanguages = sharedPreferences.getStringSet(key, null);
                Log.d(TAG, "Language selection changed. New value: " + selectedLanguages);
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

    public static class VariableSettingsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.tesseract_parameter_variable_preference, rootKey);
        }
    }
}
