package com.example.screenrec;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Toast;

/** Invisible activity: asks for audio permission if needed, shows Android's "start recording?"
 *  prompt, then starts the service. Also stops a recording that is already running. */
public class CaptureActivity extends Activity {

    private int audio;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (RecorderService.recording) {
            startService(new Intent(this, RecorderService.class)
                    .setAction(RecorderService.ACTION_STOP));
            finish();
            return;
        }
        audio = RecorderService.savedAudio(this);
        if (savedInstanceState != null) return;
        if (audio != 0 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2);
        } else {
            askCapture();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            audio = 0;
            Toast.makeText(this, "Audio permission denied, recording without sound",
                    Toast.LENGTH_LONG).show();
        }
        askCapture();
    }

    private void askCapture() {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), 1);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK && data != null) {
            Intent i = new Intent(this, RecorderService.class)
                    .setAction(RecorderService.ACTION_START)
                    .putExtra(RecorderService.EXTRA_CODE, resultCode)
                    .putExtra(RecorderService.EXTRA_DATA, data)
                    .putExtra(RecorderService.EXTRA_AUDIO, audio);
            startForegroundService(i);
        }
        finish();
    }
}
