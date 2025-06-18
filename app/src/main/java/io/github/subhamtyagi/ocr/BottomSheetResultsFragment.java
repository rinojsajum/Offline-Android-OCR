package io.github.subhamtyagi.ocr;

import android.app.Dialog;
import com.google.android.material.bottomsheet.BottomSheetBehavior;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

public class BottomSheetResultsFragment extends BottomSheetDialogFragment {

    private static final String ARGUMENT_TEXT = "arg_text";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottom_sheet_dialog_results, container, false);
        Bundle arguments = getArguments();

        if (arguments == null) {
            dismiss();
            return view;
        }


        String resultantTextString = arguments.getString(ARGUMENT_TEXT, "");

        TextView resultantText = view.findViewById(R.id.recognized_text_view);

        // CHANGED: The variable type is now MaterialButton instead of ImageButton.
        com.google.android.material.button.MaterialButton btnCopy = view.findViewById(R.id.btn_copy);
        com.google.android.material.button.MaterialButton btnShare = view.findViewById(R.id.btn_share);

        resultantText.setText(resultantTextString);

        setupButtons(resultantTextString, btnCopy, btnShare, resultantText);

        return view;
    }

    private void setupButtons(String resultantTextString, com.google.android.material.button.MaterialButton btnCopy, com.google.android.material.button.MaterialButton btnShare, TextView resultantText) {

        // Setup the Copy Button
        btnCopy.setOnClickListener(view -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("Copied Text", resultantTextString);
            clipboard.setPrimaryClip(clip);
            android.widget.Toast.makeText(getContext(), "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show();
        });

        // Setup the Share Button
        btnShare.setOnClickListener(view -> {
            android.content.Intent shareIntent = new android.content.Intent();
            shareIntent.setAction(android.content.Intent.ACTION_SEND);
            shareIntent.putExtra(android.content.Intent.EXTRA_TEXT, resultantTextString);
            shareIntent.setType("text/plain");
            startActivity(android.content.Intent.createChooser(shareIntent, "Share text via"));
        });
    }

    private void setButtonState(ImageButton button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.3f);
    }


    @Override
    public void onStart() {
        super.onStart();

        Dialog dialog = getDialog();
        if (dialog != null) {
            View bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                // Set the layout parameters to match the parent's height
                bottomSheet.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;

                // Get the BottomSheetBehavior and set its state to expanded
                BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(bottomSheet);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        }
    }


    private void copyToClipboard(String text) {
        ClipboardManager clipboardManager = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clipData = ClipData.newPlainText("nonsense_data", text);
        clipboardManager.setPrimaryClip(clipData);
        Toast.makeText(getContext(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
        dismiss();
    }

    private void shareText(String text) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(intent, null));
        dismiss();
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
    }

    @Override
    public void dismiss() {
        super.dismiss();
    }

    public static BottomSheetResultsFragment newInstance(String text) {
        BottomSheetResultsFragment fragment = new BottomSheetResultsFragment();
        Bundle bundle = new Bundle();
        bundle.putString(ARGUMENT_TEXT, text);
        fragment.setArguments(bundle);
        return fragment;
    }
}
