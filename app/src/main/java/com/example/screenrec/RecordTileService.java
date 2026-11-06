package com.example.screenrec;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class RecordTileService extends TileService {

    @Override
    public void onStartListening() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean on = RecorderService.recording;
        String[] modes = getResources().getStringArray(R.array.audio_modes);
        int saved = Math.min(RecorderService.savedAudio(this), modes.length - 1);
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(on ? R.string.tile_stop : R.string.tile_label));
        tile.setSubtitle(on ? getString(R.string.recording) : modes[saved]);
        tile.updateTile();
    }

    @Override
    public void onClick() {
        if (RecorderService.recording) {
            startService(new Intent(this, RecorderService.class)
                    .setAction(RecorderService.ACTION_STOP));
            return;
        }
        Intent intent = new Intent(this, CaptureActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE));
        } else {
            startActivityAndCollapse(intent);
        }
    }
}
