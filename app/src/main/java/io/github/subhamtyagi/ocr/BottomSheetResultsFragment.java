// BottomSheetResultsFragment.java
package io.github.subhamtyagi.ocr;

import android.app.Dialog;
import com.google.android.material.bottomsheet.BottomSheetBehavior;

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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

public class BottomSheetResultsFragment extends BottomSheetDialogFragment {

    private static final String ARG_TEXT_SINGLE = "arg_text_single";
    private static final String ARG_TEXT_LIST = "arg_text_list";
    private static final String TAG = "BottomSheetResultsFrag";

    private String singleText; // Holds single image text OR concatenated PDF text
    private List<String> pageTexts; // Holds individual page texts for PDF display
    private LinearLayout containerLayout; // The LinearLayout inside the ScrollView to hold page views

    private OnPageSelectedListener pageSelectedListener;


    public BottomSheetResultsFragment() {
        // Required empty public constructor
    }

    public interface OnPageSelectedListener {
        void onPageSelected(int pageIndex, String pageContent);
    }

    public static BottomSheetResultsFragment newInstance(String text) {
        BottomSheetResultsFragment fragment = new BottomSheetResultsFragment();
        Bundle args = new Bundle();
        args.putString(ARG_TEXT_SINGLE, text);
        fragment.setArguments(args);
        Log.d(TAG, "newInstance (single text) called.");
        return fragment;
    }

    public static BottomSheetResultsFragment newInstanceForPdf(List<String> pageTexts) {
        BottomSheetResultsFragment fragment = new BottomSheetResultsFragment();
        Bundle args = new Bundle();
        args.putStringArrayList(ARG_TEXT_LIST, new ArrayList<>(pageTexts));
        fragment.setArguments(args);
        Log.d(TAG, "newInstanceForPdf (multi-page text) called with " + pageTexts.size() + " pages.");
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            singleText = getArguments().getString(ARG_TEXT_SINGLE);
            pageTexts = getArguments().getStringArrayList(ARG_TEXT_LIST);
            Log.d(TAG, "onCreate: Arguments retrieved. Single text present: " + (singleText != null) + ", Page texts list size: " + (pageTexts != null ? pageTexts.size() : "0"));
        } else {
            Log.w(TAG, "onCreate: No arguments received for BottomSheetResultsFragment.");
        }
    }
    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof OnPageSelectedListener) {
            pageSelectedListener = (OnPageSelectedListener) context;
        }
    }


    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottom_sheet_dialog_results, container, false);
        Log.d(TAG, "onCreateView: Layout inflated.");

        containerLayout = view.findViewById(R.id.text_content_container);
        ScrollView scrollView = view.findViewById(R.id.scroll_view_results);

        if (scrollView != null) {
            scrollView.setContentDescription(getString(R.string.ocr_results_scroll_view_desc));
            scrollView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        } else {
            Log.e(TAG, "ScrollView with ID 'scroll_view_results' not found in layout!");
        }

        MaterialButton btnCopy = view.findViewById(R.id.btn_copy);
        MaterialButton btnShare = view.findViewById(R.id.btn_share);

        final String textForButtons;

        if (pageTexts != null && !pageTexts.isEmpty()) {
            Log.d(TAG, "Displaying PDF results with " + pageTexts.size() + " pages.");
            StringBuilder fullTextForActions = new StringBuilder();

            for (int i = 0; i < pageTexts.size(); i++) {
                final String currentPageText = pageTexts.get(i);

                LinearLayout pageViewContainer = new LinearLayout(getContext());
                pageViewContainer.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                containerParams.setMargins(0, 0, 0, (int) getResources().getDimension(R.dimen.page_bottom_margin));
                pageViewContainer.setLayoutParams(containerParams);
                pageViewContainer.setBackgroundResource(R.drawable.page_background);
                pageViewContainer.setPadding(
                        (int) getResources().getDimension(R.dimen.page_padding),
                        (int) getResources().getDimension(R.dimen.page_padding),
                        (int) getResources().getDimension(R.dimen.page_padding),
                        (int) getResources().getDimension(R.dimen.page_padding)
                );
                pageViewContainer.setFocusable(true);
                pageViewContainer.setFocusableInTouchMode(true);
                pageViewContainer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                pageViewContainer.setContentDescription(getString(R.string.pdf_page_full_readout_description, i + 1, Html.fromHtml(currentPageText)));

                TextView pageLabel = new TextView(getContext());
                pageLabel.setText(getString(R.string.pdf_page_label, i + 1));
                pageLabel.setTextAppearance(getContext(), com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle1);
                pageLabel.setPadding(0, 0, 0, (int) getResources().getDimension(R.dimen.text_margin_small));
                pageLabel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                pageViewContainer.addView(pageLabel);

                TextView pageContent = new TextView(getContext());
                pageContent.setText(Html.fromHtml(currentPageText));
                pageContent.setMovementMethod(LinkMovementMethod.getInstance());
                pageContent.setTextIsSelectable(true);
                pageContent.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                pageViewContainer.addView(pageContent);

                containerLayout.addView(pageViewContainer);

                fullTextForActions.append("--- Page ").append(i + 1).append(" ---\n")
                        .append(currentPageText).append("\n\n");
            }

            textForButtons = fullTextForActions.toString().trim();
        } else if (singleText != null && !singleText.isEmpty()) {
            Log.d(TAG, "Displaying single image OCR result.");
            TextView ocrResultTextView = new TextView(getContext());
            ocrResultTextView.setText(Html.fromHtml(singleText));
            ocrResultTextView.setMovementMethod(LinkMovementMethod.getInstance());
            ocrResultTextView.setTextIsSelectable(true);
            ocrResultTextView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            ocrResultTextView.setContentDescription(getString(R.string.ocr_result_content_description_full, Html.fromHtml(singleText).toString()));
            containerLayout.addView(ocrResultTextView);

            textForButtons = singleText;
        } else {
            Log.w(TAG, "No text data found for display in bottom sheet (neither single text nor page list).");
            TextView noResultTextView = new TextView(getContext());
            noResultTextView.setText(R.string.no_text_found);
            noResultTextView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            containerLayout.addView(noResultTextView);

            textForButtons = "";
        }

        setupButtons(textForButtons, btnCopy, btnShare);
        return view;
    }

    private void setupButtons(String resultantTextString, MaterialButton btnCopy, MaterialButton btnShare) {
        // Setup the Copy Button
        btnCopy.setOnClickListener(view -> {
            if (resultantTextString != null && !resultantTextString.isEmpty()) {
                ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Copied Text", resultantTextString);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(getContext(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Text copied to clipboard.");
            } else {
                Toast.makeText(getContext(), R.string.no_text_to_copy, Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Attempted to copy, but no text available.");
            }
        });

        // Setup the Share Button
        btnShare.setOnClickListener(view -> {
            if (resultantTextString != null && !resultantTextString.isEmpty()) {
                Intent shareIntent = new Intent();
                shareIntent.setAction(Intent.ACTION_SEND);
                shareIntent.putExtra(Intent.EXTRA_TEXT, resultantTextString);
                shareIntent.setType("text/plain");
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share_text_via)));
                Log.d(TAG, "Share intent launched.");
            } else {
                Toast.makeText(getContext(), R.string.no_text_to_share, Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Attempted to share, but no text available.");
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        Log.d(TAG, "onStart: Setting BottomSheet behavior to expanded.");
        Dialog dialog = getDialog();
        if (dialog != null) {
            View bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                ViewGroup.LayoutParams layoutParams = bottomSheet.getLayoutParams();
                layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
                bottomSheet.setLayoutParams(layoutParams);

                BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(bottomSheet);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (containerLayout != null) {
            // --- FIX START ---
            // Declare the local variable as final and assign it immediately
            // based on the conditions. This ensures it's effectively final.
            final View contentToFocus;

            if (pageTexts != null && !pageTexts.isEmpty()) {
                contentToFocus = containerLayout.getChildAt(0);
            } else if (singleText != null && !singleText.isEmpty()) {
                contentToFocus = containerLayout.getChildAt(0);
            } else {
                // Ensure contentToFocus is always assigned a value, even if null.
                contentToFocus = null;
            }

            if (contentToFocus != null) {
                contentToFocus.post(() -> {
                    contentToFocus.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
                });
            }
            // --- FIX END ---
        }
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        Log.d(TAG, "onCancel: BottomSheet dismissed by user.");
    }

    @Override
    public void dismiss() {
        super.dismiss();
        Log.d(TAG, "dismiss: BottomSheet dismissed programmatically.");
    }
}