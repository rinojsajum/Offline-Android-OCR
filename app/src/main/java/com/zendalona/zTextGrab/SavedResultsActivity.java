package com.zendalona.zTextGrab;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;


public class SavedResultsActivity extends AppCompatActivity implements OnItemClickListener {

    private static final String TAG = "SavedResultsActivity";
    private RecyclerView recyclerView;
    private TextView noFilesTextView;
    private Button openFileButton;
    private TextView recentFilesHeader;

    private SavedFilesAdapter adapter;
    private List<RecentFileItem> savedFilesList;

    private ActivityResultLauncher<Intent> openDocumentLauncher;
    private ActivityResultLauncher<Intent> openDocumentTreeLauncher;

    private Uri ocrResultsTreeUri = null;
    private static final String PREFS_OCR_RESULTS_URI = "ocr_results_tree_uri";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_saved_results);

        // Toolbar setup (if enabled)
        // Toolbar toolbar = findViewById(R.id.toolbar);
        // setSupportActionBar(toolbar);
        // if (getSupportActionBar() != null) {
        //     getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        //     getSupportActionBar().setTitle("Saved OCR Results");
        // }

        openFileButton = findViewById(R.id.btn_open_file_picker);
        recyclerView = findViewById(R.id.rv_saved_files);
        noFilesTextView = findViewById(R.id.tv_no_saved_files);
        recentFilesHeader = findViewById(R.id.tv_recent_files_header);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        savedFilesList = new ArrayList<>();
        adapter = new SavedFilesAdapter(this, savedFilesList, this);
        recyclerView.setAdapter(adapter);

        openDocumentLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Uri uri = result.getData().getData();
                if (uri != null) {
                    openFileByUri(uri);
                } else {
                    Toast.makeText(this, "Failed to get URI for selected file.", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "File selection cancelled.", Toast.LENGTH_SHORT).show();
            }
        });

        openDocumentTreeLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Uri treeUri = result.getData().getData();
                if (treeUri != null) {
                    final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                    getContentResolver().takePersistableUriPermission(treeUri, takeFlags);

                    ocrResultsTreeUri = treeUri;
                    // Changed to .commit() for synchronous save
                    getPreferences(Context.MODE_PRIVATE).edit()
                            .putString(PREFS_OCR_RESULTS_URI, treeUri.toString())
                            .commit(); // Use commit() here

                    Toast.makeText(this, "OCR Results folder selected and access granted.", Toast.LENGTH_SHORT).show();
                    // Removed loadRecentSavedFilesFromSAF() call here, it will be handled by onResume()
                } else {
                    Toast.makeText(this, "Failed to get URI for selected folder.", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "Folder selection cancelled. Cannot list recent files.", Toast.LENGTH_SHORT).show();
                updateRecentFilesUI(false, "Folder access denied.");
            }
        });

        openFileButton.setOnClickListener(v -> launchFilePicker());

        // Load persisted URI on startup
        String savedUriString = getPreferences(Context.MODE_PRIVATE).getString(PREFS_OCR_RESULTS_URI, null);
        if (savedUriString != null) {
            ocrResultsTreeUri = Uri.parse(savedUriString);
        }

        // Only prompt if URI is not set on initial creation
        if (ocrResultsTreeUri == null) {
            promptForOcrResultsFolder();
        }

        // Always attempt to load files. If URI is null, it will result in "No files" message.
        loadRecentSavedFilesFromSAF();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Always attempt to load files on resume. This ensures updates after returning from folder picker.
        loadRecentSavedFilesFromSAF();
    }

    private void loadRecentSavedFilesFromSAF() {
        if (ocrResultsTreeUri == null) {
            updateRecentFilesUI(false, "Please select the 'OCR_Results' folder to view recent files.");
            return;
        }

        // Verify if the persisted URI is still valid
        try {
            // Attempt to take permission again to validate its persistence.
            // If the permission has been revoked by the system or user, this will throw a SecurityException.
            getContentResolver().takePersistableUriPermission(ocrResultsTreeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            Log.e(TAG, "Persisted URI permission revoked for: " + ocrResultsTreeUri.toString(), e);
            // Clear the invalid URI and prompt again for selection
            ocrResultsTreeUri = null;
            getPreferences(Context.MODE_PRIVATE).edit().remove(PREFS_OCR_RESULTS_URI).apply(); // Use apply() for clearing, as it's not critical for immediate read.
            promptForOcrResultsFolder();
            return;
        }

        new Thread(() -> {
            List<RecentFileItem> foundItems = new ArrayList<>();
            long sevenDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7);

            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    ocrResultsTreeUri,
                    DocumentsContract.getTreeDocumentId(ocrResultsTreeUri)
            );

            String[] projection = new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
            };

            try (Cursor cursor = getContentResolver().query(
                    childrenUri,
                    projection,
                    null,
                    null,
                    null
            )) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                    int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                    int lastModifiedColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                    int mimeTypeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);

                    do {
                        String documentId = cursor.getString(idColumn);
                        String displayName = cursor.getString(nameColumn);
                        long lastModified = cursor.getLong(lastModifiedColumn);
                        String mimeType = cursor.getString(mimeTypeColumn);

                        // FIX: Add a condition to filter out files that have been moved to the trash
                        if (mimeType != null && mimeType.equals("text/plain") && displayName.endsWith(".txt") && lastModified >= sevenDaysAgo && !displayName.startsWith(".trash")) {
                            Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(ocrResultsTreeUri, documentId);
                            foundItems.add(new RecentFileItem(documentUri, displayName, lastModified));
                        }
                    } while (cursor.moveToNext());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error querying SAF documents: " + e.getMessage(), e);
                runOnUiThread(() -> Toast.makeText(SavedResultsActivity.this, "Error listing files: " + e.getMessage(), Toast.LENGTH_LONG).show());
                updateRecentFilesUI(false, "Error listing files from selected folder.");
                return;
            }

            Collections.sort(foundItems, (f1, f2) -> Long.compare(f2.lastModified, f1.lastModified));

            List<RecentFileItem> top10RecentFiles = new ArrayList<>();
            for (int i = 0; i < Math.min(foundItems.size(), 10); i++) {
                top10RecentFiles.add(foundItems.get(i));
            }

            runOnUiThread(() -> {
                savedFilesList.clear();
                savedFilesList.addAll(top10RecentFiles);
                adapter.notifyDataSetChanged();
                updateRecentFilesUI(!top10RecentFiles.isEmpty(), null);
            });

        }).start();
    }

    private void promptForOcrResultsFolder() {
        runOnUiThread(() -> {
            recentFilesHeader.setVisibility(View.VISIBLE);
            noFilesTextView.setText("Please select your 'Documents/OCR_Results' folder to view recent files.\n(If it doesn't exist, you can create it first when saving a file from the main screen.)");
            noFilesTextView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);

            new AlertDialog.Builder(this)
                    .setTitle("Select OCR Results Folder")
                    .setMessage("To display your recent OCR files, please select the 'OCR_Results' folder inside your device's 'Documents' directory. If you haven't saved a file there yet, create the 'OCR_Results' folder when saving your first file from the main screen, then select it here.")
                    .setPositiveButton("Select Folder", (dialog, which) -> {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            Uri initialUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments");
                            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);
                        }
                        openDocumentTreeLauncher.launch(intent);
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> {
                        dialog.dismiss();
                        updateRecentFilesUI(false, "Folder selection cancelled. Recent files cannot be displayed.");
                    })
                    .setCancelable(false)
                    .show();
        });
    }

    private void updateRecentFilesUI(boolean hasFiles, String message) {
        if (hasFiles) {
            recentFilesHeader.setVisibility(View.VISIBLE);
            noFilesTextView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        } else {
            recentFilesHeader.setVisibility(View.VISIBLE);
            noFilesTextView.setText(message != null ? message : "No recent OCR files found.");
            noFilesTextView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        }
    }


    private void launchFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Uri defaultDocumentsUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADocuments");
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, defaultDocumentsUri);
        }

        try {
            openDocumentLauncher.launch(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open file picker: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Error launching file picker: " + e.getMessage());
        }
    }

    private void openFileByUri(Uri fileUri) {
        if (fileUri == null) {
            Toast.makeText(this, "Cannot open file: file URI is null.", Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder text = new StringBuilder();
        ParcelFileDescriptor pfd = null;
        try {
            // Use DocumentsContract.openDocument() for a more robust way to open the file from a SAF URI
            pfd = getContentResolver().openFileDescriptor(fileUri, "r");
            if (pfd != null) {
                try (InputStream inputStream = new FileInputStream(pfd.getFileDescriptor());
                     BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        text.append(line);
                        text.append('\n');
                    }
                }
            } else {
                Toast.makeText(this, "Error: The selected file could not be opened.", Toast.LENGTH_LONG).show();
                Log.e(TAG, "Failed to open ParcelFileDescriptor for URI: " + fileUri.toString());
                return;
            }

            if (text.length() > 0) {
                BottomSheetResultsFragment bottomSheet = BottomSheetResultsFragment.newInstance(text.toString());
                bottomSheet.show(getSupportFragmentManager(), "fileContentSheet");
            } else {
                Toast.makeText(this, "Selected file is empty.", Toast.LENGTH_SHORT).show();
            }

        } catch (Exception e) { // Catch all exceptions to prevent crashes
            Log.e(TAG, "An unexpected error occurred while opening the file.", e);
            Toast.makeText(this, "An unexpected error occurred. Please try again.", Toast.LENGTH_LONG).show();
        } finally {
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing ParcelFileDescriptor", e);
                }
            }
        }
    }


    @Override
    public void onItemClick(Uri uri) {
        openFileByUri(uri);
    }


    private static class RecentFileItem {
        Uri uri;
        String displayName;
        long lastModified;

        RecentFileItem(Uri uri, String displayName, long lastModified) {
            this.uri = uri;
            this.displayName = displayName;
            this.lastModified = lastModified;
        }
    }

    private static class SavedFilesAdapter extends RecyclerView.Adapter<SavedFilesAdapter.FileViewHolder> {

        private final Context context;
        private final List<RecentFileItem> items;
        private final OnItemClickListener listener;

        public SavedFilesAdapter(Context context, List<RecentFileItem> items, OnItemClickListener listener) {
            this.context = context;
            this.items = items;
            this.listener = listener;
        }

        @NonNull
        @Override
        public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(context).inflate(R.layout.item_saved_file, parent, false);
            return new FileViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
            RecentFileItem item = items.get(position);
            holder.fileNameTextView.setText(item.displayName);

            SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault());
            String formattedDate = sdf.format(new Date(item.lastModified));
            holder.filePathTextView.setText("Last modified: " + formattedDate);

            holder.itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onItemClick(item.uri);
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        public static class FileViewHolder extends RecyclerView.ViewHolder {
            TextView fileNameTextView;
            TextView filePathTextView;

            public FileViewHolder(@NonNull View itemView) {
                super(itemView);
                fileNameTextView = itemView.findViewById(R.id.tv_file_name);
                filePathTextView = itemView.findViewById(R.id.tv_file_path);
            }
        }
    }
}