package local.boxtools;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

/**
 * Keeps the box's audio outputs to the car awake by playing inaudible noise (about -90 dB) into both
 * speaker outputs: the primary/fast one (Waze's voice) and the deep-buffer one (music apps).
 *
 * Android puts an output in standby after 3 s of silence; waking it, and the car picking the audio
 * back up, cut off the first half second of each Waze prompt. With the outputs always running the
 * prompt starts at once. It holds no audio focus and no media session, so nothing shows as playing
 * and other apps' audio is untouched. On by default; the "Car Audio Awake" icon switches it.
 */
final class CarAudioKeepAwake {
    private static final String TAG = "BoxTools";
    private static final String PREFS = "boxtools", KEY = "keep_car_audio_awake";
    private static final int RATE = 48000;

    private static Thread thread;
    private static volatile boolean running;

    private CarAudioKeepAwake() {
    }

    static boolean isEnabled(Context c) {
        return prefs(c).getBoolean(KEY, true);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY, on).apply();
        if (on) start(); else stop();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void startIfEnabled(Context c) {
        if (isEnabled(c)) start();
    }

    static synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(CarAudioKeepAwake::loop, "CarAudioKeepAwake");
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
        Log.i(TAG, "car audio keep-awake on");
    }

    static synchronized void stop() {
        if (!running) return;
        running = false;
        if (thread != null) thread.interrupt();
        thread = null;
        Log.i(TAG, "car audio keep-awake off");
    }

    private static AudioTrack track(int usage, boolean lowLatency) {
        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build();
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack.Builder b = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                        .build())
                .setAudioFormat(format)
                .setBufferSizeInBytes(Math.max(min, RATE))   // ~1/4 s
                .setTransferMode(AudioTrack.MODE_STREAM);
        if (lowLatency) b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
        return b.build();
    }

    private static void loop() {
        AudioTrack media = null, fast = null;
        try {
            media = track(AudioAttributes.USAGE_MEDIA, false);                  // deep-buffer output
            fast = track(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, true);  // primary/fast output
            media.play();
            fast.play();
            short[] noise = new short[RATE / 10 * 2];   // 100 ms of stereo
            long seed = 0x2545F4914F6CDD1DL;
            while (running && !Thread.currentThread().isInterrupted()) {
                for (int i = 0; i < noise.length; i++) {
                    // +-1 LSB dither: inaudible, but not the pure digital silence some chains detect and drop.
                    seed ^= seed << 13; seed ^= seed >>> 7; seed ^= seed << 17;
                    noise[i] = (short) ((seed & 1) == 0 ? 1 : -1);
                }
                media.write(noise, 0, noise.length);              // blocking: paces the loop
                fast.write(noise, 0, noise.length, AudioTrack.WRITE_NON_BLOCKING);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "car audio keep-awake failed", e);
            running = false;
        } finally {
            for (AudioTrack t : new AudioTrack[]{media, fast}) {
                if (t == null) continue;
                try { t.stop(); } catch (RuntimeException ignored) { }
                t.release();
            }
        }
    }
}
