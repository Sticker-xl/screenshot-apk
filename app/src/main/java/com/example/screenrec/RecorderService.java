package com.example.screenrec;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.service.quicksettings.TileService;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RecorderService extends Service {

    static final String ACTION_START = "com.example.screenrec.START";
    static final String ACTION_STOP = "com.example.screenrec.STOP";
    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";
    static final String EXTRA_AUDIO = "audio";
    static volatile boolean recording = false;

    private static final String CHANNEL = "recording";

    private MediaProjection projection;
    private Recorder recorder;
    private ParcelFileDescriptor pfd;
    private Uri uri;
    private String lastError;

    /** Saved audio choice: 0 none, 1 mic, 2 internal, 3 mic + internal. */
    static int savedAudio(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("audio", 0);
    }

    static void saveAudio(Context c, int mode) {
        c.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit().putInt("audio", mode).apply();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            if (recording) {
                finishRecording();
            } else {
                stopSelf();
            }
            return START_NOT_STICKY;
        }

        int audio = intent == null ? 0 : intent.getIntExtra(EXTRA_AUDIO, 0);
        if (audio != 0 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            audio = 0;
        }
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        if (Build.VERSION.SDK_INT >= 30
                && (audio == Recorder.AUDIO_MIC || audio == Recorder.AUDIO_BOTH)) {
            type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        // Must go foreground right away after startForegroundService().
        startForeground(1, buildNotification(), type);
        if (recording) return START_NOT_STICKY;

        try {
            begin(intent, audio);
        } catch (Exception e) {
            String why = String.valueOf(e.getMessage());
            release(false);
            toast("Couldn't start recording: " + why);
        }
        return START_NOT_STICKY;
    }

    private void begin(Intent intent, int audio) throws Exception {
        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data;
        if (Build.VERSION.SDK_INT >= 33) {
            data = intent.getParcelableExtra(EXTRA_DATA, Intent.class);
        } else {
            data = intent.getParcelableExtra(EXTRA_DATA);
        }

        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(code, data);
        // Android 14+ requires a callback to be registered before the display is created.
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                finishRecording();
            }
        }, new Handler(Looper.getMainLooper()));

        int w;
        int h;
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getMaximumWindowMetrics().getBounds();
            w = b.width();
            h = b.height();
        } else {
            DisplayMetrics real = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(real);
            w = real.widthPixels;
            h = real.heightPixels;
        }
        int dpi = getResources().getConfiguration().densityDpi;

        // Output file in the gallery: Movies/ScreenRecorder
        String name = "Screen_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        cv.put(MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/ScreenRecorder");
        cv.put(MediaStore.Video.Media.IS_PENDING, 1);
        uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
        pfd = getContentResolver().openFileDescriptor(uri, "rw");

        recorder = new Recorder(projection, audio);
        recorder.start(pfd.getFileDescriptor(), w, h, dpi);
        recording = true;
        refreshTile();
    }

    private void finishRecording() {
        if (!recording) return;
        boolean saved = release(true);
        if (saved) {
            toast("Saved to Movies/ScreenRecorder");
        } else {
            toast("Nothing saved" + (lastError != null ? " (" + lastError + ")" : " (too short?)"));
        }
    }

    /** Stops everything. Returns true if the video was kept. */
    private boolean release(boolean keep) {
        recording = false;
        lastError = null;
        if (recorder != null) {
            if (!recorder.stop()) keep = false;
            lastError = recorder.error;
            recorder = null;
        }
        if (projection != null) {
            MediaProjection p = projection;
            projection = null;
            p.stop();
        }
        try {
            if (pfd != null) pfd.close();
        } catch (Exception ignored) {
        }
        pfd = null;
        if (uri != null) {
            if (keep) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(uri, cv, null, null);
            } else {
                getContentResolver().delete(uri, null, null);
            }
            uri = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        refreshTile();
        return keep;
    }

    @Override
    public void onDestroy() {
        if (recording) release(true);
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Screen recording", NotificationManager.IMPORTANCE_LOW));

        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, RecorderService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_record), "Stop", stop).build();

        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_record)
                .setContentTitle("Recording screen")
                .setContentText("Tap Stop when you're done")
                .setOngoing(true)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .addAction(stopAction)
                .build();
    }

    private void refreshTile() {
        TileService.requestListeningState(this, new ComponentName(this, RecordTileService.class));
    }

    private void toast(String msg) {
        Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
    }
}
