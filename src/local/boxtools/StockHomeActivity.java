package local.boxtools;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** Opens the box's original home screen, whichever launcher is the default. */
public class StockHomeActivity extends Activity {
    private static final ComponentName STOCK_HOME =
            new ComponentName("com.android.launcher3", "com.android.launcher3.uioverrides.QuickstepLauncher");

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent home = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setComponent(STOCK_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(home);
        } catch (RuntimeException e) {
            Toast.makeText(getApplicationContext(), "Stock home not found: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
