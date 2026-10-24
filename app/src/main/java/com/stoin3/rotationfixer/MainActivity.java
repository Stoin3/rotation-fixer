package com.stoin3.rotationfixer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Settings screen: grants permissions and turns the rotate button service on or off. */
public class MainActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 1;
    private static final int[] POSITION_LABELS = {
            R.string.pos_bottom_right, R.string.pos_bottom_left,
            R.string.pos_top_right, R.string.pos_top_left,
    };

    private PermissionRow overlayRow;
    private PermissionRow writeSettingsRow;
    private PermissionRow notificationRow;
    private Switch enableSwitch;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final SharedPreferences prefs = Prefs.get(this);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        content.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        content.addView(title);

        TextView intro = new TextView(this);
        intro.setText(R.string.intro);
        intro.setPadding(0, dp(8), 0, dp(16));
        content.addView(intro);

        overlayRow = new PermissionRow(content, R.string.perm_overlay, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            }
        });
        writeSettingsRow = new PermissionRow(content, R.string.perm_write_settings,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openSettings(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                    }
                });
        if (Prefs.needsNotificationPermission()) {
            notificationRow = new PermissionRow(content, R.string.perm_notifications,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS},
                                    REQUEST_NOTIFICATIONS);
                        }
                    });
        }

        enableSwitch = new Switch(this);
        enableSwitch.setText(R.string.enable_service);
        enableSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        enableSwitch.setPadding(0, dp(16), 0, dp(8));
        enableSwitch.setChecked(Prefs.isEnabled(this));
        enableSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(Prefs.KEY_ENABLED, isChecked).apply();
                refresh();
            }
        });
        content.addView(enableSwitch);

        content.addView(checkBox(R.string.allow_upside_down, Prefs.KEY_ALLOW_UPSIDE_DOWN,
                Prefs.allowUpsideDown(this)));
        content.addView(checkBox(R.string.auto_hide, Prefs.KEY_AUTO_HIDE, Prefs.autoHide(this)));

        TextView positionLabel = new TextView(this);
        positionLabel.setText(R.string.position);
        positionLabel.setPadding(0, dp(16), 0, 0);
        content.addView(positionLabel);

        RadioGroup positions = new RadioGroup(this);
        for (int i = 0; i < POSITION_LABELS.length; i++) {
            RadioButton radio = new RadioButton(this);
            radio.setId(View.generateViewId());
            radio.setText(POSITION_LABELS[i]);
            radio.setTag(i);
            positions.addView(radio);
            if (i == Prefs.position(this)) {
                radio.setChecked(true);
            }
        }
        positions.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                View radio = group.findViewById(checkedId);
                if (radio != null) {
                    prefs.edit().putInt(Prefs.KEY_POSITION, (Integer) radio.getTag()).apply();
                }
            }
        });
        content.addView(positions);

        statusText = new TextView(this);
        statusText.setPadding(0, dp(16), 0, 0);
        content.addView(statusText);

        final ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        scroll.setClipToPadding(false);
        // Android 15 draws apps edge-to-edge; keep the content clear of the system bars.
        scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                if (Build.VERSION.SDK_INT >= 30) {
                    Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout());
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                } else {
                    v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                            insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                }
                return insets;
            }
        });
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        refresh();
    }

    private void refresh() {
        overlayRow.setGranted(Prefs.canDrawOverlays(this));
        writeSettingsRow.setGranted(Prefs.canWriteSettings(this));
        if (notificationRow != null) {
            notificationRow.setGranted(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED);
        }

        boolean enabled = enableSwitch.isChecked();
        boolean permitted = Prefs.hasRequiredPermissions(this);
        if (enabled && permitted) {
            RotationService.start(this);
            statusText.setText(R.string.status_ready);
        } else {
            RotationService.stop(this);
            statusText.setText(enabled ? R.string.status_missing : R.string.status_off);
        }
    }

    private void openSettings(String action) {
        Intent intent = new Intent(action, Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            startActivity(new Intent(action));
        }
    }

    private CheckBox checkBox(int label, final String key, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setChecked(checked);
        box.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Prefs.get(MainActivity.this).edit().putBoolean(key, isChecked).apply();
            }
        });
        return box;
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    /** A permission name with a "grant" button that turns into a check mark once granted. */
    private final class PermissionRow {
        private final Button button;
        private final TextView granted;

        PermissionRow(LinearLayout parent, int label, View.OnClickListener onGrant) {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(48));

            TextView name = new TextView(MainActivity.this);
            name.setText(label);
            row.addView(name, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            button = new Button(MainActivity.this);
            button.setText(R.string.grant);
            button.setOnClickListener(onGrant);
            row.addView(button);

            granted = new TextView(MainActivity.this);
            granted.setText(R.string.granted);
            row.addView(granted);

            parent.addView(row);
        }

        void setGranted(boolean isGranted) {
            button.setVisibility(isGranted ? View.GONE : View.VISIBLE);
            granted.setVisibility(isGranted ? View.VISIBLE : View.GONE);
        }
    }
}
