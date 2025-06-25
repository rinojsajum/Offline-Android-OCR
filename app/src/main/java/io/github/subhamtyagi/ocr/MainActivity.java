package io.github.subhamtyagi.ocr;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.googlecode.tesseract.android.TessBaseAPI;

import com.canhub.cropper.CropImageContract;
import com.canhub.cropper.CropImageContractOptions;
import com.canhub.cropper.CropImageOptions;
import com.canhub.cropper.CropImageView;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
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
    private static boolean isRefresh = false;

    private static final String KEY_FIRST_RUN = "isFirstRun";
    private Set<Language> initialLanguages = null;
    private File tessdataDirectory;

    private String pendingTextToSave = null;
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
    private FloatingActionButton mGalleryFab;
    private FloatingActionButton mPdfFab;
    private FloatingActionButton mSavedFilesFab;
    private LinearLayout mDownloadLayout;
    private TextView mLanguageName;
    private ExecutorService executorService;
    private Handler handler;
    private LinearProgressIndicator mProgressBar;
    private TextView mProgressMessage;
    private AlertDialog loadingDialog;
    private ProgressBar loadingSpinner;
    private LinearProgressIndicator dialogProgressBar;
    private TextView loadingMessage;
    private boolean isProgressBarVisibleInDialog = false;
    private Handler accessibilityHandler;
    private Runnable accessibilityRunnable;
    private volatile String currentAccessibilityMessage;
    private ActivityResultLauncher<CropImageContractOptions> cropImageLauncher;
    private ActivityResultLauncher<Intent> createDocumentLauncher;
    private ActivityResultLauncher<Intent> pickPdfLauncher;
    // =============================================================
    // === FIX #1: Declare the new launcher for the Settings screen.
    // =============================================================
    private ActivityResultLauncher<Intent> settingsLauncher;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final String EXTRA_INITIAL_LANGUAGES = "initial_languages";

        SpUtil.getInstance().init(this);
        boolean isFirstRun = SpUtil.getInstance().getBoolean(KEY_FIRST_RUN, true);

        if (isFirstRun) {
            Log.d(TAG, "First run detected. Launching LanguageSetupActivity.");
            Intent intent = new Intent(this, LanguageSetupActivity.class);
            startActivity(intent);
            finish();
            return;
        }

        if (getIntent().hasExtra(EXTRA_INITIAL_LANGUAGES)) {
            initialLanguages = (Set<Language>) getIntent().getSerializableExtra(EXTRA_INITIAL_LANGUAGES);
            if (initialLanguages != null) {
                Log.d(TAG, "Received initial languages from setup: " + initialLanguages.stream().map(Language::getCode).collect(Collectors.joining(", ")));
            }
        }

        setContentView(R.layout.activity_main);
        Log.d(TAG, "onCreate: Activity created.");

        // =============================================================
        // === FIX #2: Initialize the new Settings launcher.
        // =============================================================
        settingsLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    // This is the code that will run when we come back from Settings.
                    // It's the same logic that the old onActivityResult had.
                    Log.d(TAG, "Returned from settings. Re-initializing OCR.");
                    initializeOCR();
                }
        );
        // =============================================================


        cropImageLauncher = registerForActivityResult(new CropImageContract(), result -> {
            if (result.isSuccessful()) {
                Uri imageUri = result.getUriContent();
                if (imageUri != null) {
                    convertImageToText(imageUri);
                } else {
                    Toast.makeText(this, "Cropped image URI is null.", Toast.LENGTH_SHORT).show();
                }
            } else {
                Exception error = result.getError();
                Toast.makeText(this, "Image cropping failed: " + (error != null ? error.getMessage() : "Unknown error"), Toast.LENGTH_LONG).show();
            }
        });

        createDocumentLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Uri uri = result.getData().getData();
                if (uri != null && pendingTextToSave != null) {
                    writeTextToUri(uri, pendingTextToSave);
                    pendingTextToSave = null;
                } else {
                    Toast.makeText(this, "Failed to get URI for saving or no text pending.", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "File save cancelled or failed.", Toast.LENGTH_SHORT).show();
                pendingTextToSave = null;
            }
        });

        pickPdfLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Uri pdfUri = result.getData().getData();
                if (pdfUri != null) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        processPdf(pdfUri);
                    } else {
                        Toast.makeText(this, "PDF processing requires Android 5.0 (Lollipop) or higher.", Toast.LENGTH_LONG).show();
                    }
                } else {
                    Toast.makeText(this, "PDF URI is null from picker result.", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "PDF selection cancelled or failed.", Toast.LENGTH_SHORT).show();
            }
        });

        mImageView = findViewById(R.id.source_image);
        mProgressIndicator = findViewById(R.id.progress_indicator);
        mSwipeRefreshLayout = findViewById(R.id.swipe_to_refresh);
        mGalleryFab = findViewById(R.id.btn_scan);
        mLanguageName = findViewById(R.id.language_name1);
        mProgressBar = findViewById(R.id.progress_bar);
        mProgressMessage = findViewById(R.id.progress_message);
        mDownloadLayout = findViewById(R.id.download_layout);
        mSavedFilesFab = findViewById(R.id.btn_saved_files);
        mPdfFab = findViewById(R.id.btn_pdf);
        executorService = Executors.newFixedThreadPool(1);
        handler = new Handler(Looper.getMainLooper());
        initAccessibilityAnnouncer();
        initDirectories();
        initializeOCR();
        initViews();
    }

    private void initViews() {
        if (mGalleryFab != null) {
            mGalleryFab.setOnClickListener(v -> {
                if (isNoLanguagesDataMissingFromSet(Utils.getTrainingDataLanguages(this))) {
                    if (mImageTextReader != null) {
                        selectImage();
                    } else {
                        initializeOCR();
                    }
                } else {
                    downloadLanguageData(Utils.getTrainingDataLanguages(this));
                }
            });
        }
        if (mPdfFab != null) {
            mPdfFab.setOnClickListener(v -> openPdfPicker());
        }
        if (mSavedFilesFab != null) {
            mSavedFilesFab.setOnClickListener(v -> openSavedFilesFolder());
        }
        mSwipeRefreshLayout.setOnRefreshListener(() -> {
            if (isNoLanguagesDataMissingFromSet(Utils.getTrainingDataLanguages(this))) {
                if (mImageTextReader != null) {
                    Drawable drawable = mImageView.getDrawable();
                    if (drawable instanceof BitmapDrawable) {
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
                downloadLanguageData(Utils.getTrainingDataLanguages(this));
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
        Set<Language> languages = Utils.getTrainingDataLanguages(this);
        mLanguageName.setText(languages.stream().map(Language::getName).collect(Collectors.joining(", ")));
    }

    private void openPdfPicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/pdf");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        pickPdfLauncher.launch(Intent.createChooser(intent, "Select PDF"));
    }

    private void initDirectories() {
        String[] dirNames = {"best", "fast", "standard"};
        for (String dirName : dirNames) {
            File dir = new File(getExternalFilesDir(dirName), "tessdata");
            if (!dir.exists()) dir.mkdirs();
            if (dirName.equals("best")) dirBest = dir.getParentFile();
            if (dirName.equals("fast")) dirFast = dir.getParentFile();
            if (dirName.equals("standard")) dirStandard = dir.getParentFile();
        }
    }

    private void initializeOCR() {
        Log.d(TAG, "initializeOCR: Initializing OCR engine.");
        Set<Language> languages = (initialLanguages != null) ? initialLanguages : Utils.getTrainingDataLanguages(this);
        initialLanguages = null;
        if (languages == null) languages = new HashSet<>();

        mLanguageName.setText(languages.stream().map(Language::getName).collect(Collectors.joining(", ")));

        mTrainingDataType = Utils.getTrainingDataType();
        mPageSegMode = Utils.getPageSegMode();
        parameters = Utils.getAllParameters();
        Log.d(TAG, "OCR settings: Type=" + mTrainingDataType + ", PageSegMode=" + mPageSegMode + ", Languages=" + languages.stream().map(Language::getCode).collect(Collectors.joining(", ")));

        switch (mTrainingDataType) {
            case "best": tessdataDirectory = dirBest; break;
            case "standard": tessdataDirectory = dirStandard; break;
            default: tessdataDirectory = dirFast; break;
        }
        currentDirectory = new File(tessdataDirectory, "tessdata");

        if (isNoLanguagesDataMissingFromSet(languages)) {
            startImageTextReaderThread(languages);
        } else {
            downloadLanguageData(languages);
        }
    }

    private void startImageTextReaderThread(Set<Language> languages) {
        new Thread(() -> {
            try {
                if (mImageTextReader != null) mImageTextReader.tearDownEverything();
                mImageTextReader = ImageTextReader.getInstance(
                        tessdataDirectory.getAbsolutePath(), languages,
                        mPageSegMode, parameters,
                        Utils.isExtraParameterSet(), MainActivity.this);
                if (mImageTextReader != null && !mImageTextReader.isSuccess()) {
                    handler.post(() -> handleReaderException(languages));
                }
            } catch (Exception e) {
                handler.post(() -> handleReaderException(languages));
            }
        }).start();
    }

    private void handleReaderException(Set<Language> languages) {
        String langCode = languages.stream().map(Language::getCode).collect(Collectors.joining("+"));
        new File(currentDirectory, String.format(Constants.LANGUAGE_CODE, langCode)).delete();
        mImageTextReader = null;
    }

    private void downloadLanguageData(Set<Language> languagesToProcess) {
        Set<Language> missingLanguage = new HashSet<>();
        if (!Utils.isNetworkAvailable(getApplication())) {
            Toast.makeText(this, getString(R.string.you_are_not_connected_to_internet), Toast.LENGTH_SHORT).show();
            return;
        }
        for (Language l : languagesToProcess) {
            if (isLanguageDataMissing(mTrainingDataType, l)) missingLanguage.add(l);
        }
        if (missingLanguage.isEmpty()) {
            startImageTextReaderThread(languagesToProcess);
            return;
        }
        String missingLangName = missingLanguage.stream().map(Language::getName).collect(Collectors.joining(", "));
        String msg = String.format(getString(R.string.download_description), missingLangName);
        dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.training_data_missing)
                .setCancelable(false)
                .setMessage(msg)
                .setPositiveButton(R.string.yes, (d, w) -> {
                    d.cancel();
                    executorService.submit(new DownloadTraining(mTrainingDataType, missingLanguage));
                })
                .setNegativeButton(R.string.no, (d, w) -> d.cancel())
                .create();
        dialog.show();
    }

    private boolean isNoLanguagesDataMissingFromSet(Set<Language> languagesToCheck) {
        if (languagesToCheck == null) return true;
        for (Language language : languagesToCheck) {
            if (isLanguageDataMissing(mTrainingDataType, language)) return false;
        }
        return true;
    }

    private boolean isLanguageDataMissing(@NonNull String dataType, @NonNull Language language) {
        File checkDir;
        switch (dataType) {
            case "best": checkDir = new File(dirBest, "tessdata"); break;
            case "standard": checkDir = new File(dirStandard, "tessdata"); break;
            default: checkDir = new File(dirFast, "tessdata"); break;
        }
        return !new File(checkDir, String.format(Constants.LANGUAGE_CODE, language.getCode())).exists();
    }

    private void selectImage() {
        CropImageOptions options = new CropImageOptions();
        options.guidelines = CropImageView.Guidelines.ON;
        cropImageLauncher.launch(new CropImageContractOptions(null, options));
    }

    private void convertImageToText(Uri imageUri) {
        Bitmap bitmap = null;
        try {
            bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), imageUri);
        } catch (IOException e) {
            Toast.makeText(this, "Failed to load image: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
        if (bitmap != null) {
            mImageView.setImageURI(imageUri);
            executorService.submit(new ConvertImageToText(bitmap));
        }
    }

    // This method is now only for legacy purposes and can be cleaned up.
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // The logic for settings is now handled by the settingsLauncher.
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAccessibilityAnnouncements();
        if (loadingDialog != null && loadingDialog.isShowing()) loadingDialog.dismiss();
        if (executorService != null) executorService.shutdownNow();
        if (dialog != null) dialog.dismiss();
        if (mImageTextReader != null) mImageTextReader.tearDownEverything();
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        MenuItem showHistoryItem = menu.findItem(R.id.action_history);
        showHistoryItem.setVisible(Utils.isPersistData());
        return true;
    }

    // =============================================================
    // === FIX #3: Use the new launcher in onOptionsItemSelected.
    // =============================================================
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            // Use the modern launcher instead of the old method.
            Intent intent = new Intent(this, SettingsActivity.class);
            settingsLauncher.launch(intent);
            return true;
        } else if (id == R.id.action_history) {
            showOCRResult(Utils.getLastUsedText());
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    // =============================================================

    @Override
    public void onProgressValues(final TessBaseAPI.ProgressValues progressValues) {
        int progress = (int) (progressValues.getPercent() * 1.46);
        runOnUiThread(() -> {
            mProgressIndicator.setProgress(progress);
            if (loadingDialog != null && loadingDialog.isShowing() && !isProgressBarVisibleInDialog) {
                if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE);
                if (dialogProgressBar != null) dialogProgressBar.setVisibility(View.VISIBLE);
                if (loadingMessage != null) {
                    loadingMessage.setText(getString(R.string.recognizing_text));
                    currentAccessibilityMessage = getString(R.string.recognizing_text);
                }
                isProgressBarVisibleInDialog = true;
            }
            if (dialogProgressBar != null) dialogProgressBar.setProgress(progress);
        });
    }

    public void saveBitmapToStorage(Bitmap bitmap) {
        try (FileOutputStream fileOutputStream = openFileOutput("last_file.jpeg", Context.MODE_PRIVATE)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 30, fileOutputStream);
        } catch (IOException e) {
            Log.e(TAG, "saveBitmapToStorage: Failed to save bitmap: " + e.getLocalizedMessage(), e);
        }
    }

    public Bitmap loadBitmapFromStorage() {
        Bitmap bitmap = null;
        try (FileInputStream fileInputStream = openFileInput("last_file.jpeg")) {
            bitmap = BitmapFactory.decodeStream(fileInputStream);
        } catch (IOException e) {
            Log.e(TAG, "loadBitmapFromStorage: Failed to load bitmap: " + e.getLocalizedMessage(), e);
        }
        return bitmap;
    }

    public void showOCRResult(String text) {
        if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
            BottomSheetResultsFragment.newInstance(text).show(getSupportFragmentManager(), "bottomSheetResultsFragment");
            showSaveTextDialog(text);
        }
    }

    private void showSaveTextDialog(final String extractedText) {
        new AlertDialog.Builder(this)
                .setTitle("Save Text")
                .setMessage("Do you want to save the extracted text to a public location?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    pendingTextToSave = extractedText;
                    String suggestedFileName = "OCR_Result_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(new Date()) + ".txt";
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_TITLE, suggestedFileName);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADocuments"));
                    }
                    createDocumentLauncher.launch(intent);
                })
                .setNegativeButton("No", (dialog, which) -> pendingTextToSave = null)
                .setCancelable(true)
                .show();
    }

    private void writeTextToUri(Uri uri, String textToSave) {
        executorService.submit(() -> {
            try (OutputStream outputStream = getContentResolver().openOutputStream(uri)) {
                if (outputStream != null) {
                    outputStream.write(textToSave.getBytes());
                    outputStream.write("\n\n---\n\n".getBytes());
                    handler.post(() -> Toast.makeText(MainActivity.this, "Text saved successfully.", Toast.LENGTH_LONG).show());
                } else {
                    handler.post(() -> Toast.makeText(MainActivity.this, "Failed to open output stream for saving.", Toast.LENGTH_SHORT).show());
                }
            } catch (IOException e) {
                handler.post(() -> Toast.makeText(MainActivity.this, "Error saving text: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void openSavedFilesFolder() {
        startActivity(new Intent(MainActivity.this, SavedResultsActivity.class));
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void processPdf(Uri uri) {
        executorService.execute(() -> {
            try (ParcelFileDescriptor fileDescriptor = getContentResolver().openFileDescriptor(uri, "r");
                 PdfRenderer renderer = new PdfRenderer(fileDescriptor)) {
                int pageCount = renderer.getPageCount();
                StringBuilder fullText = new StringBuilder();
                handler.post(() -> {
                    mProgressBar.setMax(pageCount);
                    mProgressBar.setProgress(0);
                    mProgressBar.setVisibility(View.VISIBLE);
                    mProgressMessage.setText("Processing PDF...");
                });
                for (int i = 0; i < pageCount; i++) {
                    PdfRenderer.Page page = null;
                    Bitmap originalBitmap = null;
                    Bitmap processedBitmap = null;
                    try {
                        page = renderer.openPage(i);
                        float targetDpi = 300f;
                        int width = (int) (page.getWidth() / 72f * targetDpi);
                        int height = (int) (page.getHeight() / 72f * targetDpi);
                        originalBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                        page.render(originalBitmap, new Rect(0, 0, width, height), null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        processedBitmap = preprocessBitmap(originalBitmap);
                        if (mImageTextReader != null) {
                            fullText.append("--- Page ").append(i + 1).append(" ---\n").append(mImageTextReader.getTextFromBitmap(processedBitmap)).append("\n\n");
                        } else {
                            fullText.append("--- Page ").append(i + 1).append(" ---\nOCR failed: ImageTextReader not ready.\n\n");
                        }
                    } finally {
                        if (processedBitmap != null) processedBitmap.recycle();
                        if (originalBitmap != null) originalBitmap.recycle();
                        if (page != null) page.close();
                    }
                    final int currentProgress = i + 1;
                    handler.post(() -> mProgressBar.setProgress(currentProgress));
                }
                handler.post(() -> {
                    mProgressMessage.setText("OCR Complete!");
                    mProgressBar.setVisibility(View.GONE);
                    showResultDialog(fullText.toString());
                });
            } catch (Exception e) {
                handler.post(() -> Toast.makeText(this, "An unexpected error occurred during PDF processing.", Toast.LENGTH_LONG).show());
            }
        });
    }

    private Bitmap preprocessBitmap(Bitmap bmpOriginal) {
        int width = bmpOriginal.getWidth(), height = bmpOriginal.getHeight();
        Bitmap bmpGrayscale = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmpGrayscale);
        Paint paint = new Paint();
        ColorMatrix colorMatrix = new ColorMatrix();
        colorMatrix.setSaturation(0);
        paint.setColorFilter(new ColorMatrixColorFilter(colorMatrix));
        canvas.drawBitmap(bmpOriginal, 0, 0, paint);
        Bitmap bmpBinarized = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[width * height];
        bmpGrayscale.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = pixels[y * width + x];
                pixels[y * width + x] = Color.red(pixel) < 128 ? Color.BLACK : Color.WHITE;
            }
        }
        bmpBinarized.setPixels(pixels, 0, width, 0, 0, width, height);
        bmpGrayscale.recycle();
        return bmpBinarized;
    }

    private void showResultDialog(String text) {
        new AlertDialog.Builder(this).setTitle("OCR Result").setMessage(text).setPositiveButton("OK", null).show();
    }

    private void initAccessibilityAnnouncer() {
        accessibilityHandler = new Handler(Looper.getMainLooper());
        accessibilityRunnable = () -> {
            if (loadingDialog != null && loadingDialog.isShowing() && loadingMessage != null) {
                loadingMessage.announceForAccessibility(currentAccessibilityMessage);
                accessibilityHandler.postDelayed(accessibilityRunnable, 2500);
            }
        };
    }

    private void startAccessibilityAnnouncements(String initialMessage) {
        currentAccessibilityMessage = initialMessage;
        if (accessibilityHandler != null) {
            accessibilityHandler.removeCallbacks(accessibilityRunnable);
            accessibilityHandler.post(accessibilityRunnable);
        }
    }

    private void stopAccessibilityAnnouncements() {
        if (accessibilityHandler != null) accessibilityHandler.removeCallbacks(accessibilityRunnable);
    }

    private void showLoadingDialog() {
        if (loadingDialog != null && loadingDialog.isShowing()) return;
        isProgressBarVisibleInDialog = false;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_loading, null);
        loadingSpinner = dialogView.findViewById(R.id.loading_spinner);
        dialogProgressBar = dialogView.findViewById(R.id.loading_progress_bar);
        loadingMessage = dialogView.findViewById(R.id.loading_message);
        if (loadingSpinner != null) loadingSpinner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (dialogProgressBar != null) dialogProgressBar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        String initialMessage = getString(R.string.processing_image);
        if (loadingMessage != null) loadingMessage.setText(initialMessage);
        if (loadingSpinner != null) loadingSpinner.setVisibility(View.VISIBLE);
        if (dialogProgressBar != null) dialogProgressBar.setVisibility(View.GONE);
        builder.setView(dialogView);
        builder.setCancelable(false);
        loadingDialog = builder.create();
        loadingDialog.show();
        startAccessibilityAnnouncements(initialMessage);
    }

    private void dismissLoadingDialog(@Nullable String finalAnnouncement) {
        stopAccessibilityAnnouncements();
        if (loadingDialog != null && loadingDialog.isShowing()) {
            if (finalAnnouncement != null && loadingMessage != null) {
                loadingMessage.announceForAccessibility(finalAnnouncement);
            }
            loadingDialog.dismiss();
        }
        loadingDialog = null;
    }

    private class ConvertImageToText implements Runnable {
        private Bitmap bitmap;
        public ConvertImageToText(Bitmap bitmap) { this.bitmap = bitmap; }
        @Override
        public void run() {
            handler.post(() -> {
                showLoadingDialog();
                mProgressIndicator.setProgress(0);
                mProgressIndicator.setVisibility(View.VISIBLE);
                animateImageViewAlpha(0.2f);
            });
            if (!isRefresh && Utils.isPreProcessImage()) bitmap = Utils.preProcessBitmap(bitmap);
            isRefresh = false;
            saveBitmapToStorage(bitmap);
            String text = (mImageTextReader != null) ? mImageTextReader.getTextFromBitmap(bitmap) : "OCR Engine not initialized.";
            final String finalCleanText = (text != null) ? Html.fromHtml(text).toString().trim() : "";
            final int accuracy = (mImageTextReader != null) ? mImageTextReader.getAccuracy() : -1;
            handler.post(() -> {
                dismissLoadingDialog(getString(R.string.processing_completed));
                mProgressIndicator.setVisibility(View.GONE);
                animateImageViewAlpha(1f);
                showOCRResult(finalCleanText);
                Toast.makeText(MainActivity.this, "With Confidence: " + accuracy + "%", Toast.LENGTH_SHORT).show();
                Utils.putLastUsedText(finalCleanText);
                updateImageView();
            });
        }
        private void animateImageViewAlpha(float alpha) { mImageView.animate().alpha(alpha).setDuration(450).start(); }
        private void updateImageView() {
            Bitmap b = loadBitmapFromStorage();
            if (b != null) mImageView.setImageBitmap(b);
        }
    }

    private class DownloadTraining implements Runnable {
        private final String dataType;
        private final Set<Language> languages;
        public DownloadTraining(String dataType, Set<Language> langs) {
            this.dataType = dataType;
            this.languages = (langs != null) ? langs : new HashSet<>();
        }
        @Override
        public void run() {
            handler.post(() -> {
                mProgressMessage.setText(getString(R.string.downloading_language));
                mDownloadLayout.setVisibility(View.VISIBLE);
                mProgressBar.setVisibility(View.GONE);
            });
            boolean success = true;
            for (Language lang : languages) {
                if (!downloadTrainingData(dataType, lang.getCode())) {
                    success = false;
                    break;
                }
            }
            final boolean finalSuccess = success;
            handler.post(() -> {
                mDownloadLayout.setVisibility(View.GONE);
                if (finalSuccess) {
                    mLanguageName.setText(this.languages.stream().map(Language::getName).collect(Collectors.joining(", ")));
                    startImageTextReaderThread(this.languages);
                    Toast.makeText(MainActivity.this, "Download complete!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, "Download failed", Toast.LENGTH_SHORT).show();
                }
            });
        }
        @SuppressLint("DefaultLocale")
        private boolean downloadTrainingData(String dataType, String lang) {
            String downloadURL = getDownloadUrl(dataType, lang);
            if (downloadURL == null) return false;
            try {
                URL url = new URL(downloadURL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(false);
                downloadURL = followRedirects(conn, downloadURL);
                conn = (HttpURLConnection) new URL(downloadURL).openConnection();
                conn.connect();
                int totalContentSize = conn.getContentLength();
                if (totalContentSize <= 0) return false;

                final String size = Utils.getSize(totalContentSize);

                handler.post(() -> {
                    mProgressBar.setVisibility(View.VISIBLE);
                    mProgressMessage.setText(String.format("0%s%s", getString(R.string.percentage_downloaded), size));
                    mProgressBar.setProgress(0);
                });
                File destFile = new File(currentDirectory, String.format(Constants.LANGUAGE_CODE, lang));
                try (InputStream input = new BufferedInputStream(conn.getInputStream()); OutputStream output = new FileOutputStream(destFile)) {
                    byte[] data = new byte[6 * 1024];
                    int downloaded = 0;
                    int count;
                    while ((count = input.read(data)) != -1) {
                        output.write(data, 0, count);
                        downloaded += count;
                        final int percentage = (downloaded * 100) / totalContentSize;
                        handler.post(() -> {
                            mProgressBar.setProgress(percentage);
                            mProgressMessage.setText(String.format("%d%s%s.", percentage, getString(R.string.percentage_downloaded), size));
                        });
                    }
                }
                return true;
            } catch (IOException e) {
                return false;
            }
        }
        private String getDownloadUrl(String dataType, String lang) {
            switch (dataType) {
                case "best": return lang.equals("akk") ? Constants.TESSERACT_DATA_DOWNLOAD_URL_AKK_BEST : lang.equals("eqo") ? Constants.TESSERACT_DATA_DOWNLOAD_URL_EQU : String.format(Constants.TESSERACT_DATA_DOWNLOAD_URL_BEST, lang);
                case "standard": return lang.equals("akk") ? Constants.TESSERACT_DATA_DOWNLOAD_URL_AKK_STANDARD : lang.equals("eqo") ? Constants.TESSERACT_DATA_DOWNLOAD_URL_EQU : String.format(Constants.TESSERACT_DATA_DOWNLOAD_URL_STANDARD, lang);
                default: return lang.equals("akk") ? Constants.TESSERACT_DATA_DOWNLOAD_URL_AKK_FAST : Constants.TESSERACT_DATA_DOWNLOAD_URL_EQU;
            }
        }
        private String followRedirects(HttpURLConnection conn, String downloadURL) throws IOException {
            int redirectCount = 0;
            while (true) {
                int responseCode = conn.getResponseCode();
                if (responseCode >= 300 && responseCode <= 399) {
                    String location = conn.getHeaderField("Location");
                    if (location == null) return downloadURL;
                    downloadURL = new URL(new URL(downloadURL), location).toExternalForm();
                    conn.disconnect();
                    conn = (HttpURLConnection) new URL(downloadURL).openConnection();
                    conn.setInstanceFollowRedirects(false);
                    if (++redirectCount > 5) throw new IOException("Too many redirects");
                } else {
                    break;
                }
            }
            return downloadURL;
        }
    }
}