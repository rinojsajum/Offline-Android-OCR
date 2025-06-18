package io.github.subhamtyagi.ocr;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.pdf.PdfRenderer; // From development branch
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment; // Added from development2 branch
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor; // From development branch
import android.provider.DocumentsContract; // Added from development2 branch
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

import androidx.activity.result.ActivityResultLauncher; // From development2 branch
import androidx.activity.result.contract.ActivityResultContracts; // From development2 branch
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi; // From development branch
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.googlecode.tesseract.android.TessBaseAPI;

import com.canhub.cropper.CropImage; // From development2 branch
import com.canhub.cropper.CropImageContract; // From development2 branch
import com.canhub.cropper.CropImageContractOptions; // From development2 branch
import com.canhub.cropper.CropImageOptions; // From development2 branch
import com.canhub.cropper.CropImageView; // From development2 branch

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat; // From development2 branch
import java.util.Date; // From development2 branch
import java.util.HashSet;
import java.util.Locale; // From development2 branch
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

    private String pendingTextToSave = null; // From development2 branch (for SAF save)
    private static final int REQUEST_CODE_PICK_PDF = 1001; // From development branch

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
    private FloatingActionButton mFloatingActionButton; // This is the 'Scan/Gallery' button
    private LinearLayout mDownloadLayout;
    /**
     * Language name to be displayed
     */
    private TextView mLanguageName;
    private ExecutorService executorService;
    private Handler handler;
    private LinearProgressIndicator mProgressBar;
    private TextView mProgressMessage;

    private FloatingActionButton mSavedFilesFab; // From development2 branch
    private FloatingActionButton mPdfFab; // New: For the PDF button

    // ActivityResultLaunchers from development2 for modern AndroidX approach
    private ActivityResultLauncher<CropImageContractOptions> cropImageLauncher;
    private ActivityResultLauncher<Intent> createDocumentLauncher;
    // ActivityResultLauncher for PDF picking, using the same pattern as SAF
    private ActivityResultLauncher<Intent> pickPdfLauncher;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SpUtil.getInstance().init(this);

        // Initialize ActivityResultLauncher for image cropping (from development2)
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
                Log.e(TAG, "Image cropping failed: " + (error != null ? error.getMessage() : "Unknown error"));
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
                }
            } else {
                Toast.makeText(this, "File save cancelled or failed.", Toast.LENGTH_SHORT).show();
                pendingTextToSave = null;
            }
        });

        // Initialize ActivityResultLauncher for PDF picking (new, adapted from development's onActivityResult)
        pickPdfLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Uri pdfUri = result.getData().getData();
                if (pdfUri != null) {
                    // Check Android version as PdfRenderer requires LOLLIPOP (API 21)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        processPdf(pdfUri);
                    } else {
                        Toast.makeText(this, "PDF processing requires Android 5.0 (Lollipop) or higher.", Toast.LENGTH_LONG).show();
                    }
                }
            } else {
                Toast.makeText(this, "PDF selection cancelled or failed.", Toast.LENGTH_SHORT).show();
            }
        });


        // Find all UI elements
        mImageView = findViewById(R.id.source_image);
        mProgressIndicator = findViewById(R.id.progress_indicator);
        mSwipeRefreshLayout = findViewById(R.id.swipe_to_refresh);
        mFloatingActionButton = findViewById(R.id.btn_scan); // This is the 'Gallery' button
        mLanguageName = findViewById(R.id.language_name1);

        mProgressBar = findViewById(R.id.progress_bar);
        mProgressMessage = findViewById(R.id.progress_message);
        mDownloadLayout = findViewById(R.id.download_layout);

        mSavedFilesFab = findViewById(R.id.btn_saved_files); // From development2
        mPdfFab = findViewById(R.id.btn_pdf); // From development

        executorService = Executors.newFixedThreadPool(1);
        handler = new Handler(Looper.getMainLooper());

        initDirectories();
        initializeOCR();
        initViews();
    }

    private void initViews() {
        // Listener for the 'Gallery/Scan' button (from both, consolidated)
        mFloatingActionButton.setOnClickListener(v -> {
            if (isNoLanguagesDataMissingFromSet()) {
                if (mImageTextReader != null) {
                    selectImage(); // Now uses the new ActivityResultLauncher
                } else {
                    initializeOCR();
                }
            } else {
                downloadLanguageData();
            }
        });

        // Listener for the 'PDF' button (from development)
        mPdfFab.setOnClickListener(v -> openPdfPicker());

        // Listener for the 'Saved Files' button (from development2)
        mSavedFilesFab.setOnClickListener(v -> openSavedFilesFolder());

        // SwipeRefreshLayout listener (from both, consolidated)
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

        // Load last image from storage if persistent data is enabled (from both, consolidated)
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
        // Updated language display logic for consistency (from development2, compatible with development)
        Set<Language> languages = Utils.getTrainingDataLanguages(this);
        if (languages == null) {
            languages = new HashSet<>();
        }
        mLanguageName.setText(languages.stream().map(Language::getName).collect(Collectors.joining(", ")));
    }

    /**
     * Opens the PDF picker for selecting a PDF file (from development)
     */
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
        // Ensure currentDirectory is set to a valid initial value (standard by default)
        // Consolidated from both branches
        currentDirectory = new File(dirStandard, "tessdata");
    }


    /**
     * initialize the OCR i.e tesseract api
     * if there is no training data in directory than it will ask for download
     */
    private void initializeOCR() {
        Set<Language> languages = Utils.getTrainingDataLanguages(this);
        if (languages == null) { // Ensure languages is not null
            languages = new HashSet<>();
        }
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
            default: // Assuming "fast" is the default (from development)
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
                    handler.post(() -> handleReaderException(languages)); // Run on UI thread for Toast compatibility
                }
            } catch (Exception e) {
                Log.e(TAG, "Error initializing OCR: " + e.getMessage(), e);
                handler.post(() -> handleReaderException(languages)); // Run on UI thread for Toast compatibility
            }
        }).start();
    }

    private void handleReaderException(Set<Language> languages) {
        File destFile = new File(currentDirectory, String.format(Constants.LANGUAGE_CODE, languages));
        if (destFile.exists()) {
            destFile.delete(); // Only delete if it exists
            Toast.makeText(this, "Error with OCR data for " + languages.stream().map(Language::getName).collect(Collectors.joining(", ")) + ". Please try downloading again.", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "OCR initialization failed. Language data might be corrupted or missing.", Toast.LENGTH_LONG).show();
        }
        mImageTextReader = null;
    }

    private void downloadLanguageData() {
        Set<Language> missingLanguage = new HashSet<>();
        Set<Language> languages = Utils.getTrainingDataLanguages(this);
        if (languages == null) { // Ensure languages is not null
            languages = new HashSet<>();
        }

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
        dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.training_data_missing)
                .setCancelable(false)
                .setMessage(msg)
                .setPositiveButton(R.string.yes, (dialog, which) -> {
                    dialog.cancel();
                    executorService.submit(new DownloadTraining(mTrainingDataType, missingLanguage));
                })
                .setNegativeButton(R.string.no, (dialog, which) -> dialog.cancel())
                .create();
        dialog.show();

    }

    private boolean isNoLanguagesDataMissingFromSet() {
        final String dataType = mTrainingDataType;
        Set<Language> languages = Utils.getTrainingDataLanguages(this);
        if (languages == null) { // Ensure languages is not null
            languages = new HashSet<>();
        }
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

    /**
     * Launches the image cropping activity using the modern ActivityResultLauncher (from development2)
     */
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
            Log.e(TAG, "convertImageToText: " + e.getLocalizedMessage());
            Toast.makeText(this, "Failed to load image: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
        if (bitmap != null) {
            mImageView.setImageURI(imageUri);
            executorService.submit(new ConvertImageToText(bitmap));
        }
    }

    // This onActivityResult is now simplified, as most intent results are handled by ActivityResultLaunchers.
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_SETTINGS) {
            initializeOCR();
        }
        // No need for CropImage.CROP_IMAGE_ACTIVITY_REQUEST_CODE or REQUEST_CODE_PICK_PDF here,
        // as they are handled by their respective ActivityResultLaunchers.
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
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
        runOnUiThread(() -> mProgressIndicator.setProgress((int) (progressValues.getPercent() * 1.46)));
    }

    public void saveBitmapToStorage(Bitmap bitmap) {
        FileOutputStream fileOutputStream;
        try {
            fileOutputStream = openFileOutput("last_file.jpeg", Context.MODE_PRIVATE);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 30, fileOutputStream);
            fileOutputStream.close();
        } catch (IOException e) {
            Log.e(TAG, "saveBitmapToStorage: " + e.getLocalizedMessage());
        }
    }

    public Bitmap loadBitmapFromStorage() {
        Bitmap bitmap = null;
        FileInputStream fileInputStream;
        try {
            fileInputStream = openFileInput("last_file.jpeg");
            bitmap = BitmapFactory.decodeStream(fileInputStream);
            fileInputStream.close();

        } catch (IOException e) {
            Log.e(TAG, "loadBitmapFromStorage: " + e.getLocalizedMessage());
        }
        return bitmap;
    }

    public void showOCRResult(String text) {
        if (this.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
            BottomSheetResultsFragment bottomSheetResultsFragment = BottomSheetResultsFragment.newInstance(text);
            bottomSheetResultsFragment.show(getSupportFragmentManager(), "bottomSheetResultsFragment");
            // Prompt the user to save to public location (SAF) - from development2
            showSaveTextDialog(text);
        }
    }

    /**
     * Displays a dialog asking the user to save the extracted text to a public location (SAF).
     * (From development2 branch)
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
     * (From development2 branch)
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
     * (From development2 branch)
     */
    private void openSavedFilesFolder() {
        startActivity(new Intent(MainActivity.this, SavedResultsActivity.class));
    }

    /**
     * Processes a selected PDF URI, rendering pages to bitmaps and performing OCR.
     * Requires Android 5.0 (Lollipop) or higher.
     * (From development branch)
     *
     * @param uri The URI of the PDF file.
     */
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void processPdf(Uri uri) {
        executorService.execute(() -> {
            try (ParcelFileDescriptor fileDescriptor = getContentResolver().openFileDescriptor(uri, "r");
                 PdfRenderer renderer = new PdfRenderer(fileDescriptor)) {

                int pageCount = renderer.getPageCount();
                StringBuilder fullText = new StringBuilder();

                handler.post(() -> { // Use handler for UI updates
                    mProgressBar.setMax(pageCount);
                    mProgressBar.setProgress(0);
                    mProgressBar.setVisibility(View.VISIBLE);
                    mProgressMessage.setText("Processing PDF...");
                });

                for (int i = 0; i < pageCount; i++) {
                    PdfRenderer.Page page = renderer.openPage(i);

                    // Set high-resolution rendering
                    int width = page.getWidth() * 2;   // 2x scaling
                    int height = page.getHeight() * 2;

                    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    Rect rect = new Rect(0, 0, width, height);
                    page.render(bitmap, rect, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    bitmap = toGrayscale(bitmap);
                    page.close();

                    String result = mImageTextReader.getTextFromBitmap(bitmap); // Use existing getTextFromBitmap()
                    fullText.append("Page ").append(i + 1).append(":\n").append(result).append("\n\n");

                    final int progress = i + 1;
                    handler.post(() -> mProgressBar.setProgress(progress)); // Use handler for UI updates
                }

                handler.post(() -> { // Use handler for UI updates
                    mProgressMessage.setText("OCR Complete!");
                    mProgressBar.setVisibility(View.GONE);
                    showResultDialog(fullText.toString());
                });

            } catch (IOException e) {
                Log.e(TAG, "Error processing PDF: ", e);
                handler.post(() -> Toast.makeText(this, "Failed to read PDF.", Toast.LENGTH_SHORT).show()); // Use handler for UI updates
            }
        });
    }

    /**
     * Converts a given bitmap to grayscale.
     * (From development branch)
     *
     * @param bmpOriginal The original color bitmap.
     * @return The grayscale version of the bitmap.
     */
    private Bitmap toGrayscale(Bitmap bmpOriginal) {
        int width, height;
        height = bmpOriginal.getHeight();
        width = bmpOriginal.getWidth();

        Bitmap bmpGrayscale = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmpGrayscale);
        Paint paint = new Paint();
        ColorMatrix colorMatrix = new ColorMatrix();
        colorMatrix.setSaturation(0);  // Remove color (convert to grayscale)
        ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
        paint.setColorFilter(filter);
        canvas.drawBitmap(bmpOriginal, 0, 0, paint);
        return bmpGrayscale;
    }

    /**
     * Displays a basic AlertDialog with the OCR result.
     * (From development branch)
     *
     * @param text The OCR extracted text to display.
     */
    private void showResultDialog(String text) {
        new AlertDialog.Builder(this)
                .setTitle("OCR Result")
                .setMessage(text)
                .setPositiveButton("OK", null)
                .setCancelable(true)
                .show();
    }


    private class ConvertImageToText implements Runnable {
        private Bitmap bitmap;

        public ConvertImageToText(Bitmap bitmap) {
            this.bitmap = bitmap;
        }

        @Override
        public void run() {
            // Pre-execution on UI thread
            handler.post(() -> {
                mProgressIndicator.setProgress(0);
                mProgressIndicator.setVisibility(View.VISIBLE);
                animateImageViewAlpha(0.2f);
            });

            // Background execution
            if (!isRefresh && Utils.isPreProcessImage()) {
                bitmap = Utils.preProcessBitmap(bitmap);
            }
            isRefresh = false;
            saveBitmapToStorage(bitmap);
            String text = mImageTextReader.getTextFromBitmap(bitmap);

            // Post-execution on UI thread
            handler.post(() -> {
                mProgressIndicator.setVisibility(View.GONE);
                animateImageViewAlpha(1f);
                String cleanText = Html.fromHtml(text).toString().trim();
                showOCRResult(cleanText); // This now calls the combined showOCRResult
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
            this.languages = (langs != null) ? langs : new HashSet<>(); // Ensure languages is not null
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
                default: // Default case for "fast" from development2. Development had a slightly different format for the default case.
                    return lang.equals("akk") ? Constants.TESSERACT_DATA_DOWNLOAD_URL_AKK_FAST : Constants.TESSERACT_DATA_DOWNLOAD_URL_EQU ;
            }
        }

        private String followRedirects(HttpURLConnection conn, String downloadURL) throws IOException {
            while (true) {
                int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_MOVED_PERM || responseCode == HttpURLConnection.HTTP_MOVED_TEMP) {
                    String location = conn.getHeaderField("Location");
                    URL base = new URL(downloadURL);
                    downloadURL = new URL(base, location).toExternalForm(); // Handle relative URLs
                    conn = (HttpURLConnection) new URL(downloadURL).openConnection(); // Re-open connection
                } else {
                    break; // No more redirects
                }
            }
            return downloadURL;
        }
    }
}
