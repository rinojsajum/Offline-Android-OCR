package io.github.subhamtyagi.ocr;

import android.view.accessibility.AccessibilityEvent;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.googlecode.tesseract.android.TessBaseAPI;

import java.util.List;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.Html;
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
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.lifecycle.Lifecycle;
import androidx.preference.PreferenceManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.canhub.cropper.CropImageContract;
import com.canhub.cropper.CropImageContractOptions;
import com.canhub.cropper.CropImageOptions;
import com.canhub.cropper.CropImageView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

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
import java.util.ArrayList;
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

public class MainActivity extends AppCompatActivity implements BottomSheetResultsFragment.OnPageSelectedListener, TessBaseAPI.ProgressNotifier {


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

    private LinearLayout mGalleryLayout;
    private LinearLayout mCameraLayout;
    private LinearLayout mSavedFilesLayout;
    private LinearLayout mPdfLayout;

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
    private volatile boolean isProcessingPdf = false;
    private Handler accessibilityHandler;
    private Runnable accessibilityRunnable;
    private volatile String currentAccessibilityMessage;

    private ActivityResultLauncher<CropImageContractOptions> cropImageLauncher;

    private ActivityResultLauncher<String> galleryPickerLauncher;
    private ActivityResultLauncher<Uri> cameraCaptureLauncher;
    private Uri cameraOutputUri;

    private ActivityResultLauncher<Intent> createDocumentLauncher;
    private ActivityResultLauncher<Intent> pickPdfLauncher;
    private ActivityResultLauncher<Intent> settingsLauncher;


    // REPLACE YOUR ENTIRE onCreate METHOD WITH THIS

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

        settingsLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    Log.d(TAG, "Returned from settings. Re-initializing OCR.");
                    // This is where we return from settings.
                    // initializeOCR() will be called, which should check for missing languages.
                    initializeOCR();
                }
        );

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

        galleryPickerLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                startCropOrOcr(uri);
            } else {
                Toast.makeText(this, "Image selection cancelled.", Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Gallery image selection cancelled.");
            }
        });

        cameraCaptureLauncher = registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
            if (success) {
                if (cameraOutputUri != null) {
                    startCropOrOcr(cameraOutputUri);
                } else {
                    Toast.makeText(this, "Camera output URI is null.", Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "Camera capture successful but output URI is null.");
                }
            } else {
                Toast.makeText(this, "Camera capture cancelled or failed.", Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Camera capture cancelled or failed.");
                if (cameraOutputUri != null) {
                    try {
                        File file = new File(cameraOutputUri.getPath());
                        if (file.exists()) {
                            file.delete();
                            Log.d(TAG, "Deleted temporary camera file: " + file.getAbsolutePath());
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error deleting temp camera file: " + e.getMessage());
                    }
                    cameraOutputUri = null;
                }
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
                    Log.e(TAG, "SAF save failed: URI null or no text pending.");
                }
            } else {
                Toast.makeText(this, "File save cancelled or failed.", Toast.LENGTH_SHORT).show();
                pendingTextToSave = null;
                Log.d(TAG, "SAF save cancelled or failed.");
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

        mImageView = findViewById(R.id.source_image);
        mProgressIndicator = findViewById(R.id.progress_indicator);
        mSwipeRefreshLayout = findViewById(R.id.swipe_to_refresh);
        mLanguageName = findViewById(R.id.language_name1);
        mProgressBar = findViewById(R.id.progress_bar);
        mProgressMessage = findViewById(R.id.progress_message);
        mDownloadLayout = findViewById(R.id.download_layout);

        // *** THIS IS THE CHANGED PART ***
        // Find the LinearLayouts instead of the FloatingActionButtons
        mCameraLayout = findViewById(R.id.camera_layout);
        mGalleryLayout = findViewById(R.id.gallery_layout);
        mPdfLayout = findViewById(R.id.pdf_layout);
        mSavedFilesLayout = findViewById(R.id.saved_files_layout);
        // *** END OF CHANGED PART ***

        executorService = Executors.newFixedThreadPool(1);
        handler = new Handler(Looper.getMainLooper());

        initAccessibilityAnnouncer();
        initDirectories();
        initializeOCR();
        initViews();
    }

    // *** ADDED: THIS IS THE CRITICAL LOGIC THAT CHECKS THE SETTING ***
    private void startCropOrOcr(Uri imageUri) {
        if (imageUri == null) {
            Log.e(TAG, "Image URI is null, cannot proceed.");
            Toast.makeText(this, "Failed to get image URI.", Toast.LENGTH_SHORT).show();
            return;
        }

        // 1. Get SharedPreferences to read the setting value
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        // 2. Read the value of our "Enable Cropping" setting. Default to 'false' if not found.
        boolean isCroppingEnabled = prefs.getBoolean(getString(R.string.key_enable_cropping), false);

        if (isCroppingEnabled) {
            // 3a. If cropping is ON, launch the crop activity as before
            Log.d(TAG, "Cropping is enabled. Launching crop activity.");
            CropImageOptions options = new CropImageOptions();
            options.guidelines = CropImageView.Guidelines.ON;
            options.allowFlipping = false;
            cropImageLauncher.launch(new CropImageContractOptions(imageUri, options));
        } else {
            // 3b. If cropping is OFF, skip cropping and go directly to OCR
            Log.d(TAG, "Cropping is disabled. Skipping to OCR.");
            convertImageToText(imageUri);
        }
    }

// REPLACE YOUR ENTIRE initViews METHOD WITH THIS

    private void initViews() {
        Log.d(TAG, "initViews: Initializing UI elements and listeners.");

        // Using mGalleryLayout which you defined earlier
        if (mGalleryLayout != null) {
            mGalleryLayout.setOnClickListener(v -> {
                Log.d(TAG, "Gallery Layout clicked. Starting gallery selection.");
                if (isNoLanguagesDataMissingFromSet(Utils.getTrainingDataLanguages(this))) {
                    if (mImageTextReader != null) {
                        startGallerySelectionAndCrop();
                    } else {
                        initializeOCR();
                    }
                } else {
                    downloadLanguageData(Utils.getTrainingDataLanguages(this));
                }
            });
        } else {
            // Updated log message for clarity
            Log.e(TAG, "Gallery Layout (gallery_layout) not found in layout!");
        }

        // Using mCameraLayout
        if (mCameraLayout != null) {
            mCameraLayout.setOnClickListener(v -> {
                Log.d(TAG, "Camera Layout clicked. Starting camera capture.");
                if (isNoLanguagesDataMissingFromSet(Utils.getTrainingDataLanguages(this))) {
                    if (mImageTextReader != null) {
                        startCameraCaptureAndCrop();
                    } else {
                        initializeOCR();
                    }
                } else {
                    downloadLanguageData(Utils.getTrainingDataLanguages(this));
                }
            });
        } else {
            // Updated log message for clarity
            Log.e(TAG, "Camera Layout (camera_layout) not found in layout!");
        }

        // Using mPdfLayout
        if (mPdfLayout != null) {
            mPdfLayout.setOnClickListener(v -> {
                Log.d(TAG, "PDF Layout clicked. Opening PDF picker.");
                openPdfPicker();
            });
        } else {
            // Updated log message for clarity
            Log.e(TAG, "PDF Layout (pdf_layout) not found in layout!");
        }

        // Using mSavedFilesLayout
        if (mSavedFilesLayout != null) {
            mSavedFilesLayout.setOnClickListener(v -> {
                Log.d(TAG, "Saved Files Layout clicked. Opening SavedResultsActivity.");
                openSavedFilesFolder();
            });
        } else {
            // Updated log message for clarity
            Log.e(TAG, "Saved Files Layout (saved_files_layout) not found in layout!");
        }

        mSwipeRefreshLayout.setOnRefreshListener(() -> {
            Log.d(TAG, "SwipeRefreshLayout triggered.");
            if (isNoLanguagesDataMissingFromSet(Utils.getTrainingDataLanguages(this))) {
                if (mImageTextReader != null) {
                    Drawable drawable = mImageView.getDrawable();
                    if (drawable instanceof BitmapDrawable) {
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
                downloadLanguageData(Utils.getTrainingDataLanguages(this));
                Log.d(TAG, "Swipe refresh: Language data missing, prompting download.");
            }
            mSwipeRefreshLayout.setRefreshing(false);
        });

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
        Set<Language> languages = Utils.getTrainingDataLanguages(this);
        if (languages == null) {
            languages = new HashSet<>();
        }
        mLanguageName.setText(languages.stream().map(Language::getName).collect(Collectors.joining(", ")));
        // --- START DEBUG LOGGING ---
        Log.d(TAG, "onResume: Current languages from Utils: " + languages.stream().map(Language::getCode).collect(Collectors.joining(", ")));
        // --- END DEBUG LOGGING ---
    }

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
    }

    private void initializeOCR() {
        Log.d(TAG, "initializeOCR: Initializing OCR engine.");
        // Always get the latest languages from Utils here
        Set<Language> languages = Utils.getTrainingDataLanguages(this);
        if (languages == null) {
            languages = new HashSet<>();
        }
        // --- START DEBUG LOGGING ---
        Log.d(TAG, "initializeOCR: Languages to initialize with: " + languages.stream().map(Language::getCode).collect(Collectors.joining(", ")));
        // --- END DEBUG LOGGING ---

        mTrainingDataType = Utils.getTrainingDataType();
        mPageSegMode = Utils.getPageSegMode();
        parameters = Utils.getAllParameters();
        Log.d(TAG, "OCR settings: Type=" + mTrainingDataType + ", PageSegMode=" + mPageSegMode + ", Languages=" + languages.stream().map(Language::getCode).collect(Collectors.joining(", ")));

        switch (mTrainingDataType) {
            case "best":
                tessdataDirectory = dirBest;
                break;
            case "standard":
                tessdataDirectory = dirStandard;
                break;
            default:
                tessdataDirectory = dirFast;
        }
        currentDirectory = new File(tessdataDirectory, "tessdata");
        Log.d(TAG, "Selected Tesseract data path for OCR: " + tessdataDirectory.getAbsolutePath());

        // --- START DEBUG LOGGING ---
        boolean languagesMissing = !isNoLanguagesDataMissingFromSet(languages);
        Log.d(TAG, "initializeOCR: Are languages missing? " + languagesMissing);
        // --- END DEBUG LOGGING ---

        if (!isNoLanguagesDataMissingFromSet(languages)) { // This condition checks if languages ARE missing
            downloadLanguageData(languages);
            Log.d(TAG, "Language data missing. Initiating download process.");
        } else { // This condition means NO languages are missing
            startImageTextReaderThread(languages);
            Log.d(TAG, "All language data available. Starting ImageTextReader thread.");
        }
    }


    private void startImageTextReaderThread(Set<Language> languages) {
        new Thread(() -> {
            Log.d(TAG, "startImageTextReaderThread: Initializing ImageTextReader in background.");
            try {
                if (mImageTextReader != null) {
                    mImageTextReader.tearDownEverything();
                    Log.d(TAG, "Existing ImageTextReader torn down.");
                }
                mImageTextReader = ImageTextReader.getInstance(
                        tessdataDirectory.getAbsolutePath(), languages,
                        mPageSegMode, parameters,
                        Utils.isExtraParameterSet(), MainActivity.this);
                if (mImageTextReader != null && !mImageTextReader.isSuccess()) {
                    handler.post(() -> handleReaderException(languages));
                    Log.e(TAG, "ImageTextReader initialization failed (not success).");
                } else if (mImageTextReader == null) {
                    Log.e(TAG, "ImageTextReader instance is null after getInstance.");
                } else {
                    Log.d(TAG, "ImageTextReader initialized successfully.");
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception during ImageTextReader initialization: " + e.getLocalizedMessage(), e);
                handler.post(() -> handleReaderException(languages));
            }
        }).start();
    }

    private void handleReaderException(Set<Language> languages) {
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

    private void downloadLanguageData(Set<Language> languagesToProcess) {
        Log.d(TAG, "downloadLanguageData: Checking for missing language data and prompting download.");
        Set<Language> missingLanguage = new HashSet<>();

        if (!Utils.isNetworkAvailable(getApplication())) {
            Toast.makeText(this, getString(R.string.you_are_not_connected_to_internet), Toast.LENGTH_SHORT).show();
            Log.w(TAG, "No network available for language data download.");
            return;
        }
        for (Language l : languagesToProcess) {
            if (isLanguageDataMissing(mTrainingDataType, l)) {
                missingLanguage.add(l);
            }
        }
        // --- START DEBUG LOGGING ---
        Log.d(TAG, "downloadLanguageData: Identified missing languages: " + missingLanguage.stream().map(Language::getCode).collect(Collectors.joining(", ")));
        // --- END DEBUG LOGGING ---

        if (missingLanguage.isEmpty()) {
            Log.d(TAG, "No language data is actually missing, initializing OCR.");
            initializeOCR(); // This call might be redundant if initializeOCR was just called and found no missing languages.
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

    private boolean isNoLanguagesDataMissingFromSet(Set<Language> languagesToCheck) {
        final String dataType = mTrainingDataType;
        if (languagesToCheck == null) {
            languagesToCheck = new HashSet<>();
        }
        // --- START DEBUG LOGGING ---
        Log.d(TAG, "isNoLanguagesDataMissingFromSet: Checking for languages: " + languagesToCheck.stream().map(Language::getCode).collect(Collectors.joining(", ")) + " with data type: " + dataType);
        // --- END DEBUG LOGGING ---
        for (Language language : languagesToCheck) {
            if (isLanguageDataMissing(dataType, language)) {
                Log.d(TAG, "Language data missing for: " + language.getName() + " (" + dataType + "). Returning false (meaning languages ARE missing).");
                return false; // Returns false if ANY language data is missing
            }
        }
        Log.d(TAG, "All required language data is present. Returning true (meaning NO languages are missing).");
        return true; // Returns true if ALL language data is present
    }

    private boolean isLanguageDataMissing(@NonNull String dataType, @NonNull Language language) {
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

    private void startGallerySelectionAndCrop() {
        Log.d(TAG, "startGallerySelectionAndCrop: Launching gallery image selection.");
        galleryPickerLauncher.launch("image/*");
    }

    private void startCameraCaptureAndCrop() {
        Log.d(TAG, "startCameraCaptureAndCrop: Launching camera capture.");
        try {
            File photoFile = createImageFile();
            cameraOutputUri = FileProvider.getUriForFile(
                    this,
                    getApplicationContext().getPackageName() + ".provider", // Corrected authority
                    photoFile
            );
            cameraCaptureLauncher.launch(cameraOutputUri);
        } catch (IOException ex) {
            Log.e(TAG, "Error creating image file for camera: " + ex.getMessage(), ex);
            Toast.makeText(this, "Error creating file for camera image.", Toast.LENGTH_SHORT).show();
            cameraOutputUri = null;
        }
    }

    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (storageDir == null) {
            throw new IOException("External storage directory not available.");
        }
        File image = File.createTempFile(
                imageFileName,
                ".jpg",
                storageDir
        );
        Log.d(TAG, "Created temporary image file for camera: " + image.getAbsolutePath());
        return image;
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.d(TAG, "onActivityResult (legacy): requestCode=" + requestCode);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy: Activity destroyed.");

        stopAccessibilityAnnouncements();
        if (loadingDialog != null && loadingDialog.isShowing()) {
            loadingDialog.dismiss();
        }
        loadingDialog = null;

        if (executorService != null) {
            executorService.shutdownNow();
            Log.d(TAG, "ExecutorService shut down.");
        }
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
            Log.d(TAG, "Language download AlertDialog dismissed.");
        }
        if (mImageTextReader != null) {
            mImageTextReader.tearDownEverything();
            Log.d(TAG, "ImageTextReader torn down.");
        }
        if (cameraOutputUri != null) {
            try {
                File file = new File(cameraOutputUri.getPath());
                if (file.exists()) {
                    file.delete();
                    Log.d(TAG, "Deleted temporary camera file on destroy: " + file.getAbsolutePath());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error deleting temp camera file on destroy: " + e.getMessage());
            }
            cameraOutputUri = null;
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
            Intent intent = new Intent(this, SettingsActivity.class);
            settingsLauncher.launch(intent);
            Log.d(TAG, "Options menu: Settings selected, launching SettingsActivity via launcher.");
            return true;
        } else if (id == R.id.action_history) {
            if (Utils.isLastUsedPdf()) {
                List<String> pdfPageTexts = Utils.getLastUsedPdfPages();
                if (pdfPageTexts != null && !pdfPageTexts.isEmpty()) {
                    showOCRResult(pdfPageTexts);
                    Log.d(TAG, "Options menu: History selected, showing last used PDF page texts.");
                } else {
                    showOCRResult(Utils.getLastUsedText());
                    Log.d(TAG, "Options menu: History selected, but PDF pages empty. Showing combined text.");
                }
            } else {
                showOCRResult(Utils.getLastUsedText());
                Log.d(TAG, "Options menu: History selected, showing last used single image text.");
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onPageSelected(int pageIndex, String pageContent) {
        AccessibilityManager accessibilityManager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (accessibilityManager != null && accessibilityManager.isEnabled()) {
            AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
            event.setClassName(getClass().getName());
            event.setPackageName(getPackageName());
            event.getText().add("Page " + (pageIndex + 1) + ": " + pageContent);
            accessibilityManager.sendAccessibilityEvent(event);

            Log.d(TAG, "TalkBack announcement sent for page " + (pageIndex + 1));
        } else {
            Log.w(TAG, "AccessibilityManager not available or not enabled.");
        }
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
                        loadingMessage.setText(getString(R.string.recognizing_text));
                        currentAccessibilityMessage = getString(R.string.recognizing_text);
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
        Log.d(TAG, "saveBitmapToStorage: Saving bitmap to internal storage.");
        try (FileOutputStream fileOutputStream = openFileOutput("last_file.jpeg", Context.MODE_PRIVATE)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 30, fileOutputStream);
            Log.d(TAG, "Bitmap saved to last_file.jpeg successfully.");
        } catch (IOException e) {
            Log.e(TAG, "saveBitmapToStorage: Failed to save bitmap: " + e.getLocalizedMessage(), e);
        }
    }

    public Bitmap loadBitmapFromStorage() {
        Log.d(TAG, "loadBitmapFromStorage: Loading bitmap from internal storage.");
        Bitmap bitmap = null;
        try (FileInputStream fileInputStream = openFileInput("last_file.jpeg")) {
            bitmap = BitmapFactory.decodeStream(fileInputStream);
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
            Log.d(TAG, "showOCRResult (single text): Bottom sheet result fragment shown.");
            Utils.putLastUsedText(text, false);
            showSaveTextDialog(text);
        } else {
            Log.d(TAG, "showOCRResult (single text): Activity not in RESUMED state, not showing bottom sheet.");
        }
    }



    public void showOCRResult(List<String> pageTexts) {
        if (this.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
            StringBuilder fullTextToSave = new StringBuilder();
            for (int i = 0; i < pageTexts.size(); i++) {
                fullTextToSave.append("--- Page ").append(i + 1).append(" ---\n")
                        .append(pageTexts.get(i)).append("\n\n");
            }
            String combinedText = fullTextToSave.toString();

            BottomSheetResultsFragment bottomSheetResultsFragment = BottomSheetResultsFragment.newInstanceForPdf(pageTexts);
            bottomSheetResultsFragment.show(getSupportFragmentManager(), "bottomSheetResultsFragment");
            Log.d(TAG, "showOCRResult (multi-page PDF): Bottom sheet result fragment shown.");

            Utils.putLastUsedText(combinedText, true);
            Utils.putLastUsedPdfPages(pageTexts);
            Log.d(TAG, "Saved full PDF text and individual pages to history.");

            showSaveTextDialog(combinedText);
        } else {
            Log.d(TAG, "showOCRResult (multi-page PDF): Activity not in RESUMED state, not showing bottom sheet.");
        }
    }


    private void showSaveTextDialog(final String extractedText) {
        new AlertDialog.Builder(this)
                .setTitle("Save Text")
                .setMessage("Do you want to save the extracted text to a public location?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    pendingTextToSave = extractedText;
                    SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault());
                    String suggestedFileName = "OCR_Result_" + timeFormat.format(new Date()) + ".txt";
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_TITLE, suggestedFileName);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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

    private void writeTextToUri(Uri uri, String textToSave) {
        executorService.submit(() -> {
            try (OutputStream outputStream = getContentResolver().openOutputStream(uri)) {
                if (outputStream != null) {
                    outputStream.write(textToSave.getBytes());
                    outputStream.write("\n\n---\n\n".getBytes());
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

    private void openSavedFilesFolder() {
        startActivity(new Intent(MainActivity.this, SavedResultsActivity.class));
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void processPdf(Uri uri) {
        Log.d(TAG, "processPdf: Starting PDF processing for URI: " + uri.toString());
        executorService.execute(() -> {
            isProcessingPdf = true;
            List<String> pageTexts = new ArrayList<>();
            try (ParcelFileDescriptor fileDescriptor = getContentResolver().openFileDescriptor(uri, "r");
                 PdfRenderer renderer = new PdfRenderer(fileDescriptor)) {

                final int pageCount = renderer.getPageCount();
                Log.d(TAG, "PDF opened successfully. Total pages: " + pageCount);

                handler.post(this::showLoadingDialog);
                handler.post(() -> {
                    if (loadingDialog != null && loadingDialog.isShowing()) {
                        if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE);
                        if (dialogProgressBar != null) {
                            dialogProgressBar.setVisibility(View.VISIBLE);
                            dialogProgressBar.setMax(pageCount);
                            dialogProgressBar.setProgress(0);
                        }
                        if (loadingMessage != null) {
                            String msg = String.format(Locale.getDefault(), "Processing page 1 of %d...", pageCount);
                            loadingMessage.setText(msg);
                            currentAccessibilityMessage = msg;
                        }
                        isProgressBarVisibleInDialog = true;
                        Log.d(TAG, "UI: Switched loading dialog to determinate for PDF processing.");
                    }
                });

                for (int i = 0; i < pageCount; i++) {
                    final int currentPage = i + 1;
                    handler.post(() -> {
                        if (dialogProgressBar != null) dialogProgressBar.setProgress(currentPage);
                        if (loadingMessage != null) {
                            String msg = String.format(Locale.getDefault(), "Processing page %d of %d...", currentPage, pageCount);
                            loadingMessage.setText(msg);
                            currentAccessibilityMessage = msg;
                        }
                        Log.d(TAG, "UI: PDF Progress updated to " + currentPage + "/" + pageCount);
                    });

                    PdfRenderer.Page page = null;
                    Bitmap originalBitmap = null;
                    Bitmap processedBitmap = null;
                    try {
                        page = renderer.openPage(i);
                        Log.d(TAG, "Opened PDF page: " + (i + 1));

                        float targetDpi = 200f;
                        int width = (int) (page.getWidth() / 72f * targetDpi);
                        int height = (int) (page.getHeight() / 72f * targetDpi);
                        originalBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

                        Matrix matrix = new Matrix();
                        matrix.postScale((float) width / page.getWidth(), (float) height / page.getHeight());
                        Rect clip = new Rect(0, 0, width, height);
                        page.render(originalBitmap, clip, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        Log.i(TAG, "Page " + (i + 1) + " successfully rendered AND SCALED. Dimensions: " + originalBitmap.getWidth() + "x" + originalBitmap.getHeight());

                        if (i == 0 && originalBitmap != null) {
                            final Bitmap previewBitmap = originalBitmap.copy(originalBitmap.getConfig(), false);
                            handler.post(() -> {
                                saveBitmapToStorage(previewBitmap);
                                mImageView.setImageBitmap(previewBitmap);
                                Log.d(TAG, "Updated recent view with first page of PDF.");
                            });
                        }

                        processedBitmap = preprocessBitmap(originalBitmap);
                        Log.d(TAG, "Page " + (i + 1) + " preprocessed.");

                        if (mImageTextReader != null) {
                            String result = mImageTextReader.getTextFromBitmap(processedBitmap);
                            pageTexts.add(Html.fromHtml(result != null ? result : "").toString().trim());
                            Log.d(TAG, "OCR result for Page " + (i + 1) + ": " + (result != null ? result.substring(0, Math.min(result.length(), 100)) + "..." : "No text found"));
                        } else {
                            Log.e(TAG, "ImageTextReader is not initialized for PDF OCR on Page " + (i + 1) + ". Skipping OCR for this page.");
                            pageTexts.add("OCR failed for Page " + (i + 1) + ": ImageTextReader not ready.");
                        }
                    } finally {
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
                }

                handler.post(() -> {
                    isProcessingPdf = false;
                    dismissLoadingDialog(getString(R.string.processing_completed));
                    Log.d(TAG, "UI: PDF processing finished, loading dialog dismissed.");
                    showOCRResult(pageTexts);
                    Log.d(TAG, "showOCRResult called for PDF page texts.");
                });

            } catch (IOException e) {
                Log.e(TAG, "Error processing PDF: " + e.getLocalizedMessage(), e);
                handler.post(() -> {
                    isProcessingPdf = false;
                    dismissLoadingDialog("PDF processing failed");
                    Toast.makeText(this, "Failed to read PDF. Error: " + e.getLocalizedMessage(), Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error during PDF processing or OCR: " + e.getLocalizedMessage(), e);
                handler.post(() -> {
                    isProcessingPdf = false;
                    dismissLoadingDialog("An unexpected error occurred");
                    Toast.makeText(this, "An unexpected error occurred during PDF processing.", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private Bitmap preprocessBitmap(Bitmap bmpOriginal) {
        Log.d(TAG, "preprocessBitmap: Starting image preprocessing. Original dimensions: " + bmpOriginal.getWidth() + "x" + bmpOriginal.getHeight());
        int width = bmpOriginal.getWidth();
        int height = bmpOriginal.getHeight();

        Bitmap bmpGrayscale = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmpGrayscale);
        Paint paint = new Paint();
        ColorMatrix colorMatrix = new ColorMatrix();
        colorMatrix.setSaturation(0);
        ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
        paint.setColorFilter(filter);
        canvas.drawBitmap(bmpOriginal, 0, 0, paint);
        Log.d(TAG, "Bitmap converted to grayscale.");

        Bitmap bmpBinarized = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[width * height];
        bmpGrayscale.getPixels(pixels, 0, width, 0, 0, width, height);

        int threshold = 128;
        Log.d(TAG, "Applying binarization with threshold: " + threshold);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = pixels[y * width + x];
                int gray = Color.red(pixel);

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

    private void initAccessibilityAnnouncer() {
        accessibilityHandler = new Handler(Looper.getMainLooper());
        accessibilityRunnable = new Runnable() {
            @Override
            public void run() {
                if (loadingDialog != null && loadingDialog.isShowing() && loadingMessage != null) {
                    loadingMessage.announceForAccessibility(currentAccessibilityMessage);
                    accessibilityHandler.postDelayed(this, 2500);
                }
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
        if (accessibilityHandler != null) {
            accessibilityHandler.removeCallbacks(accessibilityRunnable);
        }
    }

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

        if (loadingSpinner != null) {
            loadingSpinner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        if (dialogProgressBar != null) {
            dialogProgressBar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

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
        loadingSpinner = null;
        dialogProgressBar = null;
        loadingMessage = null;
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
            handler.post(() -> {
                showLoadingDialog();
                mProgressIndicator.setProgress(0);
                mProgressIndicator.setVisibility(View.VISIBLE);
                animateImageViewAlpha(0.2f);
                Log.d(TAG, "UI: Progress indicator visible, ImageView alpha set to 0.2.");
            });

            if (!isRefresh && Utils.isPreProcessImage()) {
                Log.d(TAG, "Applying pre-processing to image.");
                bitmap = Utils.preProcessBitmap(bitmap);
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
                text = "OCR Engine not initialized. Please restart app or check settings.";
            }

            final String finalCleanText = (text != null) ? Html.fromHtml(text).toString().trim() : "";
            final int accuracy = (mImageTextReader != null) ? mImageTextReader.getAccuracy() : -1;

            handler.post(() -> {
                dismissLoadingDialog(getString(R.string.processing_completed));
                mProgressIndicator.setVisibility(View.GONE);
                animateImageViewAlpha(1f);
                Log.d(TAG, "UI: Progress indicator hidden, ImageView alpha restored.");
                showOCRResult(finalCleanText);
                Toast.makeText(MainActivity.this, "With Confidence: " + accuracy + "%", Toast.LENGTH_SHORT).show();
                Log.d(TAG, "OCR Result shown. Confidence: " + accuracy + "%");
                Utils.putLastUsedText(finalCleanText, false);
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
                Log.w(TAG, "Could not update ImageView, loaded bitmap is null.");
            }
        }
    }


    private class DownloadTraining implements Runnable {
        private final String dataType;
        private final Set<Language> languages;

        public DownloadTraining(String dataType, Set<Language> langs) {
            this.dataType = dataType;
            this.languages = (langs != null) ? langs : new HashSet<>();
            Log.d(TAG, "DownloadTraining task created for " + languages.size() + " languages (" + dataType + ").");
        }

        @Override
        public void run() {
            handler.post(() -> {
                Toast.makeText(MainActivity.this, "Please wait while the language is being downloaded...", Toast.LENGTH_LONG).show();
                mProgressMessage.setText(getString(R.string.downloading_language));
                mDownloadLayout.setVisibility(View.VISIBLE);
                mProgressBar.setVisibility(View.GONE);
                Log.d(TAG, "UI: Download layout visible, message set to downloading.");
            });

            boolean success = true;
            for (Language lang : languages) {
                Log.d(TAG, "Attempting to download language: " + lang.getCode());
                if (!downloadTrainingData(dataType, lang.getCode())) {
                    success = false;
                    Log.e(TAG, "Failed to download language: " + lang.getCode() + ". Aborting further downloads for this batch.");
                    break;
                }
            }

            final boolean finalSuccess = success;
            handler.post(() -> {
                mDownloadLayout.setVisibility(View.GONE);
                if (finalSuccess) {
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
                String originalUrl = downloadURL;
                downloadURL = followRedirects(conn, downloadURL);
                if (!originalUrl.equals(downloadURL)) {
                    Log.d(TAG, "Redirected download URL for " + lang + ": " + downloadURL);
                }
                conn = (HttpURLConnection) new URL(downloadURL).openConnection();
                conn.connect();
                int totalContentSize = conn.getContentLength();
                if (totalContentSize <= 0) {
                    Log.e(TAG, "Invalid content size for " + lang + ": " + totalContentSize);
                    return false;
                }
                final String size = Utils.getSize(totalContentSize);
                Log.d(TAG, "Total content size for " + lang + ": " + size);

                handler.post(() -> {
                    mProgressBar.setVisibility(View.VISIBLE);
                    mProgressMessage.setText(String.format("0%s%s", getString(R.string.percentage_downloaded), size));
                    mProgressBar.setProgress(0);
                    Log.d(TAG, "UI: Progress bar for download visible, initial message set.");
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
                default:
                    url = lang.equals("akk") ? Constants.TESSERACT_DATA_DOWNLOAD_URL_AKK_FAST : Constants.TESSERACT_DATA_DOWNLOAD_URL_EQU;
                    break;
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
                        return downloadURL;
                    }
                    URL base = new URL(downloadURL);
                    downloadURL = new URL(base, location).toExternalForm();
                    conn.disconnect();
                    conn = (HttpURLConnection) new URL(downloadURL).openConnection();
                    conn.setInstanceFollowRedirects(false);
                    Log.d(TAG, "Followed redirect (" + responseCode + ") to: " + downloadURL);
                    redirectCount++;
                    if (redirectCount > 5) {
                        Log.e(TAG, "Too many redirects for URL: " + downloadURL);
                        throw new IOException("Too many redirects");
                    }
                } else {
                    break;
                }
            }
            return downloadURL;
        }
    }
}
