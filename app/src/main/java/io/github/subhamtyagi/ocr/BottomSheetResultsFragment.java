package io.github.subhamtyagi.ocr;

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
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
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

    private String singleText;
    private List<String> pageTexts;
    private LinearLayout containerLayout;

    public interface OnPageSelectedListener {
        void onPageSelected(int pageIndex, String pageContent);

        void onProgressValues(TessBaseAPI.ProgressValues progressValues);
    }

    private OnPageSelectedListener pageSelectedListener;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof OnPageSelectedListener) {
            pageSelectedListener = (OnPageSelectedListener) context;
        } else {
            throw new RuntimeException(context.toString() + " must implement OnPageSelectedListener");
        }
    }

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
        return fragment;
    }

    public static BottomSheetResultsFragment newInstanceForPdf(List<String> pageTexts) {
        BottomSheetResultsFragment fragment = new BottomSheetResultsFragment();
        Bundle args = new Bundle();
        args.putStringArrayList(ARG_TEXT_LIST, new ArrayList<>(pageTexts));
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            singleText = getArguments().getString(ARG_TEXT_SINGLE);
            pageTexts = getArguments().getStringArrayList(ARG_TEXT_LIST);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottom_sheet_dialog_results, container, false);
        containerLayout = view.findViewById(R.id.text_content_container);
        ScrollView scrollView = view.findViewById(R.id.scroll_view_results);

        if (scrollView != null) {
            scrollView.setContentDescription(getString(R.string.ocr_results_scroll_view_desc));
            scrollView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        MaterialButton btnCopy = view.findViewById(R.id.btn_copy);
        MaterialButton btnShare = view.findViewById(R.id.btn_share);

        final String textForButtons;

        if (pageTexts != null && !pageTexts.isEmpty()) {
            StringBuilder fullTextForActions = new StringBuilder();

            for (int i = 0; i < pageTexts.size(); i++) {
                String currentPageText = pageTexts.get(i);
                final int pageIndex = i; // ✅ Fix: make i final for lambda

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
                pageViewContainer.setContentDescription(getString(R.string.pdf_page_content_description, pageIndex + 1));

                // 🔊 Accessibility click support
                pageViewContainer.setOnClickListener(v -> {
                    if (pageSelectedListener != null) {
                        pageSelectedListener.onPageSelected(pageIndex, currentPageText);
                    }
                });

                TextView pageLabel = new TextView(getContext());
                pageLabel.setText(getString(R.string.pdf_page_label, pageIndex + 1));
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

                fullTextForActions.append("--- Page ").append(pageIndex + 1).append(" ---\n")
                        .append(currentPageText).append("\n\n");
            }


            textForButtons = fullTextForActions.toString().trim();

        } else if (singleText != null && !singleText.isEmpty()) {
            TextView ocrResultTextView = new TextView(getContext());
            String cleanText = Html.fromHtml(singleText).toString().trim();
            ocrResultTextView.setText(cleanText);
            ocrResultTextView.setTextIsSelectable(true);
            ocrResultTextView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            ocrResultTextView.setContentDescription(cleanText);
            containerLayout.removeAllViews();
            containerLayout.addView(ocrResultTextView);
            containerLayout.setContentDescription(cleanText);

            containerLayout.setOnClickListener(v -> {
                AccessibilityManager am = (AccessibilityManager) requireContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
                if (am != null && am.isEnabled()) {
                    AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
                    event.getText().add(cleanText);
                    event.setClassName(getClass().getName());
                    event.setPackageName(requireContext().getPackageName());
                    am.sendAccessibilityEvent(event);
                }
            });

            textForButtons = cleanText;
        } else {
            TextView noResultTextView = new TextView(getContext());
            noResultTextView.setText(R.string.no_text_found);
            noResultTextView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            containerLayout.addView(noResultTextView);
            containerLayout.setOnClickListener(null);
            textForButtons = "";
        }

        setupButtons(textForButtons, btnCopy, btnShare);
        return view;
    }

    private void setupButtons(String text, MaterialButton btnCopy, MaterialButton btnShare) {
        btnCopy.setOnClickListener(v -> {
            if (!text.isEmpty()) {
                ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Copied Text", text);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(getContext(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getContext(), R.string.no_text_to_copy, Toast.LENGTH_SHORT).show();
            }
        });

        btnShare.setOnClickListener(v -> {
            if (!text.isEmpty()) {
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.putExtra(Intent.EXTRA_TEXT, text);
                shareIntent.setType("text/plain");
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share_text_via)));
            } else {
                Toast.makeText(getContext(), R.string.no_text_to_share, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null) {
            View bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
                BottomSheetBehavior.from(bottomSheet).setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (containerLayout != null && containerLayout.getChildCount() > 0) {
            View contentToFocus = containerLayout.getChildAt(0);
            contentToFocus.post(() -> contentToFocus.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED));
        }
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
    }

    @Override
    public void dismiss() {
        super.dismiss();
    }
}