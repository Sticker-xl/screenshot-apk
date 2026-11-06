package com.example.screenrec;

import android.Manifest;
import android.app.Activity;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView help = new TextView(this);
        help.setText(R.string.help);
        help.setTextSize(16);
        root.addView(help);

        TextView audioTitle = new TextView(this);
        audioTitle.setText(R.string.audio_title);
        audioTitle.setTextSize(18);
        audioTitle.setPadding(0, pad, 0, pad / 2);
        root.addView(audioTitle);

        String[] modes = getResources().getStringArray(R.array.audio_modes);
        RadioGroup group = new RadioGroup(this);
        for (int i = 0; i < modes.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(i + 1);
            rb.setText(modes[i]);
            group.addView(rb);
        }
        group.check(Math.min(RecorderService.savedAudio(this), modes.length - 1) + 1);
        group.setOnCheckedChangeListener((g, id) -> {
            int mode = id - 1;
            RecorderService.saveAudio(this, mode);
            if (mode != 0 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2);
            }
        });
        root.addView(group);

        if (Build.VERSION.SDK_INT >= 33) {
            Button add = new Button(this);
            add.setText(R.string.add_tile);
            add.setOnClickListener(v -> addTile());
            root.addView(add);
        }

        Button start = new Button(this);
        start.setText(R.string.start);
        start.setOnClickListener(v -> startActivity(new Intent(this, CaptureActivity.class)));
        root.addView(start);

        setContentView(scroll);
    }

    private void addTile() {
        if (Build.VERSION.SDK_INT < 33) return;
        StatusBarManager sbm = getSystemService(StatusBarManager.class);
        sbm.requestAddTileService(
                new ComponentName(this, RecordTileService.class),
                getString(R.string.tile_label),
                Icon.createWithResource(this, R.drawable.ic_record),
                getMainExecutor(),
                result -> { });
    }
}
