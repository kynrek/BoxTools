package local.boxtools;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/** Launcher icon "Car Audio Awake": switches the keep-awake (see CarAudioKeepAwake) on or off. */
public class CarAudioActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean on = !CarAudioKeepAwake.isEnabled(this);
        CarAudioKeepAwake.setEnabled(this, on);
        Toast.makeText(getApplicationContext(), on
                ? "Car audio keep-awake on: navigation prompts start without a gap"
                : "Car audio keep-awake off", Toast.LENGTH_LONG).show();
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 3500);
    }
}
