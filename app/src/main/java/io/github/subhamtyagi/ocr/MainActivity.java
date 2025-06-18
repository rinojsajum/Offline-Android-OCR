package io.github.subhamtyagi.ocr;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color; // Explicitly added as it's used in preprocessBitmap
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
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
// Note: com.theartofdev.edmodo.cropper.CropImage and CropImageView are removed,
// replaced by com.canhub.cropper equivalents for consistency with ActivityResultLauncher.

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
    private static final int REQUEST_CODE_SETTINGS = 797;
    private static boolean isRefresh = false;

    private String pendingTextToSave = null;
    // REQUEST_CODE_PICK_PDF is no longer strictly needed as pickPdfLauncher handles it,
    // but its value is retained for historical context/if an old intent call still existed somewhere.
    private static final int REQUEST_CODE_PICK_PDF = 1001;

    private File dirBest;
    private File dirStandard;
    private File dirFast;
    private File currentDirectory;
    private ImageTextReader mImageTextReader;
    /**
     * TrainingDataType: i.e Best, Standard, Fast
     */
    private String mTrainingDataType;
    private int mPageSegMode;
    private Map<String, String> parameters;
    /**
     * AlertDialog for showing when language data doesn't exists
     */
    private AlertDialog dialog;
    private ImageView mImageView;
    private LinearProgressIndicator mProgressIndicator;
    private SwipeRefreshLayout mSwipeRefreshLayout;

    // Unified FAB declarations for clarity and consistency
    private FloatingActionButton mGalleryFab; // Handles image picking (formerly btn_scan / btn_media_picker)
    private FloatingActionButton mPdfFab;     // Handles PDF picking (formerly btn_pdf)
    private FloatingActionButton mSavedFilesFab; // Handles opening saved files (from development2)

    private LinearLayout mDownloadLayout;
    /**
     * Language name to be displayed
     */
    private TextView mLanguageName;
    private ExecutorService executorService;
    private Handler handler;
    private LinearProgressIndicator mProgressBar;
    private TextView mProgressMessage;

    // ActivityResultLaunchers for modern AndroidX approach (from development2, adapted for server's PDF)
    private ActivityResultLauncher<CropImageContractOptions> cropImageLauncher;
    private ActivityResultLauncher<Intent> createDocumentLauncher;
    private ActivityResultLauncher<Intent> pickPdfLauncher;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.d(TAG, "onCreate: Activity created.");

        SpUtil.getInstance().init(this);

        // Initialize ActivityResultLauncher for image cropping (from development2)
        cropImageLauncher = registerForActivityResult(new CropImageContract(), result -> {
            if (result.isSuccessful()) {
                Uri imageUri = result.getUriContent();
                if (imageUri != null) {
                    convertImageToText(imageUri);
                } else {
                    Toast.makeText(this, "Cropped image URI is null.", Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "Image cropping successful but URI is null.");
                }
            } else {
                Exception error = result.getError();
                Log.e(TAG, "Image cropping failed: " + (error != null ? error.getMessage() : "Unknown error"), error);
                Toast.makeText(this, "Image cropping failed: " + (error != null ? error.getMessage() : "Unknown error"), Toast.LENGTH_LONG).show();
            }
        });

        // Initialize ActivityResultLauncher for SAF text saving (from development2)
        createDocumentLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Uri uri = result.getData().getData();
                if (uri != null && pendingTextToSave != null) {
                    writeTextToUri(uri, pendingTextToSave);
                    pendingTextToSave = null;
                } else {
                    Toast.makeText(this, "Failed to get URI for saving or no text pending.", Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "SAF save failed: URI null or no text pending.");
                }
            } else {
                Toast.makeText(this, "File save cancelled or failed.", Toast.LENGTH_SHORT).show();
                pendingTextToSave = null;
                Log.d(TAG, "SAF save cancelled or failed.");
            }
        });

        // Initialize ActivityResultLauncher for PDF picking (unified from both branches)
        pickPdfLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Uri pdfUri = result.getData().getData();
                if (pdfUri != null) {
                    // Check Android version as PdfRenderer requires LOLLIPOP (API 21)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        processPdf(pdfUri);
                    } else {
                        Toast.makeText(this, "PDF processing requires Android 5.0 (Lollipop) or higher.", Toast.LENGTH_LONG).show();
                        Log.e(TAG, "PDF processing not supported on this Android version (< Lollipop).");
                    }
                } else {
                    Toast.makeText(this, "PDF URI is null from picker result.", Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "PDF URI is null from picker result.");
                }
            } else {
                Toast.makeText(this, "PDF selection cancelled or failed.", Toast.LENGTH_SHORT).show();
                Log.d(TAG, "PDF selection cancelled or failed.");
            }
        });


        // Find all UI elements using consistent names
        mImageView = findViewById(R.id.source_image);
        mProgressIndicator = findViewById(R.id.progress_indicator);
        mSwipeRefreshLayout = findViewById(R.id.swipe_to_refresh);
        mGalleryFab = findViewById(R.id.btn_scan); // Corresponds to btn_scan in your version, or btn_media_picker in server
        mLanguageName = findViewById(R.id.language_name1);

        mProgressBar = findViewById(R.id.progress_bar);
        mProgressMessage = findViewById(R.id.progress_message);
        mDownloadLayout = findViewById(R.id.download_layout);

        mSavedFilesFab = findViewById(R.id.btn_saved_files);
        mPdfFab = findViewById(R.id.btn_pdf);

        executorService = Executors.newFixedThreadPool(1);
        handler = new Handler(Looper.getMainLooper());

        initDirectories();
        initializeOCR();
        initViews();
    }

    private void initViews() {
        Log.d(TAG, "initViews: Initializing UI elements and listeners.");

        // Listener for the 'Gallery/Scan' button
        if (mGalleryFab != null) {
            mGalleryFab.setOnClickListener(v -> {
                Log.d(TAG, "Gallery FAB clicked. Opening image selection.");
                if (isNoLanguagesDataMissingFromSet()) {
                    if (mImageTextReader != null) {
                        selectImage(); // Now uses ActivityResultLauncher
                    } else {
                        initializeOCR();
                    }
                } else {
                    downloadLanguageData();
                }
            });
        } else {
            Log.e(TAG, "Gallery FAB (btn_scan) not found in layout!");
        }


        // Listener for the 'PDF' button
        if (mPdfFab != null) {
            mPdfFab.setOnClickListener(v -> {
                Log.d(TAG, "PDF FAB clicked. Opening PDF picker.");
                openPdfPicker(); // Now uses ActivityResultLauncher
            });
        } else {
            Log.e(TAG, "PDF FAB (btn_pdf) not found in layout!");
        }


        // Listener for the 'Saved Files' button (from development2)
        if (mSavedFilesFab != null) {
            mSavedFilesFab.setOnClickListener(v -> {
                Log.d(TAG, "Saved Files FAB clicked. Opening SavedResultsActivity.");
                openSavedFilesFolder();
            });
        } else {
            Log.e(TAG, "Saved Files FAB (btn_saved_files) not found in layout!");
        }


        // SwipeRefreshLayout listener
        mSwipeRefreshLayout.setOnRefreshListener(() -> {
            Log.d(TAG, "SwipeRefreshLayout triggered.");
            if (isNoLanguagesDataMissingFromSet()) {
                if (mImageTextReader != null) {
                    Drawable drawable = mImageView.getDrawable();
                    if (drawable instanceof BitmapDrawable) { // Added type check for safety
                        Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
                        if (bitmap != null) {
                            isRefresh = true;
                            executorService.submit(new ConvertImageToText(bitmap));
                            Log.d(TAG, "Swipe refresh: Converting current image to text.");
                        } else {
                            Log.d(TAG, "Swipe refresh: No bitmap found in ImageView drawable.");
                        }
                    } else {
                        Log.d(TAG, "Swipe refresh: ImageView drawable is not a BitmapDrawable.");
                    }
                } else {
                    initializeOCR();
                    Log.d(TAG, "Swipe refresh: ImageTextReader not initialized, initializing OCR.");
                }
            } else {
                downloadLanguageData();
                Log.d(TAG, "Swipe refresh: Language data missing, prompting download.");
            }
            mSwipeRefreshLayout.setRefreshing(false);
        });

        // Load last image from storage if persistent data is enabled
        if (Utils.isPersistData()) {
            Bitmap bitmap = loadBitmapFromStorage();
            if (bitmap != null) {
                mImageView.setImageBitmap(bitmap);
                Log.d(TAG, "Loaded persisted bitmap from storage.");
            } else {
                Log.d(TAG, "No persisted bitmap found in storage.");
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Ensure languages is not null before streaming
        Set<Language> languages = Utils.getTrainingDataLanguages(this);
        if (languages == null) {
            languages = new HashSet<>();
        }
        mLanguageName.setText(languages.stream().map(Language::getName).collect(Collectors.joining(", ")));
        Log.d(TAG, "onResume: Language name updated.");
    }

    /**
     * Opens the PDF picker for selecting a PDF file using ActivityResultLauncher.
     */
    private void openPdfPicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/pdf");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        pickPdfLauncher.launch(Intent.createChooser(intent, "Select PDF"));
        Log.d(TAG, "Intent to pick PDF launched with ActivityResultLauncher.");
    }

    private void initDirectories() {
        Log.d(TAG, "initDirectories: Initializing Tesseract data directories.");
        String[] dirNames = {"best", "fast", "standard"};
        for (String dirName : dirNames) {
            File dir = new File(getExternalFilesDir(dirName), "tessdata");
            if (dir.mkdirs() || dir.isDirectory()) {
                Log.d(TAG, "Directory " + dir.getAbsolutePath() + " ensured/created.");
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
            } else {
                Log.e(TAG, "Failed to create directory: " + dir.getAbsolutePath());
            }
        }
        // Consolidated logic: set to standard initially, then updated in initializeOCR based on settings
        currentDirectory = new File(dirStandard, "tessdata");
        Log.d(TAG, "Current Tesseract data directory set to: " + currentDirectory.getAbsolutePath());
    }

    /**
     * Initialize the OCR (Tesseract) API.
     * If training data is missing, it will prompt for download.
     */
    private void initializeOCR() {
        Log.d(TAG, "initializeOCR: Initializing OCR engine.");
        Set<Language> languages = Utils.getTrainingDataLanguages(this);
        if (languages == null) {
            languages = new HashSet<>();
        }
        File cf;
        mTrainingDataType = Utils.getTrainingDataType();
        mPageSegMode = Utils.getPageSegMode();
        parameters = Utils.getAllParameters();
        Log.d(TAG, "OCR settings: Type=" + mTrainingDataType + ", PageSegMode=" + mPageSegMode + ", Languages=" + languages.stream().map(Language::getCode).collect(Collectors.joining(", ")));

        switch (mTrainingDataType) {
            case "best":
                currentDirectory = new File(dirBest, "tessdata");
                cf = dirBest;
                break;
            case "standard":
                cf = dirStandard;
                currentDirectory = new File(dirStandard, "tessdata");
                break;
            default: // Default case for "fast"
                cf = dirFast;
                currentDirectory = new File(dirFast, "tessdata");
        }
        Log.d(TAG, "Selected Tesseract data path for OCR: " + cf.getAbsolutePath());

        if (isNoLanguagesDataMissingFromSet()) {
            startImageTextReaderThread(cf, languages);
            Log.d(TAG, "All language data available. Starting ImageTextReader thread.");
        } else {
            downloadLanguageData();
            Log.d(TAG, "Language data missing. Initiating download process.");
        }
    }

    private void startImageTextReaderThread(File cf, Set<Language> languages) {
        new Thread(() -> {
            Log.d(TAG, "startImageTextReaderThread: Initializing ImageTextReader in background.");
            try {
                if (mImageTextReader != null) {
                    mImageTextReader.tearDownEverything();
                    Log.d(TAG, "Existing ImageTextReader torn down.");
                }
                mImageTextReader = ImageTextReader.getInstance(
                        cf.getAbsolutePath(), languages,
                        mPageSegMode, parameters,
                        Utils.isExtraParameterSet(), MainActivity.this);
                if (mImageTextReader != null && !mImageTextReader.isSuccess()) {
                    // Post to UI thread for Toast compatibility
                    handler.post(() -> handleReaderException(languages));
                    Log.e(TAG, "ImageTextReader initialization failed (not success).");
                } else if (mImageTextReader == null) {
                    Log.e(TAG, "ImageTextReader instance is null after getInstance.");
                } else {
                    Log.d(TAG, "ImageTextReader initialized successfully.");
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception during ImageTextReader initialization: " + e.getLocalizedMessage(), e);
                // Post to UI thread for Toast compatibility
                handler.post(() -> handleReaderException(languages));
            }
        }).start();
    }

    private void handleReaderException(Set<Language> languages) {
        // Generate a single language code string for the set for file name, if needed for deletion
        String langCode = languages.stream().map(Language::getCode).collect(Collectors.joining("+"));
        File destFile = new File(currentDirectory, String.format(Constants.LANGUAGE_CODE, langCode));

        if (destFile.exists()) {
            if (destFile.delete()) {
                Log.d(TAG, "Deleted problematic language data file: " + destFile.getAbsolutePath());
            } else {
                Log.e(TAG, "Failed to delete problematic language data file: " + destFile.getAbsolutePath());
            }
            Toast.makeText(this, "Error with OCR data for " + languages.stream().map(Language::getName).collect(Collectors.joining(", ")) + ". Please try downloading again.", Toast.LENGTH_LONG).show();
        } else {
            Log.d(TAG, "Problematic language data file not found: " + destFile.getAbsolutePath());
            Toast.makeText(this, "OCR initialization failed. Language data might be corrupted or missing.", Toast.LENGTH_LONG).show();
        }
        mImageTextReader = null;
        Log.d(TAG, "ImageTextReader set to null due to initialization error.");
    }

    private void downloadLanguageData() {
        Log.d(TAG, "downloadLanguageData: Checking for missing language data and prompting download.");
        Set<Language> missingLanguage = new HashSet<>();
        Set<Language> languages = Utils.getTrainingDataLanguages(this);
        if (languages == null) { // Ensure languages is not null
            languages = new HashSet<>();
        }

        if (!Utils.isNetworkAvailable(getApplication())) {
            Toast.makeText(this, getString(R.string.you_are_not_connected_to_internet), Toast.LENGTH_SHORT).show();
            Log.w(TAG, "No network available for language data download.");
            return;
        }
        for (Language l : languages) {
            if (isLanguageDataMissing(mTrainingDataType, l)) {
                missingLanguage.add(l);
            }
        }
        // If no language data is actually missing after checking, proceed to initialize OCR
        if (missingLanguage.isEmpty()) {
            Log.d(TAG, "No language data is actually missing, initializing OCR.");
            initializeOCR();
            return;
        }

        String missingLangName = missingLanguage.stream().map(Language::getName).collect(Collectors.joining(", "));
        String msg = String.format(getString(R.string.download_description), missingLangName);
        dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.training_data_missing)
                .setCancelable(false)
                .setMessage(msg)
                .setPositiveButton(R.string.yes, (dialog, which) -> {
                    dialog.cancel();
                    executorService.submit(new DownloadTraining(mTrainingDataType, missingLanguage));
                    Log.d(TAG, "User chose to download missing language data: " + missingLangName);
                })
                .setNegativeButton(R.string.no, (dialog, which) -> {
                    dialog.cancel();
                    Log.d(TAG, "User chose NOT to download missing language data.");
                })
                .create();
        dialog.show();
    }

    private boolean isNoLanguagesDataMissingFromSet() {
        final String dataType = mTrainingDataType;
        Set<Language> languages = Utils.getTrainingDataLanguages(this);
        if (languages == null) {
            languages = new HashSet<>();
        }
        for (Language language : languages) {
            if (isLanguageDataMissing(dataType, language)) {
                Log.d(TAG, "Language data missing for: " + language.getName() + " (" + dataType + ")");
                return false;
            }
        }
        Log.d(TAG, "All required language data is present.");
        return true;
    }

    private boolean isLanguageDataMissing(@NonNull String dataType, @NonNull Language language) {
        // Redetermine currentDirectory based on dataType for each check to ensure correctness
        File checkDirectory;
        switch (dataType) {
            case "best":
                checkDirectory = new File(dirBest, "tessdata");
                break;
            case "standard":
                checkDirectory = new File(dirStandard, "tessdata");
                break;
            default:
                checkDirectory = new File(dirFast, "tessdata");
        }
        boolean missing = !new File(checkDirectory, String.format(Constants.LANGUAGE_CODE, language.getCode())).exists();
        if (missing) {
            Log.d(TAG, "Checking data for " + language.getCode() + " (" + dataType + "): MISSING at " + new File(checkDirectory, String.format(Constants.LANGUAGE_CODE, language.getCode())).getAbsolutePath());
        } else {
            Log.d(TAG, "Checking data for " + language.getCode() + " (" + dataType + "): FOUND at " + new File(checkDirectory, String.format(Constants.LANGUAGE_CODE, language.getCode())).getAbsolutePath());
        }
        return missing;
    }

    /**
     * Launches the image cropping activity using the modern ActivityResultLauncher.
     */
    private void selectImage() {
        Log.d(TAG, "selectImage: Launching image cropping activity.");
        CropImageOptions options = new CropImageOptions();
        options.guidelines = CropImageView.Guidelines.ON;
        cropImageLauncher.launch(new CropImageContractOptions(null, options));
    }

    private void convertImageToText(Uri imageUri) {
        Log.d(TAG, "convertImageToText: Starting image to text conversion for URI: " + imageUri.toString());
        Bitmap bitmap = null;
        try {
            bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), imageUri);
            Log.d(TAG, "Bitmap loaded from URI. Dimensions: " + (bitmap != null ? bitmap.getWidth() + "x" + bitmap.getHeight() : "null"));
        } catch (IOException e) {
            Log.e(TAG, "convertImageToText: Failed to get bitmap from URI: " + e.getLocalizedMessage(), e);
            Toast.makeText(this, "Failed to load image: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
        if (bitmap != null) {
            mImageView.setImageURI(imageUri);
            executorService.submit(new ConvertImageToText(bitmap));
        } else {
            Log.e(TAG, "Bitmap is null, cannot proceed with OCR.");
            Toast.makeText(this, "Bitmap is null, cannot proceed with OCR.", Toast.LENGTH_SHORT).show();
        }
    }

    // This onActivityResult is now simplified, as most intent results are handled by ActivityResultLaunchers.
    // It only handles REQUEST_CODE_SETTINGS and error logging for old CropImage result codes.
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.d(TAG, "onActivityResult: requestCode=" + requestCode + ", resultCode=" + resultCode);

        if (requestCode == REQUEST_CODE_SETTINGS) {
            Log.d(TAG, "ActivityResult from settings, re-initializing OCR.");
            initializeOCR();
        }
        // If an old CropImage call somehow still triggers this, handle its error
        // Note: The correct constant for errors from the canhub cropper is typically just `RESULT_CANCELED` for user cancellation,
        // or a specific error object in the `ActivityResult` for actual errors.
        // The constant `CropImage.CROP_IMAGE_ACTIVITY_RESULT_ERROR_CODE` is from the old library.
        // Keeping it for backward compatibility/defensive coding, but modern usage relies on the launcher's result.
        if (resultCode == com.theartofdev.edmodo.cropper.CropImage.CROP_IMAGE_ACTIVITY_RESULT_ERROR_CODE) { // Retained old constant for defensive coding
            com.theartofdev.edmodo.cropper.CropImage.ActivityResult result = com.theartofdev.edmodo.cropper.CropImage.getActivityResult(data);
            if (result != null && result.getError() != null) {
                Log.e(TAG, "Old CropImage error detected: " + result.getError().getMessage());
                Toast.makeText(this, "Image cropping failed (old library error): " + result.getError().getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy: Activity destroyed.");
        if (executorService != null) {
            executorService.shutdownNow();
            Log.d(TAG, "ExecutorService shut down.");
        }
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
            Log.d(TAG, "AlertDialog dismissed.");
        }
        if (mImageTextReader != null) {
            mImageTextReader.tearDownEverything();
            Log.d(TAG, "ImageTextReader torn down.");
        }
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);

        MenuItem showHistoryItem = menu.findItem(R.id.action_history);
        showHistoryItem.setVisible(Utils.isPersistData());
        Log.d(TAG, "onCreateOptionsMenu: History item visibility set to " + showHistoryItem.isVisible());
        return true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop: Activity stopped.");
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            // Using startActivityForResult directly here for consistency with its definition
            startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_CODE_SETTINGS);
            Log.d(TAG, "Options menu: Settings selected, launching SettingsActivity.");
        } else if (id == R.id.action_history) {
            showOCRResult(Utils.getLastUsedText());
            Log.d(TAG, "Options menu: History selected, showing last used text.");
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onProgressValues(final TessBaseAPI.ProgressValues progressValues) {
        runOnUiThread(() -> {
            int progress = (int) (progressValues.getPercent() * 1.46);
            mProgressIndicator.setProgress(progress);
            // Log this less frequently or only at key intervals if it's too chatty
            // Log.v(TAG, "OCR Progress: " + progressValues.getPercent() + "%, UI Progress: " + progress + "%");
        });
    }

    public void saveBitmapToStorage(Bitmap bitmap) {
        Log.d(TAG, "saveBitmapToStorage: Saving bitmap to internal storage.");
        FileOutputStream fileOutputStream;
        try {
            fileOutputStream = openFileOutput("last_file.jpeg", Context.MODE_PRIVATE);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 30, fileOutputStream);
            fileOutputStream.close();
            Log.d(TAG, "Bitmap saved to last_file.jpeg successfully.");
        } catch (IOException e) {
            Log.e(TAG, "saveBitmapToStorage: Failed to save bitmap: " + e.getLocalizedMessage(), e);
        }
    }

    public Bitmap loadBitmapFromStorage() {
        Log.d(TAG, "loadBitmapFromStorage: Loading bitmap from internal storage.");
        Bitmap bitmap = null;
        FileInputStream fileInputStream;
        try {
            fileInputStream = openFileInput("last_file.jpeg");
            bitmap = BitmapFactory.decodeStream(fileInputStream);
            fileInputStream.close();
            Log.d(TAG, "Bitmap loaded from last_file.jpeg. Dimensions: " + (bitmap != null ? bitmap.getWidth() + "x" + bitmap.getHeight() : "null"));
        } catch (IOException e) {
            Log.e(TAG, "loadBitmapFromStorage: Failed to load bitmap: " + e.getLocalizedMessage(), e);
        }
        return bitmap;
    }

    public void showOCRResult(String text) {
        if (this.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
            BottomSheetResultsFragment bottomSheetResultsFragment = BottomSheetResultsFragment.newInstance(text);
            bottomSheetResultsFragment.show(getSupportFragmentManager(), "bottomSheetResultsFragment");
            Log.d(TAG, "showOCRResult: Bottom sheet result fragment shown.");
            // Prompt the user to save to public location (SAF)
            showSaveTextDialog(text);
        } else {
            Log.d(TAG, "showOCRResult: Activity not in RESUMED state, not showing bottom sheet.");
        }
    }

    /**
     * Displays a dialog asking the user to save the extracted text to a public location (SAF).
     *
     * @param extractedText The text to be saved.
     */
    private void showSaveTextDialog(final String extractedText) {
        new AlertDialog.Builder(this)
                .setTitle("Save Text")
                .setMessage("Do you want to save the extracted text to a public location?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    pendingTextToSave = extractedText;

                    SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault());
                    String suggestedFileName = "OCR_Result_" + timeFormat.format(new Date()) + ".txt";

                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_TITLE, suggestedFileName);

                    // Attempt to suggest the Documents root, adapted for modern Android.
                    // This is a hint, and not all file managers will respect it exactly.
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        Uri initialUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADocuments");
                        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);
                    }
                    createDocumentLauncher.launch(intent);
                })
                .setNegativeButton("No", (dialog, which) -> {
                    dialog.dismiss();
                    pendingTextToSave = null;
                })
                .setCancelable(true)
                .show();
    }

    /**
     * Writes the given text to the specified URI using Storage Access Framework.
     *
     * @param uri        The URI of the document to write to.
     * @param textToSave The text content to write.
     */
    private void writeTextToUri(Uri uri, String textToSave) {
        executorService.submit(() -> {
            try (OutputStream outputStream = getContentResolver().openOutputStream(uri)) {
                if (outputStream != null) {
                    outputStream.write(textToSave.getBytes());
                    outputStream.write("\n\n---\n\n".getBytes()); // Add separator for multiple saves to same file
                    handler.post(() -> {
                        Toast.makeText(MainActivity.this, "Text saved successfully to: " + uri.getPath() + " (visible in file managers)", Toast.LENGTH_LONG).show();
                        Log.d(TAG, "File saved to URI: " + uri.toString());
                    });
                } else {
                    handler.post(() -> Toast.makeText(MainActivity.this, "Failed to open output stream for saving.", Toast.LENGTH_SHORT).show());
                }
            } catch (IOException e) {
                Log.e(TAG, "Error writing text to URI: " + e.getMessage());
                handler.post(() -> Toast.makeText(MainActivity.this, "Error saving text: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    /**
     * Opens the SavedResultsActivity to view previously saved OCR results.
     */
    private void openSavedFilesFolder() {
        startActivity(new Intent(MainActivity.this, SavedResultsActivity.class));
    }

    /**
     * Processes a selected PDF URI, rendering pages to bitmaps and performing OCR.
     * Requires Android 5.0 (Lollipop) or higher.
     *
     * @param uri The URI of the PDF file.
     */
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void processPdf(Uri uri) {
        Log.d(TAG, "processPdf: Starting PDF processing for URI: " + uri.toString());
        executorService.execute(() -> {
            try (ParcelFileDescriptor fileDescriptor = getContentResolver().openFileDescriptor(uri, "r");
                 PdfRenderer renderer = new PdfRenderer(fileDescriptor)) {

                int pageCount = renderer.getPageCount();
                Log.d(TAG, "PDF opened successfully. Total pages: " + pageCount);
                StringBuilder fullText = new StringBuilder();

                handler.post(() -> {
                    mProgressBar.setMax(pageCount);
                    mProgressBar.setProgress(0);
                    mProgressBar.setVisibility(View.VISIBLE);
                    mProgressMessage.setText("Processing PDF...");
                    Log.d(TAG, "UI: PDF processing progress bar visible, message set.");
                });

                for (int i = 0; i < pageCount; i++) {
                    PdfRenderer.Page page = null;
                    Bitmap originalBitmap = null;
                    Bitmap processedBitmap = null;
                    try {
                        page = renderer.openPage(i);
                        Log.d(TAG, "Opened PDF page: " + (i + 1));

                        // Target DPI for OCR
                        float targetDpi = 300f; // Aim for 300 DPI for better OCR

                        // Calculate width and height based on target DPI.
                        // PDF page dimensions are typically in points (72 points per inch).
                        int width = (int) (page.getWidth() / 72f * targetDpi);
                        int height = (int) (page.getHeight() / 72f * targetDpi);

                        originalBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                        // The rect should match the dimensions of the new, higher resolution bitmap
                        Rect rect = new Rect(0, 0, width, height);
                        page.render(originalBitmap, rect, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        Log.i(TAG, "Page " + (i + 1) + " successfully rendered to originalBitmap. Dimensions: " + originalBitmap.getWidth() + "x" + originalBitmap.getHeight());

                        // Preprocess the bitmap (grayscale and binarization)
                        processedBitmap = preprocessBitmap(originalBitmap); // Using the local preprocessBitmap for consistency with server's PDF logic
                        Log.d(TAG, "Page " + (i + 1) + " preprocessed.");

                        // Perform OCR on the processed bitmap
                        if (mImageTextReader != null) {
                            String result = mImageTextReader.getTextFromBitmap(processedBitmap);
                            fullText.append("--- Page ").append(i + 1).append(" ---\n").append(result).append("\n\n");
                            Log.d(TAG, "OCR result for Page " + (i + 1) + ": " + (result != null ? result.substring(0, Math.min(result.length(), 100)) + "..." : "No text found"));
                        } else {
                            Log.e(TAG, "ImageTextReader is not initialized for PDF OCR on Page " + (i + 1) + ". Skipping OCR for this page.");
                            fullText.append("--- Page ").append(i + 1).append(" ---\nOCR failed: ImageTextReader not ready.\n\n");
                        }
                    } finally { // Ensures resources are released even if an error occurs
                        if (processedBitmap != null && !processedBitmap.isRecycled()) {
                            processedBitmap.recycle();
                            Log.d(TAG, "Processed bitmap for page " + (i + 1) + " recycled.");
                        }
                        if (originalBitmap != null && !originalBitmap.isRecycled()) {
                            originalBitmap.recycle();
                            Log.d(TAG, "Original bitmap for page " + (i + 1) + " recycled.");
                        }
                        if (page != null) {
                            page.close();
                            Log.d(TAG, "PDF page " + (i + 1) + " closed.");
                        }
                    }

                    final int progress = i + 1;
                    handler.post(() -> {
                        mProgressBar.setProgress(progress);
                        Log.d(TAG, "UI: Progress updated to " + progress + "/" + pageCount);
                    });
                }

                handler.post(() -> {
                    mProgressMessage.setText("OCR Complete!");
                    mProgressBar.setVisibility(View.GONE);
                    Log.d(TAG, "UI: OCR processing finished, progress bar hidden.");
                    // Show the combined OCR result for all pages
                    showResultDialog(fullText.toString());
                    Log.d(TAG, "OCR Result dialog shown.");
                });

            } catch (IOException e) {
                Log.e(TAG, "Error processing PDF: " + e.getLocalizedMessage(), e);
                handler.post(() -> Toast.makeText(this, "Failed to read PDF. Error: " + e.getLocalizedMessage(), Toast.LENGTH_LONG).show());
            } catch (Exception e) { // Catch any other unexpected exceptions during PDF processing or OCR
                Log.e(TAG, "Unexpected error during PDF processing or OCR: " + e.getLocalizedMessage(), e);
                handler.post(() -> Toast.makeText(this, "An unexpected error occurred during PDF processing.", Toast.LENGTH_LONG).show());
            }
        });
    }

    /**
     * Converts a given bitmap to grayscale and then binarizes it.
     * This method is retained from the server's PDF processing logic.
     *
     * @param bmpOriginal The original color bitmap.
     * @return The preprocessed (grayscale and binarized) version of the bitmap.
     */
    private Bitmap preprocessBitmap(Bitmap bmpOriginal) {
        Log.d(TAG, "preprocessBitmap: Starting image preprocessing. Original dimensions: " + bmpOriginal.getWidth() + "x" + bmpOriginal.getHeight());
        int width = bmpOriginal.getWidth();
        int height = bmpOriginal.getHeight();

        // Step 1: Grayscale
        Bitmap bmpGrayscale = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmpGrayscale);
        Paint paint = new Paint();
        ColorMatrix colorMatrix = new ColorMatrix();
        colorMatrix.setSaturation(0); // Remove color
        ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
        paint.setColorFilter(filter);
        canvas.drawBitmap(bmpOriginal, 0, 0, paint);
        Log.d(TAG, "Bitmap converted to grayscale.");

        // Step 2: Binarization (Adaptive Thresholding concept)
        Bitmap bmpBinarized = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[width * height];
        bmpGrayscale.getPixels(pixels, 0, width, 0, 0, width, height);

        int threshold = 128; // A good starting point for binarization
        Log.d(TAG, "Applying binarization with threshold: " + threshold);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = pixels[y * width + x];
                int gray = Color.red(pixel); // Get grayscale value

                if (gray < threshold) {
                    pixels[y * width + x] = Color.BLACK;
                } else {
                    pixels[y * width + x] = Color.WHITE;
                }
            }
        }
        bmpBinarized.setPixels(pixels, 0, width, 0, 0, width, height);
        Log.d(TAG, "Bitmap binarized to black and white.");

        if (bmpGrayscale != null && !bmpGrayscale.isRecycled()) {
            bmpGrayscale.recycle();
            Log.d(TAG, "Grayscale bitmap recycled.");
        }

        Log.d(TAG, "preprocessBitmap: Finished preprocessing. Returning binarized bitmap.");
        return bmpBinarized;
    }

    /**
     * Displays a basic AlertDialog with the OCR result.
     *
     * @param text The OCR extracted text to display.
     */
    private void showResultDialog(String text) {
        Log.d(TAG, "showResultDialog: Displaying OCR result dialog. Text length: " + text.length());
        new AlertDialog.Builder(this)
                .setTitle("OCR Result")
                .setMessage(text)
                .setPositiveButton("OK", null)
                .setCancelable(true)
                .show();
    }

    // Helper method to save bitmaps for debugging (from server version) - not currently called in flow, but useful
    private void saveBitmapToFile(Context context, Bitmap bitmap, String filename) {
        Log.d(TAG, "saveBitmapToFile called for: " + filename);
        if (bitmap == null || bitmap.isRecycled()) {
            Log.e(TAG, "Cannot save null or recycled bitmap: " + filename);
            return;
        }
        try {
            File debugDir = new File(context.getExternalFilesDir(null), "ocr_debug_images");
            if (!debugDir.exists()) {
                if (!debugDir.mkdirs()) {
                    Log.e(TAG, "Failed to create debug directory: " + debugDir.getAbsolutePath());
                    return;
                }
            }
            File debugFile = new File(debugDir, filename);
            try (FileOutputStream out = new FileOutputStream(debugFile)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                Log.d(TAG, "Saved debug image: " + debugFile.getAbsolutePath());
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to save debug image " + filename + ": " + e.getMessage(), e);
        }
    }


    private class ConvertImageToText implements Runnable {
        private Bitmap bitmap;

        public ConvertImageToText(Bitmap bitmap) {
            this.bitmap = bitmap;
            Log.d(TAG, "ConvertImageToText task created with bitmap dimensions: " + bitmap.getWidth() + "x" + bitmap.getHeight());
        }

        @Override
        public void run() {
            Log.d(TAG, "ConvertImageToText: Running OCR task in background.");
            // Pre-execute on UI thread
            handler.post(() -> {
                mProgressIndicator.setProgress(0);
                mProgressIndicator.setVisibility(View.VISIBLE);
                animateImageViewAlpha(0.2f);
                Log.d(TAG, "UI: Progress indicator visible, ImageView alpha set to 0.2.");
            });

            // Background execution
            if (!isRefresh && Utils.isPreProcessImage()) {
                Log.d(TAG, "Applying pre-processing to image.");
                bitmap = Utils.preProcessBitmap(bitmap); // Using Utils for general image preprocessing
            } else if (isRefresh) {
                Log.d(TAG, "Skipping pre-processing due to refresh flag.");
            } else {
                Log.d(TAG, "Skipping pre-processing as it's disabled in settings.");
            }
            isRefresh = false;
            saveBitmapToStorage(bitmap);

            String text = null;
            if (mImageTextReader != null) {
                text = mImageTextReader.getTextFromBitmap(bitmap);
                Log.d(TAG, "OCR completed. Raw text length: " + (text != null ? text.length() : 0));
            } else {
                Log.e(TAG, "mImageTextReader is null, cannot perform OCR.");
                text = "OCR Engine not initialized. Please restart app or check settings."; // User feedback
            }

            // Post-execution on UI thread
            final String finalCleanText = (text != null) ? Html.fromHtml(text).toString().trim() : "";
            final int accuracy = (mImageTextReader != null) ? mImageTextReader.getAccuracy() : -1;

            handler.post(() -> {
                mProgressIndicator.setVisibility(View.GONE);
                animateImageViewAlpha(1f);
                Log.d(TAG, "UI: Progress indicator hidden, ImageView alpha restored.");
                showOCRResult(finalCleanText);
                Toast.makeText(MainActivity.this, "With Confidence: " + accuracy + "%", Toast.LENGTH_SHORT).show();
                Log.d(TAG, "OCR Result shown. Confidence: " + accuracy + "%");
                Utils.putLastUsedText(finalCleanText);
                updateImageView();
            });
        }

        private void animateImageViewAlpha(float alpha) {
            mImageView.animate().alpha(alpha).setDuration(450).start();
            Log.d(TAG, "ImageView alpha animated to: " + alpha);
        }

        private void updateImageView() {
            Bitmap bitmap = loadBitmapFromStorage();
            if (bitmap != null) {
                mImageView.setImageBitmap(bitmap);
                Log.d(TAG, "ImageView updated with loaded bitmap.");
            } else {
                Log.w(TAG, "Could not update ImageView, loaded bitmap is null.");
            }
        }
    }


    private class DownloadTraining implements Runnable {
        private final String dataType;
        private final Set<Language> languages;
        private String size;

        public DownloadTraining(String dataType, Set<Language> langs) {
            this.dataType = dataType;
            this.languages = (langs != null) ? langs : new HashSet<>();
            Log.d(TAG, "DownloadTraining task created for " + languages.size() + " languages (" + dataType + ").");
        }

        @Override
        public void run() {
            handler.post(() -> {
                mProgressMessage.setText(getString(R.string.downloading_language));
                mDownloadLayout.setVisibility(View.VISIBLE);
                mProgressBar.setVisibility(View.GONE); // Initially hide indeterminate bar, show determinate later
                Log.d(TAG, "UI: Download layout visible, message set to downloading.");
            });

            final boolean[] success = {true};
            for (Language lang : languages) {
                Log.d(TAG, "Attempting to download language: " + lang.getCode());
                success[0] = success[0] && downloadTrainingData(dataType, lang.getCode());
                if (!success[0]) {
                    Log.e(TAG, "Failed to download language: " + lang.getCode() + ". Aborting further downloads for this batch.");
                    break; // Stop if one download fails
                }
            }
            handler.post(() -> {
                mDownloadLayout.setVisibility(View.GONE);
                if (success[0]) {
                    initializeOCR();
                    Toast.makeText(MainActivity.this, "Download complete!", Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "DownloadTraining: All downloads successful, re-initializing OCR.");
                } else {
                    Toast.makeText(MainActivity.this, "Download failed", Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "DownloadTraining: One or more downloads failed.");
                }
            });
        }

        @SuppressLint("DefaultLocale")
        private boolean downloadTrainingData(String dataType, String lang) {
            String downloadURL = getDownloadUrl(dataType, lang);
            if (downloadURL == null) {
                Log.e(TAG, "Download URL is null for " + lang + " (" + dataType + ")");
                return false;
            }
            Log.d(TAG, "Starting download of " + lang + " from: " + downloadURL);
            try {
                URL url = new URL(downloadURL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(false);
                String originalUrl = downloadURL; // Keep original for logging
                downloadURL = followRedirects(conn, downloadURL); // Handle redirects
                if (!originalUrl.equals(downloadURL)) { // Log redirect
                    Log.d(TAG, "Redirected download URL for " + lang + ": " + downloadURL);
                }
                conn = (HttpURLConnection) new URL(downloadURL).openConnection(); // Re-open connection after redirects
                conn.connect();
                int totalContentSize = conn.getContentLength();
                if (totalContentSize <= 0) {
                    Log.e(TAG, "Invalid content size for " + lang + ": " + totalContentSize);
                    return false;
                }
                size = Utils.getSize(totalContentSize);
                Log.d(TAG, "Total content size for " + lang + ": " + size);

                // Switch from indeterminate to determinate progress bar
                handler.post(() -> {
                    mProgressBar.setVisibility(View.VISIBLE);
                    mProgressMessage.setText(String.format("0%s%s", getString(R.string.percentage_downloaded), size));
                    mProgressBar.setProgress(0);               // Reset progress bar to 0
                    Log.d(TAG, "UI: Progress bar for download visible, initial message set.");
                });

                File destFile = new File(currentDirectory, String.format(Constants.LANGUAGE_CODE, lang));
                try (InputStream input = new BufferedInputStream(conn.getInputStream()); OutputStream output = new FileOutputStream(destFile)) {

                    byte[] data = new byte[6 * 1024]; // 6KB buffer
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
                    output.flush();
                    Log.d(TAG, "Download complete for " + lang + ". Saved to: " + destFile.getAbsolutePath());
                }

                return true;
            } catch (IOException e) {
                Log.e(TAG, "Download failed for " + lang + ": " + e.getLocalizedMessage(), e);
                return false;
            }
        }

        private String getDownloadUrl(String dataType, String lang) {
            String url = null;
            switch (dataType) {
                case "best":
                    url = lang.equals("akk") ? Constants.TESSERACT_DATA_DOWNLOAD_URL_AKK_BEST : lang.equals("eqo") ? Constants.TESSERACT_DATA_DOWNLOAD_URL_EQU : String.format(Constants.TESSERACT_DATA_DOWNLOAD_URL_BEST, lang);
                    break;
                case "standard":
                    url = lang.equals("akk") ? Constants.TESSERACT_DATA_DOWNLOAD_URL_AKK_STANDARD : lang.equals("eqo") ? Constants.TESSERACT_DATA_DOWNLOAD_URL_EQU : String.format(Constants.TESSERACT_DATA_DOWNLOAD_URL_STANDARD, lang);
                    break;
                default: // Assuming "fast" is the default
                    url = lang.equals("akk") ? Constants.TESSERACT_DATA_DOWNLOAD_URL_AKK_FAST : Constants.TESSERACT_DATA_DOWNLOAD_URL_EQU;
                    break; // Fixed from previous version: ensure it returns for default case or has a generic format.
            }
            Log.v(TAG, "Resolved download URL for " + lang + " (" + dataType + "): " + url);
            return url;
        }

        private String followRedirects(HttpURLConnection conn, String downloadURL) throws IOException {
            int redirectCount = 0;
            while (true) {
                int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_MOVED_PERM || responseCode == HttpURLConnection.HTTP_MOVED_TEMP || responseCode == HttpURLConnection.HTTP_SEE_OTHER) {
                    String location = conn.getHeaderField("Location");
                    if (location == null) {
                        Log.e(TAG, "Redirect location is null for URL: " + downloadURL);
                        return downloadURL; // Cannot follow null redirect
                    }
                    URL base = new URL(downloadURL);
                    downloadURL = new URL(base, location).toExternalForm(); // Handle relative URLs
                    conn.disconnect(); // Disconnect old connection
                    conn = (HttpURLConnection) new URL(downloadURL).openConnection(); // Re-open connection
                    conn.setInstanceFollowRedirects(false); // Manually follow redirects
                    Log.d(TAG, "Followed redirect (" + responseCode + ") to: " + downloadURL);
                    redirectCount++;
                    if (redirectCount > 5) { // Prevent infinite redirect loops
                        Log.e(TAG, "Too many redirects for URL: " + downloadURL);
                        throw new IOException("Too many redirects");
                    }
                } else {
                    break; // No more redirects
                }
            }
            return downloadURL;
        }
    }
}
