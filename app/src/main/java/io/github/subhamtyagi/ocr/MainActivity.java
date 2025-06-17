package io.github.subhamtyagi.ocr;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Html;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.googlecode.tesseract.android.TessBaseAPI;
import com.theartofdev.edmodo.cropper.CropImage;
import com.theartofdev.edmodo.cropper.CropImageView;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import io.github.subhamtyagi.ocr.ocr.ImageTextReader;
import io.github.subhamtyagi.ocr.utils.Constants;
import io.github.subhamtyagi.ocr.utils.Language;
import io.github.subhamtyagi.ocr.utils.SpUtil;
import io.github.subhamtyagi.ocr.utils.Utils;

public class MainActivity extends AppCompatActivity implements TessBaseAPI.ProgressNotifier {

    public static final String TAG = "MainActivity";
    private static final int REQUEST_CODE_SETTINGS = 797;
    private static boolean isRefresh = false;

    private File dirBest;
    private File dirStandard;
    private File dirFast;
    private File currentDirectory;
    private ImageTextReader mImageTextReader;
    private String mTrainingDataType;
    private int mPageSegMode;
    private Map<String, String> parameters;
    private AlertDialog dialog;
    private ImageView mImageView;
    private LinearProgressIndicator mProgressIndicator;
    private SwipeRefreshLayout mSwipeRefreshLayout;
    private FloatingActionButton mFloatingActionButton;
    private LinearLayout mDownloadLayout;
    private TextView mLanguageName;
    private ExecutorService executorService;
    private Handler handler;
    private LinearProgressIndicator mProgressBar;
    private TextView mProgressMessage;

    // --- MODIFIED FOR ACCESSIBILITY ---
    private AlertDialog loadingDialog;
    private ProgressBar loadingSpinner;
    private LinearProgressIndicator dialogProgressBar;
    private TextView loadingMessage;
    private boolean isProgressBarVisibleInDialog = false;
    // --- END MODIFICATION ---

    // =========================================================================================
    // === NEW ACCESSIBILITY ANNOUNCER BLOCK ===================================================
    // =========================================================================================
    private Handler accessibilityHandler;
    private Runnable accessibilityRunnable;
    private volatile String currentAccessibilityMessage;
    // =========================================================================================


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SpUtil.getInstance().init(this);

        mImageView = findViewById(R.id.source_image);
        mProgressIndicator = findViewById(R.id.progress_indicator);
        mSwipeRefreshLayout = findViewById(R.id.swipe_to_refresh);
        mFloatingActionButton = findViewById(R.id.btn_scan);
        mLanguageName = findViewById(R.id.language_name1);

        mProgressBar = findViewById(R.id.progress_bar);
        mProgressMessage = findViewById(R.id.progress_message);
        mDownloadLayout = findViewById(R.id.download_layout);

        executorService = Executors.newFixedThreadPool(1);
        handler = new Handler(Looper.getMainLooper());

        // --- NEW ---
        initAccessibilityAnnouncer();
        // --- END NEW ---

        initDirectories();
        initializeOCR();
        initViews();
    }

    private void initViews() {
        mFloatingActionButton.setOnClickListener(v -> {
            if (isNoLanguagesDataMissingFromSet()) {
                if (mImageTextReader != null) {
                    selectImage();
                } else {
                    initializeOCR();
                }
            } else {
                downloadLanguageData();
            }
        });
        mSwipeRefreshLayout.setOnRefreshListener(() -> {
            if (isNoLanguagesDataMissingFromSet()) {
                if (mImageTextReader != null) {
                    Drawable drawable = mImageView.getDrawable();
                    if (drawable != null) {
                        Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
                        if (bitmap != null) {
                            isRefresh = true;
                            executorService.submit(new ConvertImageToText(bitmap));
                        }
                    }
                } else {
                    initializeOCR();
                }
            } else {
                downloadLanguageData();
            }
            mSwipeRefreshLayout.setRefreshing(false);
        });
        if (Utils.isPersistData()) {
            Bitmap bitmap = loadBitmapFromStorage();
            if (bitmap != null) {
                mImageView.setImageBitmap(bitmap);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mLanguageName.setText(Utils.getTrainingDataLanguages(this).stream().map(Language::getName).collect(Collectors.joining(", ")));
    }

    private void initDirectories() {
        String[] dirNames = {"best", "fast", "standard"};
        for (String dirName : dirNames) {
            File dir = new File(getExternalFilesDir(dirName), "tessdata");
            if (dir.mkdirs() || dir.isDirectory()) {
                switch (dirName) {
                    case "best":
                        dirBest = dir.getParentFile();
                        break;
                    case "fast":
                        dirFast = dir.getParentFile();
                        break;
                    case "standard":
                        dirStandard = dir.getParentFile();
                        break;
                }
            }
        }
        currentDirectory = new File(dirStandard, "tessdata");
    }

    private void initializeOCR() {
        Set<Language> languages = Utils.getTrainingDataLanguages(this);
        File cf;
        mTrainingDataType = Utils.getTrainingDataType();
        mPageSegMode = Utils.getPageSegMode();
        parameters = Utils.getAllParameters();
        switch (mTrainingDataType) {
            case "best":
                currentDirectory = new File(dirBest, "tessdata");
                cf = dirBest;
                break;
            case "standard":
                cf = dirStandard;
                currentDirectory = new File(dirStandard, "tessdata");
                break;
            default:
                cf = dirFast;
                currentDirectory = new File(dirFast, "tessdata");
        }
        if (isNoLanguagesDataMissingFromSet()) {
            startImageTextReaderThread(cf, languages);
        } else {
            downloadLanguageData();
        }
    }

    private void startImageTextReaderThread(File cf, Set<Language> languages) {
        new Thread(() -> {
            try {
                if (mImageTextReader != null) {
                    mImageTextReader.tearDownEverything();
                }
                mImageTextReader = ImageTextReader.getInstance(cf.getAbsolutePath(), languages, mPageSegMode, parameters, Utils.isExtraParameterSet(), MainActivity.this);
                if (mImageTextReader != null && !mImageTextReader.isSuccess()) {
                    handleReaderException(languages);
                }
            } catch (Exception e) {
                handleReaderException(languages);
            }
        }).start();
    }

    private void handleReaderException(Set<Language> languages) {
        File destFile = new File(currentDirectory, String.format(Constants.LANGUAGE_CODE, languages));
        destFile.delete();
        mImageTextReader = null;
    }

    private void downloadLanguageData() {
        Set<Language> missingLanguage = new HashSet<>();
        Set<Language> languages = Utils.getTrainingDataLanguages(this);
        if (!Utils.isNetworkAvailable(getApplication())) {
            Toast.makeText(this, getString(R.string.you_are_not_connected_to_internet), Toast.LENGTH_SHORT).show();
            return;
        }
        for (Language l : languages) {
            if (isLanguageDataMissing(mTrainingDataType, l)) {
                missingLanguage.add(l);
            }
        }
        String missingLangName = missingLanguage.stream().map(Language::getName).collect(Collectors.joining(", "));
        String msg = String.format(getString(R.string.download_description), missingLangName);
        dialog = new AlertDialog.Builder(this).setTitle(R.string.training_data_missing).setCancelable(false).setMessage(msg).setPositiveButton(R.string.yes, (dialog, which) -> {
            dialog.cancel();
            executorService.submit(new DownloadTraining(mTrainingDataType, missingLanguage));
        }).setNegativeButton(R.string.no, (dialog, which) -> dialog.cancel()).create();
        dialog.show();
    }

    private boolean isNoLanguagesDataMissingFromSet() {
        final String dataType = mTrainingDataType;
        Set<Language> languages = Utils.getTrainingDataLanguages(this);
        for (Language language : languages) {
            if (isLanguageDataMissing(dataType, language)) return false;
        }
        return true;
    }

    private boolean isLanguageDataMissing(final @NonNull String dataType, final @NonNull Language language) {
        switch (dataType) {
            case "best":
                currentDirectory = new File(dirBest, "tessdata");
                break;
            case "standard":
                currentDirectory = new File(dirStandard, "tessdata");
                break;
            default:
                currentDirectory = new File(dirFast, "tessdata");
        }
        return !new File(currentDirectory, String.format(Constants.LANGUAGE_CODE, language.getCode())).exists();
    }

    private void selectImage() {
        CropImage.activity().setGuidelines(CropImageView.Guidelines.ON).start(this);
    }

    private void convertImageToText(Uri imageUri) {
        Bitmap bitmap = null;
        try {
            bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), imageUri);
        } catch (IOException e) {
            Log.e(TAG, "convertImageToText: " + e.getLocalizedMessage());
        }
        mImageView.setImageURI(imageUri);
        executorService.submit(new ConvertImageToText(bitmap));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_SETTINGS) {
            initializeOCR();
        }
        if (resultCode == RESULT_OK) {
            if (requestCode == CropImage.CROP_IMAGE_ACTIVITY_REQUEST_CODE) {
                if (isNoLanguagesDataMissingFromSet()) {
                    CropImage.ActivityResult result = CropImage.getActivityResult(data);
                    if (result != null) {
                        convertImageToText(result.getUri());
                    }
                } else {
                    initializeOCR();
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // --- MODIFIED FOR ACCESSIBILITY ---
        // Clean up announcer and dialog to prevent memory leaks
        stopAccessibilityAnnouncements();
        if (loadingDialog != null && loadingDialog.isShowing()) {
            loadingDialog.dismiss();
        }
        loadingDialog = null;
        // --- END MODIFICATION ---

        executorService.shutdownNow();
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
        if (mImageTextReader != null) mImageTextReader.tearDownEverything();
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        MenuItem showHistoryItem = menu.findItem(R.id.action_history);
        showHistoryItem.setVisible(Utils.isPersistData());
        return true;
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_CODE_SETTINGS);
        } else if (id == R.id.action_history) {
            showOCRResult(Utils.getLastUsedText());
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onProgressValues(final TessBaseAPI.ProgressValues progressValues) {
        int progress = (int) (progressValues.getPercent() * 1.46);
        runOnUiThread(() -> {
            mProgressIndicator.setProgress(progress);

            if (loadingDialog != null && loadingDialog.isShowing()) {
                if (!isProgressBarVisibleInDialog) {
                    if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE);
                    if (dialogProgressBar != null) dialogProgressBar.setVisibility(View.VISIBLE);
                    if (loadingMessage != null) {
                        loadingMessage.setText("Recognizing text");
                    }
                    isProgressBarVisibleInDialog = true;
                }
                if (dialogProgressBar != null) {
                    dialogProgressBar.setProgress(progress);
                }
            }
        });
    }

    public void saveBitmapToStorage(Bitmap bitmap) {
        FileOutputStream fileOutputStream;
        try {
            fileOutputStream = openFileOutput("last_file.jpeg", Context.MODE_PRIVATE);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 30, fileOutputStream);
            fileOutputStream.close();
        } catch (IOException e) {
            Log.e(TAG, "loadBitmapFromStorage: " + e.getLocalizedMessage());
        }
    }

    public Bitmap loadBitmapFromStorage() {
        Bitmap bitmap = null;
        try {
            FileInputStream fileInputStream = openFileInput("last_file.jpeg");
            bitmap = BitmapFactory.decodeStream(fileInputStream);
            fileInputStream.close();
        } catch (IOException e) {
            Log.e(TAG, "loadBitmapFromStorage: " + e.getLocalizedMessage());
        }
        return bitmap;
    }

    public void showOCRResult(String text) {
        if (this.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
            BottomSheetResultsFragment.newInstance(text).show(getSupportFragmentManager(), "bottomSheetResultsFragment");
        }
    }


    // =========================================================================================
    // === NEW ACCESSIBILITY ANNOUNCER METHODS =================================================
    // =========================================================================================

    /**
     * Initializes the handler and runnable for repeated accessibility announcements.
     */
    private void initAccessibilityAnnouncer() {
        accessibilityHandler = new Handler(Looper.getMainLooper());
        accessibilityRunnable = new Runnable() {
            @Override
            public void run() {
                // Announce the message only if the dialog is still showing
                if (loadingDialog != null && loadingDialog.isShowing() && loadingMessage != null) {
                    loadingMessage.announceForAccessibility(currentAccessibilityMessage);
                    // Schedule the next announcement in 2.5 seconds
                    accessibilityHandler.postDelayed(this, 2500);
                }
            }
        };
    }

    /**
     * Starts the periodic accessibility announcements.
     * @param initialMessage The first message to be announced repeatedly.
     */
    private void startAccessibilityAnnouncements(String initialMessage) {
        currentAccessibilityMessage = initialMessage;
        // Remove any pending announcements and start a new cycle immediately.
        if (accessibilityHandler != null) {
            accessibilityHandler.removeCallbacks(accessibilityRunnable);
            accessibilityHandler.post(accessibilityRunnable);
        }
    }

    /**
     * Stops the periodic accessibility announcements.
     */
    private void stopAccessibilityAnnouncements() {
        if (accessibilityHandler != null) {
            accessibilityHandler.removeCallbacks(accessibilityRunnable);
        }
    }

    // =========================================================================================
    // === LOADING DIALOG METHODS (MODIFIED FOR ACCESSIBILITY) =================================
    // =========================================================================================

    private void showLoadingDialog() {
        if (loadingDialog != null && loadingDialog.isShowing()) {
            return;
        }

        isProgressBarVisibleInDialog = false;

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_loading, null);

        loadingSpinner = dialogView.findViewById(R.id.loading_spinner);
        dialogProgressBar = dialogView.findViewById(R.id.loading_progress_bar);
        loadingMessage = dialogView.findViewById(R.id.loading_message);

        // --- NEW ACCESSIBILITY FIX: SILENCE PROGRESS INDICATORS ---
        // This prevents TalkBack from announcing "Progress, 10%" etc.
        if (loadingSpinner != null) {
            loadingSpinner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        if (dialogProgressBar != null) {
            dialogProgressBar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        // --- END OF FIX ---

        String initialMessage = getString(R.string.processing_image);
        if (loadingMessage != null) loadingMessage.setText(initialMessage);
        if (loadingSpinner != null) loadingSpinner.setVisibility(View.VISIBLE);
        if (dialogProgressBar != null) dialogProgressBar.setVisibility(View.GONE);

        builder.setView(dialogView);
        builder.setCancelable(false);

        loadingDialog = builder.create();
        loadingDialog.show();

        // Start repeating the "Processing image" announcement.
        startAccessibilityAnnouncements(initialMessage);
    }

    /**
     * Dismisses the loading dialog and makes a final announcement.
     * @param finalAnnouncement The message to announce once upon completion.
     */
    private void dismissLoadingDialog(@Nullable String finalAnnouncement) {
        // Stop the repeating announcements immediately.
        stopAccessibilityAnnouncements();

        if (loadingDialog != null && loadingDialog.isShowing()) {
            // Announce the final message if provided. This will interrupt any previous speech.
            if (finalAnnouncement != null && loadingMessage != null) {
                loadingMessage.announceForAccessibility(finalAnnouncement);
            }
            loadingDialog.dismiss();
        }

        loadingDialog = null;
        loadingSpinner = null;
        dialogProgressBar = null;
        loadingMessage = null;
    }

    // =========================================================================================
    // === END OF NEW/MODIFIED METHODS =========================================================
    // =========================================================================================


    private class ConvertImageToText implements Runnable {
        private final Bitmap bitmap;

        public ConvertImageToText(Bitmap bitmap) {
            this.bitmap = bitmap;
        }

        @Override
        public void run() {
            // Pre-execute on UI thread
            handler.post(() -> {
                showLoadingDialog();
                mProgressIndicator.setProgress(0);
                mProgressIndicator.setVisibility(View.VISIBLE);
                animateImageViewAlpha(0.2f);
            });

            // Background execution
            if (!isRefresh && Utils.isPreProcessImage()) {
                Utils.preProcessBitmap(this.bitmap);
            }
            isRefresh = false;
            saveBitmapToStorage(this.bitmap);
            String text = mImageTextReader.getTextFromBitmap(this.bitmap);

            // Post-execution on UI thread
            handler.post(() -> {
                // --- MODIFIED: Dismiss loading screen with a final announcement ---
                dismissLoadingDialog(getString(R.string.processing_completed));
                // --- END MODIFICATION ---

                mProgressIndicator.setVisibility(View.GONE);
                animateImageViewAlpha(1f);
                String cleanText = Html.fromHtml(text).toString().trim();
                showOCRResult(cleanText);
                Toast.makeText(MainActivity.this, "With Confidence: " + mImageTextReader.getAccuracy() + "%", Toast.LENGTH_SHORT).show();
                Utils.putLastUsedText(cleanText);
                updateImageView();
            });
        }

        private void animateImageViewAlpha(float alpha) {
            mImageView.animate().alpha(alpha).setDuration(450).start();
        }

        private void updateImageView() {
            Bitmap bitmap = loadBitmapFromStorage();
            if (bitmap != null) {
                mImageView.setImageBitmap(bitmap);
            }
        }
    }

    private class DownloadTraining implements Runnable {
        private final String dataType;
        private final Set<Language> languages;
        private String size;

        public DownloadTraining(String dataType, Set<Language> langs) {
            this.dataType = dataType;
            this.languages = langs;
        }

        @Override
        public void run() {
            handler.post(() -> {
                mProgressMessage.setText(getString(R.string.downloading_language));
                mDownloadLayout.setVisibility(View.VISIBLE);
                mProgressBar.setVisibility(View.GONE);
            });

            final boolean[] success = {true};
            for (Language lang : languages) {
                success[0] = success[0] && downloadTrainingData(dataType, lang.getCode());
            }
            handler.post(() -> {
                mDownloadLayout.setVisibility(View.GONE);
                if (success[0]) {
                    initializeOCR();
                } else {
                    Toast.makeText(MainActivity.this, "Download failed", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @SuppressLint("DefaultLocale")
        private boolean downloadTrainingData(String dataType, String lang) {
            String downloadURL = getDownloadUrl(dataType, lang);
            if (downloadURL == null) {
                return false;
            }
            try {
                URL url = new URL(downloadURL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(false);
                downloadURL = followRedirects(conn, downloadURL);
                conn = (HttpURLConnection) new URL(downloadURL).openConnection();
                conn.connect();
                int totalContentSize = conn.getContentLength();
                if (totalContentSize <= 0) {
                    return false;
                }
                size = Utils.getSize(totalContentSize);

                handler.post(() -> {
                    mProgressBar.setVisibility(View.VISIBLE);
                    mProgressMessage.setText(String.format("0%s%s", getString(R.string.percentage_downloaded), size));
                    mProgressBar.setProgress(0);
                });

                try (InputStream input = new BufferedInputStream(conn.getInputStream()); OutputStream output = new FileOutputStream(new File(currentDirectory, String.format(Constants.LANGUAGE_CODE, lang)))) {
                    byte[] data = new byte[6 * 1024];
                    int downloaded = 0;
                    int count;
                    while ((count = input.read(data)) != -1) {
                        output.write(data, 0, count);
                        downloaded += count;
                        int percentage = (downloaded * 100) / totalContentSize;
                        handler.post(() -> {
                            mProgressBar.setProgress(percentage);
                            mProgressMessage.setText(String.format("%d%s%s.", percentage, getString(R.string.percentage_downloaded), size));
                        });
                    }
                    output.flush();
                }
                return true;
            } catch (IOException e) {
                Log.e(TAG, "Download failed: " + e.getLocalizedMessage());
                return false;
            }
        }

        private String getDownloadUrl(String dataType, String lang) {
            switch (dataType) {
                case "best":
                    return lang.equals("akk") ? Constants.TESSERACT_DATA_DOWNLOAD_URL_AKK_BEST : lang.equals("eqo") ? Constants.TESSERACT_DATA_DOWNLOAD_URL_EQU : String.format(Constants.TESSERACT_DATA_DOWNLOAD_URL_BEST, lang);
                case "standard":
                    return lang.equals("akk") ? Constants.TESSERACT_DATA_DOWNLOAD_URL_AKK_STANDARD : lang.equals("eqo") ? Constants.TESSERACT_DATA_DOWNLOAD_URL_EQU : String.format(Constants.TESSERACT_DATA_DOWNLOAD_URL_STANDARD, lang);
                default:
                    return lang.equals("akk") ? Constants.TESSERACT_DATA_DOWNLOAD_URL_AKK_FAST : lang.equals("eqo") ? Constants.TESSERACT_DATA_DOWNLOAD_URL_EQU : String.format(Constants.TESSERACT_DATA_DOWNLOAD_URL_FAST, lang);
            }
        }

        private String followRedirects(HttpURLConnection conn, String downloadURL) throws IOException {
            while (true) {
                int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_MOVED_PERM || responseCode == HttpURLConnection.HTTP_MOVED_TEMP) {
                    String location = conn.getHeaderField("Location");
                    URL base = new URL(downloadURL);
                    downloadURL = new URL(base, location).toExternalForm();
                    conn = (HttpURLConnection) new URL(downloadURL).openConnection();
                } else {
                    break;
                }
            }
            return downloadURL;
        }
    }
}