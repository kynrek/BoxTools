package com.android.launcher3.uioverrides;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Anything that opens the vendor launcher by name goes to the real home screen (MiniMap) instead. */
public class QuickstepLauncher extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
        }
        finish();
    }
}
