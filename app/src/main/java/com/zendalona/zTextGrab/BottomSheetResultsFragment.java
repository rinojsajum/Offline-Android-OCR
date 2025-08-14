package com.zendalona.zTextGrab;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.googlecode.tesseract.android.TessBaseAPI;

import java.util.ArrayList;
import java.util.List;

public class BottomSheetResultsFragment extends BottomSheetDialogFragment {

    private static final String ARG_TEXT_SINGLE = "arg_text_single";
    private static final String ARG_TEXT_LIST = "arg_text_list";
    private static final String TAG = "BottomSheetResultsFrag";

    private String singleText; // Holds single image text OR concatenated PDF text
    private List<String> pageTexts; // Holds individual page texts for PDF display
    private LinearLayout containerLayout; // The LinearLayout inside the ScrollView to hold page views
    private ScrollView scrollView; // Your addition: Reference to the ScrollView
    private BottomSheetBehavior<View> bottomSheetBehavior; // Your addition: Reference to the BottomSheetBehavior

    // Combined OnPageSelectedListener declaration
    private OnPageSelectedListener pageSelectedListener;


    public BottomSheetResultsFragment() {
        // Required empty public constructor
    }

    public interface OnPageSelectedListener {
        void onPageSelected(int pageIndex, String pageContent);
        // Friend's addition
        void onProgressValues(TessBaseAPI.ProgressValues progressValues);
    }

    // Friend's method - kept
    private void notifyPageSelected(int pageIndex, String pageContent) {
        if (pageSelectedListener != null) {
            pageSelectedListener.onPageSelected(pageIndex, pageContent);
        }
    }

    public static BottomSheetResultsFragment newInstance(String text) {
        BottomSheetResultsFragment fragment = new BottomSheetResultsFragment();
        Bundle args = new Bundle();
        args.putString(ARG_TEXT_SINGLE, text);
        fragment.setArguments(args);
        // Your addition
        Log.d(TAG, "newInstance (single text) called.");
        return fragment;
    }

    public static BottomSheetResultsFragment newInstanceForPdf(List<String> pageTexts) {
        BottomSheetResultsFragment fragment = new BottomSheetResultsFragment();
        Bundle args = new Bundle();
        args.putStringArrayList(ARG_TEXT_LIST, new ArrayList<>(pageTexts));
        fragment.setArguments(args);
        // Your addition
        Log.d(TAG, "newInstanceForPdf (multi-page text) called with " + pageTexts.size() + " pages.");
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            singleText = getArguments().getString(ARG_TEXT_SINGLE);
            pageTexts = getArguments().getStringArrayList(ARG_TEXT_LIST);
            // Your addition
            Log.d(TAG, "onCreate: Arguments retrieved. Single text present: " + (singleText != null) + ", Page texts list size: " + (pageTexts != null ? pageTexts.size() : "0"));
        } else {
            // Your addition
            Log.w(TAG, "onCreate: No arguments received for BottomSheetResultsFragment.");
        }
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof OnPageSelectedListener) {
            pageSelectedListener = (OnPageSelectedListener) context;
        } else {
            // Friend's addition for stricter checking
            throw new RuntimeException(context.toString() + " must implement OnPageSelectedListener");
        }
    }


    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottom_sheet_dialog_results, container, false);
        // Your addition
        Log.d(TAG, "onCreateView: Layout inflated.");

        containerLayout = view.findViewById(R.id.text_content_container);
        // Your change: Assign to class member
        scrollView = view.findViewById(R.id.scroll_view_results);

        if (scrollView != null) {
            scrollView.setContentDescription(getString(R.string.ocr_results_scroll_view_desc));
            scrollView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            // Your addition: Explicitly enable nested scrolling for the ScrollView
            ViewCompat.setNestedScrollingEnabled(scrollView, true);
            // Your addition
            Log.d(TAG, "ScrollView found and nested scrolling enabled.");
        } else {
            // Your addition
            Log.e(TAG, "ScrollView with ID 'scroll_view_results' not found in layout!");
        }

        MaterialButton btnCopy = view.findViewById(R.id.btn_copy);
        MaterialButton btnShare = view.findViewById(R.id.btn_share);

        final String textForButtons;

        if (pageTexts != null && !pageTexts.isEmpty()) {
            // Your addition
            Log.d(TAG, "Displaying PDF results with " + pageTexts.size() + " pages.");
            StringBuilder fullTextForActions = new StringBuilder();

            for (int i = 0; i < pageTexts.size(); i++) {
                final String currentPageText = pageTexts.get(i);
                final int pageIndex = i; // Friend's fix: make i final for lambda

                LinearLayout pageViewContainer = new LinearLayout(getContext());
                pageViewContainer.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                // FIX: Changed R.d.page_bottom_margin to R.dimen.page_bottom_margin
                containerParams.setMargins(0, 0, 0, (int) getResources().getDimension(R.dimen.page_bottom_margin));
                pageViewContainer.setLayoutParams(containerParams);
                pageViewContainer.setBackgroundResource(R.drawable.page_background);
                pageViewContainer.setPadding(
                        // FIX: Changed R.d.page_padding to R.dimen.page_padding
                        (int) getResources().getDimension(R.dimen.page_padding),
                        (int) getResources().getDimension(R.dimen.page_padding),
                        (int) getResources().getDimension(R.dimen.page_padding),
                        (int) getResources().getDimension(R.dimen.page_padding)
                );
                pageViewContainer.setFocusable(true);
                pageViewContainer.setFocusableInTouchMode(true);
                pageViewContainer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES); // Your setting
                // Combined content description, prioritizing friend's simpler version but keeping your ability to include full text if needed in resource string
                pageViewContainer.setContentDescription(getString(R.string.pdf_page_content_description, pageIndex + 1));

                // Friend's addition: Accessibility click support for page selection
                pageViewContainer.setOnClickListener(v -> {
                    if (pageSelectedListener != null) {
                        pageSelectedListener.onPageSelected(pageIndex, currentPageText);
                    }
                });

                TextView pageLabel = new TextView(getContext());
                // Use pageIndex from friend's fix
                pageLabel.setText(getString(R.string.pdf_page_label, pageIndex + 1));
                pageLabel.setTextAppearance(getContext(), com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle1);
                // FIX: Changed R.d.text_margin_small to R.dimen.text_margin_small
                pageLabel.setPadding(0, 0, 0, (int) getResources().getDimension(R.dimen.text_margin_small));
                pageLabel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                pageViewContainer.addView(pageLabel);

                TextView pageContent = new TextView(getContext());
                pageContent.setText(Html.fromHtml(currentPageText));
                pageContent.setMovementMethod(LinkMovementMethod.getInstance());
                pageContent.setTextIsSelectable(true);
                // Your setting for accessibility
                pageContent.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                pageViewContainer.addView(pageContent);

                containerLayout.addView(pageViewContainer);

                fullTextForActions.append("--- Page ").append(pageIndex + 1).append(" ---\n")
                        .append(currentPageText).append("\n\n");
            }

            textForButtons = fullTextForActions.toString().trim();
        } else if (singleText != null && !singleText.isEmpty()) {
            // Your addition
            Log.d(TAG, "Displaying single image OCR result.");
            TextView ocrResultTextView = new TextView(getContext());

            // Friend's improvement: Clean text for accessibility and button actions
            String cleanText = Html.fromHtml(singleText).toString().trim();
            ocrResultTextView.setText(cleanText);
            ocrResultTextView.setTextIsSelectable(true);
            ocrResultTextView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            // Friend's improvement for content description
            ocrResultTextView.setContentDescription(cleanText);

            // Friend's additions for single text handling in containerLayout
            containerLayout.removeAllViews();
            containerLayout.addView(ocrResultTextView);
            containerLayout.setContentDescription(cleanText);

            // Friend's addition for accessibility announcement on click
            containerLayout.setOnClickListener(v -> {
                // FIX: Changed ACCBILITY_SERVICE to ACCESSIBILITY_SERVICE
                AccessibilityManager am = (AccessibilityManager) requireContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
                if (am != null && am.isEnabled()) {
                    AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
                    event.getText().add(cleanText);
                    event.setClassName(getClass().getName());
                    event.setPackageName(requireContext().getPackageName());
                    am.sendAccessibilityEvent(event);
                }
            });

            textForButtons = cleanText; // Use cleanText from friend's logic
        } else {
            // Your addition
            Log.w(TAG, "No text data found for display in bottom sheet (neither single text nor page list).");
            TextView noResultTextView = new TextView(getContext());
            noResultTextView.setText(R.string.no_text_found);
            noResultTextView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            containerLayout.addView(noResultTextView);
            // Friend's addition
            containerLayout.setOnClickListener(null); // Clear listener if no text
            textForButtons = "";
        }

        setupButtons(textForButtons, btnCopy, btnShare);
        return view;
    }

    private void setupButtons(String resultantTextString, MaterialButton btnCopy, MaterialButton btnShare) {
        // Setup the Copy Button
        btnCopy.setOnClickListener(view -> {
            // Combined null/empty check
            if (resultantTextString != null && !resultantTextString.isEmpty()) {
                ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Copied Text", resultantTextString);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(getContext(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
                // Your addition
                Log.d(TAG, "Text copied to clipboard.");
            } else {
                Toast.makeText(getContext(), R.string.no_text_to_copy, Toast.LENGTH_SHORT).show();
                // Your addition
                Log.d(TAG, "Attempted to copy, but no text available.");
            }
        });

        // Setup the Share Button
        btnShare.setOnClickListener(view -> {
            // Combined null/empty check
            if (resultantTextString != null && !resultantTextString.isEmpty()) {
                Intent shareIntent = new Intent();
                shareIntent.setAction(Intent.ACTION_SEND);
                shareIntent.putExtra(Intent.EXTRA_TEXT, resultantTextString);
                shareIntent.setType("text/plain");
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share_text_via)));
                // Your addition
                Log.d(TAG, "Share intent launched.");
            } else {
                Toast.makeText(getContext(), R.string.no_text_to_share, Toast.LENGTH_SHORT).show();
                // Your addition
                Log.d(TAG, "Attempted to share, but no text available.");
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        // Your addition
        Log.d(TAG, "onStart: Setting BottomSheet behavior to expanded.");
        Dialog dialog = getDialog();
        if (dialog != null) {
            View bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                // Your comprehensive layout params and behavior setup
                ViewGroup.LayoutParams layoutParams = bottomSheet.getLayoutParams();
                layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
                bottomSheet.setLayoutParams(layoutParams);

                // Assign to class member as in your code
                bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet);
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);

                // Your addition: Add a callback to log state changes (for debugging)
                bottomSheetBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
                    @Override
                    public void onStateChanged(@NonNull View bottomSheet, int newState) {
                        String stateName;
                        switch (newState) {
                            case BottomSheetBehavior.STATE_COLLAPSED:
                                stateName = "COLLAPSED";
                                break;
                            case BottomSheetBehavior.STATE_EXPANDED:
                                stateName = "EXPANDED";
                                break;
                            case BottomSheetBehavior.STATE_DRAGGING:
                                stateName = "DRAGGING";
                                break;
                            case BottomSheetBehavior.STATE_SETTLING:
                                stateName = "SETTLING";
                                break;
                            case BottomSheetBehavior.STATE_HIDDEN:
                                stateName = "HIDDEN";
                                break;
                            case BottomSheetBehavior.STATE_HALF_EXPANDED:
                                stateName = "HALF_EXPANDED";
                                break;
                            default:
                                stateName = "UNKNOWN";
                                break;
                        }
                        Log.d(TAG, "BottomSheet State Changed: " + stateName);
                    }

                    @Override
                    public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                        // Log.d(TAG, "BottomSheet Sliding: " + slideOffset);
                    }
                });
            }
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // This section was heavily conflicted. Merging to combine the accessibility focus logic
        // and ensure it's robust. Your `contentToFocus` assignment logic is more robust.
        if (containerLayout != null) {
            final View contentToFocus;

            if (pageTexts != null && !pageTexts.isEmpty()) {
                contentToFocus = containerLayout.getChildAt(0);
            } else if (singleText != null && !singleText.isEmpty()) {
                contentToFocus = containerLayout.getChildAt(0);
            } else {
                contentToFocus = null;
            }

            if (contentToFocus != null) {
                contentToFocus.post(() -> {
                    // Both branches had this accessibility event. Keep it.
                    contentToFocus.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
                });
            }
        }
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        // Your addition
        Log.d(TAG, "onCancel: BottomSheet dismissed by user.");
    }

    @Override
    public void dismiss() {
        super.dismiss();
        // Your addition
        Log.d(TAG, "dismiss: BottomSheet dismissed programmatically.");
    }
}