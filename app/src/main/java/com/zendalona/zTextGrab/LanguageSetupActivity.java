package com.zendalona.zTextGrab;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.zendalona.zTextGrab.utils.Language;
import com.zendalona.zTextGrab.utils.SpUtil;

import java.util.HashSet;
import java.util.Set;


public class LanguageSetupActivity extends AppCompatActivity {

    private static final String KEY_FIRST_RUN = "isFirstRun";
    public static final String EXTRA_INITIAL_LANGUAGES = "initial_languages";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_language_setup);

        TextView titleView = findViewById(R.id.language_setup_title);
        TextView descriptionView = findViewById(R.id.language_setup_description);
        Button continueButton = findViewById(R.id.button_continue);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(getString(R.string.language_setup_title_first_run));
        }

        titleView.setText(R.string.welcome_message);
        descriptionView.setText(R.string.language_setup_description_first_run);
        continueButton.setText(R.string.launch_language_dialog_continue);

        getFragmentManager().beginTransaction()
                .replace(R.id.language_list_container, new LanguageSetupFragment())
                .commit();

        continueButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LanguageSetupFragment fragment = (LanguageSetupFragment) getFragmentManager().findFragmentById(R.id.language_list_container);

                if (fragment == null) {
                    Toast.makeText(LanguageSetupActivity.this, "Error: Could not get fragment.", Toast.LENGTH_SHORT).show();
                    return;
                }

                Set<String> selectedCodes = fragment.getSelectedLanguageCodes();

                if (selectedCodes == null || selectedCodes.isEmpty()) {
                    Toast.makeText(LanguageSetupActivity.this, "Please select at least one language to continue.", Toast.LENGTH_SHORT).show();
                    return;
                }

                // Get the SharedPreferences editor.
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(LanguageSetupActivity.this);
                SharedPreferences.Editor editor = prefs.edit();

                // FIX: Changed hardcoded key to use the string resource to match Utils.java
                final String PREFERENCE_KEY = getString(R.string.key_language_for_tesseract_multi);

                // Save the set of language codes.
                editor.putStringSet(PREFERENCE_KEY, selectedCodes);
                editor.apply();

                Log.d("LanguageSetup", "Explicitly saved languages to SharedPreferences: " + selectedCodes);

                // Convert codes to Language objects to pass to MainActivity for the initial run.
                Set<Language> selectedLanguages = convertCodesToLanguages(selectedCodes);

                // Mark first run as complete.
                SpUtil.getInstance().putBoolean(KEY_FIRST_RUN, false);

                // Start MainActivity with the correct data.
                Intent intent = new Intent(LanguageSetupActivity.this, MainActivity.class);
                intent.putExtra(EXTRA_INITIAL_LANGUAGES, new HashSet<>(selectedLanguages));
                startActivity(intent);

                finish();
            }
        });
    }

    private Set<Language> convertCodesToLanguages(Set<String> codes) {
        Set<Language> languages = new HashSet<>();
        for (String code : codes) {
            languages.add(new Language(LanguageSetupActivity.this, code));
        }
        return languages;
    }
}