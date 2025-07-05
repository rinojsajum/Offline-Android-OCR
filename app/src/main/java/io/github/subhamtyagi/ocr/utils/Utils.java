package io.github.subhamtyagi.ocr.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.preference.PreferenceManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A class that contains all the utility functions
 */
public class Utils {
    /**
     * get the training data path for Tesseract
     *
     * @param context The context of the application
     * @return The path to the training data
     */
    public static String getTessDataPath(Context context) {
        return context.getExternalFilesDir(null) + File.separator;
    }

    /**
     * Get the Image from the given path
     *
     * @param path The path to the image
     * @return The image as a Bitmap
     */
    public static Bitmap getImage(String path) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 4;
        return BitmapFactory.decodeFile(path, options);
    }

    /**
     * Check if the file exists
     *
     * @param path The path to the file
     * @return True if the file exists, false otherwise
     */
    public static boolean isFileExist(String path) {
        File file = new File(path);
        return file.exists();
    }

    /**
     * create a directory
     *
     * @param path The path to the directory
     * @return True if the directory was created, false otherwise
     */
    public static boolean createDir(String path) {
        File file = new File(path);
        return !file.exists() && file.mkdirs();
    }

    /**
     * pre-process the image
     *
     * @param bitmap The image to pre-process
     * @return The pre-processed image
     */
    public static Bitmap preProcessBitmap(Bitmap bitmap) {
        //un-sharp masking
        //OTSU binarization
        //skew correction
        //noise removal
        //thinning or skeletonization
        return toGrayscale(bitmap);
    }

    /**
     * Convert the image to grayscale
     *
     * @param bmpOriginal The image to convert
     * @return The grayscale image
     */
    private static Bitmap toGrayscale(Bitmap bmpOriginal) {
        int width, height;
        height = bmpOriginal.getHeight();
        width = bmpOriginal.getWidth();

        Bitmap bmpGrayscale = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmpGrayscale);
        Paint paint = new Paint();
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0);
        ColorMatrixColorFilter f = new ColorMatrixColorFilter(cm);
        paint.setColorFilter(f);
        c.drawBitmap(bmpOriginal, 0, 0, paint);
        return bmpGrayscale;
    }

    public static Bitmap loadBitmap(String path, int reqWidth, int reqHeight) {
        try {
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(new FileInputStream(path), null, options);
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeStream(new FileInputStream(path), null, options);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) > reqHeight && (halfWidth / inSampleSize) > reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    public static boolean isPreProcessImage() {
        return SpUtil.getInstance().getBoolean(Constants.KEY_GRAYSCALE_IMAGE_OCR, false) ||
                SpUtil.getInstance().getBoolean(Constants.KEY_UN_SHARP_MASKING, false) ||
                SpUtil.getInstance().getBoolean(Constants.KEY_OTSU_THRESHOLD, false) ||
                SpUtil.getInstance().getBoolean(Constants.KEY_FIND_SKEW_AND_DESKEW, false);
    }

    public static boolean isPersistData() {
        // FIX: Changed default value from 'false' to 'true'.
        // This makes the "Recent" button visible from the very first launch.
        return SpUtil.getInstance().getBoolean(Constants.KEY_PERSIST_DATA, true);
    }

    public static void putLastUsedText(String text) {
        SpUtil.getInstance().putString(Constants.KEY_LAST_USE_IMAGE_TEXT, text);
    }

    public static String getLastUsedText() {
        return SpUtil.getInstance().getString(Constants.KEY_LAST_USE_IMAGE_TEXT);
    }

    public static Set<Language> getTrainingDataLanguages(Context context) {
        if (context == null) {
            return new HashSet<>();
        }
        final String PREFERENCE_KEY = "key_ocr_language_preference";
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        Set<String> languageCodes = sharedPreferences.getStringSet(PREFERENCE_KEY, new HashSet<>());
        Set<Language> languages = new HashSet<>();
        if (languageCodes != null) {
            // Add a default language if none are selected, to prevent crashes
            if (languageCodes.isEmpty()) {
                languages.add(new Language(context, "eng"));
            } else {
                for (String code : languageCodes) {
                    languages.add(new Language(context, code));
                }
            }
        }
        return languages;
    }

    public static void setTrainingDataLanguages(Context context, Set<Language> languages) {
        if (context == null || languages == null) {
            return;
        }
        final String PREFERENCE_KEY = "key_ocr_language_preference";
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        Set<String> languageCodes = languages.stream().map(Language::getCode).collect(Collectors.toSet());
        sharedPreferences.edit().putStringSet(PREFERENCE_KEY, languageCodes).apply();
    }

    public static String getTrainingDataType() {
        return SpUtil.getInstance().getString(Constants.KEY_TESS_TRAINING_DATA_SOURCE, "best");
    }

    public static boolean isNetworkAvailable(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return false;
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    public static String getSize(int size) {
        String s = "";
        double kb = (double) size / 1024;
        double mb = kb / 1024;
        double gb = mb / 1024;
        double tb = gb / 1024;
        if (size < 1024) {
            s = size + " Bytes";
        } else if (size >= 1024 && size < (1024 * 1024)) {
            s = String.format(java.util.Locale.US, "%.2f", kb) + " KB";
        } else if (size >= (1024 * 1024) && size < (1024 * 1024 * 1024)) {
            s = String.format(java.util.Locale.US, "%.2f", mb) + " MB";
        } else if (size >= (1024 * 1024 * 1024) && size < (1024 * 1024 * 1024L * 1024L)) {
            s = String.format(java.util.Locale.US, "%.2f", gb) + " GB";
        } else if (size >= (1024 * 1024 * 1024L * 1024L)) {
            s = String.format(java.util.Locale.US, "%.2f", tb) + " TB";
        }
        return s;
    }

    public static int getPageSegMode() {
        String psm = SpUtil.getInstance().getString(Constants.KEY_PAGE_SEG_MODE, "3");
        return Integer.parseInt(psm);
    }

    public static boolean isExtraParameterSet(){
        return SpUtil.getInstance().getBoolean(Constants.KEY_ADVANCE_TESS_OPTION,false);
    }

    /**
     * Returns the map of advanced parameters for Tesseract.
     */
    public static Map<String, String> getAllParameters() {
        Map<String, String> parameters = new HashMap<>();

        if (isExtraParameterSet()) {
            // In a more complete version, you would read each parameter from SharedPreferences here.
        }

        return parameters;
    }
}