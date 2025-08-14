package com.zendalona.zTextGrab;

import android.net.Uri; // Import Uri

// This is now a top-level interface
public interface OnItemClickListener {
    void onItemClick(Uri uri); // Changed to Uri
}
