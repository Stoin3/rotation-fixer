package com.stoin3.rotationfixer;

import android.animation.ObjectAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;

/**
 * Foreground service that watches the accelerometer while auto-rotate is off and shows a
 * small rotate button in a screen corner whenever the phone is physically held in a different
 * orientation than the screen. Tapping the button rotates the screen to match, by writing
 * {@link Settings.System#USER_ROTATION} (the same setting the system rotation suggestion uses).
 */
public class RotationService extends Service {
    private static final String TAG = "RotationService";
    private static final String CHANNEL_ID = "service";
    private static final int NOTIFICATION_ID = 1;

    /** A new physical orientation must be held this long before the button appears. */
    private static final long SETTLE_DELAY_MS = 400;
    /** Time to wait after tapping before checking whether the rotation was applied. */
    private static final long APPLY_CHECK_DELAY_MS = 1500;
    private static final long AUTO_HIDE_DELAY_MS = 10_000;
    /** Max distance in degrees from an orientation's centre before we switch to it. */
    private static final int SNAP_TOLERANCE_DEG = 35;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private Display display;
    private DisplayManager displayManager;
    private OrientationEventListener orientationListener;

    private ImageView button;
    private boolean buttonShown;
    private ObjectAnimator hintAnimator;

    private boolean screenOn = true;
    private boolean listening;
    /** Latest orientation suggested by the sensor, or -1 if unknown (e.g. lying flat). */
    private int sensorRotation = -1;
    /** {@link #sensorRotation} once it has been stable for {@link #SETTLE_DELAY_MS}. */
    private int settledRotation = -1;
    /** Rotation that should not be offered again until the phone is turned differently. */
    private int suppressedRotation = -1;
    /** Rotation the button currently offers. */
    private int offeredRotation = -1;
    /** Rotation requested by the last tap that the display has not switched to yet. */
    private int pendingRotation = -1;

    private final Runnable settleRunnable = new Runnable() {
        @Override
        public void run() {
            if (settledRotation != sensorRotation) {
                settledRotation = sensorRotation;
                if (suppressedRotation != settledRotation) {
                    suppressedRotation = -1;
                }
            }
            evaluate();
        }
    };

    private final Runnable autoHideRunnable = new Runnable() {
        @Override
        public void run() {
            suppressedRotation = offeredRotation;
            evaluate();
        }
    };

    private final ContentObserver settingsObserver = new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateListening();
        }
    };

    private final DisplayManager.DisplayListener displayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {}

                @Override
                public void onDisplayRemoved(int displayId) {}

                @Override
                public void onDisplayChanged(int displayId) {
                    if (displayId == Display.DEFAULT_DISPLAY) {
                        evaluate();
                    }
                }
            };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            screenOn = !Intent.ACTION_SCREEN_OFF.equals(intent.getAction());
            updateListening();
        }
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
                    suppressedRotation = -1;
                    evaluate();
                }
            };

    static void start(Context context) {
        Intent intent = new Intent(context, RotationService.class);
        try {
            context.startForegroundService(intent);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not start service", e);
        }
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, RotationService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startInForeground();

        if (!Prefs.canDrawOverlays(this)) {
            stopSelf();
            return;
        }

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        screenOn = powerManager.isInteractive();

        orientationListener = new OrientationEventListener(this, SensorManager.SENSOR_DELAY_UI) {
            @Override
            public void onOrientationChanged(int degrees) {
                onSensorOrientation(degrees);
            }
        };

        ContentResolver resolver = getContentResolver();
        resolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
                false, settingsObserver);
        resolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.USER_ROTATION),
                false, settingsObserver);
        displayManager.registerDisplayListener(displayListener, handler);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, filter);
        Prefs.get(this).registerOnSharedPreferenceChangeListener(prefsListener);

        updateListening();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (orientationListener != null) {
            orientationListener.disable();
            getContentResolver().unregisterContentObserver(settingsObserver);
            displayManager.unregisterDisplayListener(displayListener);
            unregisterReceiver(screenReceiver);
            Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefsListener);
        }
        removeButton();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_MIN);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);

        PendingIntent openApp = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_rotate)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(openApp)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private boolean isAutoRotateOff() {
        return Settings.System.getInt(getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION, 0) == 0;
    }

    /** Only use the accelerometer while it is useful: screen on and rotation locked. */
    private void updateListening() {
        boolean shouldListen = screenOn && isAutoRotateOff() && orientationListener.canDetectOrientation();
        if (shouldListen && !listening) {
            orientationListener.enable();
            listening = true;
        } else if (!shouldListen && listening) {
            orientationListener.disable();
            listening = false;
            handler.removeCallbacks(settleRunnable);
            sensorRotation = -1;
            settledRotation = -1;
            suppressedRotation = -1;
        }
        evaluate();
    }

    private void onSensorOrientation(int degrees) {
        int rotation = degrees == OrientationEventListener.ORIENTATION_UNKNOWN
                ? -1 : degreesToRotation(degrees, sensorRotation);
        if (rotation != sensorRotation) {
            sensorRotation = rotation;
            handler.removeCallbacks(settleRunnable);
            handler.postDelayed(settleRunnable, SETTLE_DELAY_MS);
        }
    }

    /**
     * Maps the device's clockwise tilt in degrees to the {@link Surface} rotation the display
     * would need. Angles between two orientations keep the previous result (hysteresis).
     */
    static int degreesToRotation(int degrees, int previous) {
        for (int rotation = Surface.ROTATION_0; rotation <= Surface.ROTATION_270; rotation++) {
            // Turning the device clockwise by 90 degrees needs ROTATION_270 and vice versa.
            int centre = (360 - rotation * 90) % 360;
            int distance = Math.abs(degrees - centre) % 360;
            distance = Math.min(distance, 360 - distance);
            if (distance <= SNAP_TOLERANCE_DEG) {
                return rotation;
            }
        }
        return previous;
    }

    private void evaluate() {
        if (display == null) {
            return;
        }
        int current = display.getRotation();
        if (pendingRotation != -1) {
            if (current != pendingRotation) {
                hideButton();
                return;
            }
            pendingRotation = -1;
        }
        int target = settledRotation;
        boolean show = listening
                && target != -1
                && target != current
                && target != suppressedRotation
                && (target != Surface.ROTATION_180 || Prefs.allowUpsideDown(this));
        if (show) {
            showButton(target, current);
        } else {
            hideButton();
        }
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    private void showButton(int target, int current) {
        if (button == null && !createButton()) {
            return;
        }
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) button.getLayoutParams();
        int gravity = buttonGravity(target, current);
        if (lp.gravity != gravity) {
            lp.gravity = gravity;
            windowManager.updateViewLayout(button, lp);
        }
        boolean changed = !buttonShown || offeredRotation != target;
        offeredRotation = target;
        if (!changed) {
            return;
        }
        buttonShown = true;
        button.setVisibility(View.VISIBLE);
        button.animate().cancel();
        button.setAlpha(0f);
        button.animate().alpha(1f).setDuration(200).start();

        // Spin the icon in the direction the screen will turn, like the system suggestion does.
        int steps = (target - current + 4) % 4;
        float angle = steps == 1 ? -90f : steps == 3 ? 90f : 180f;
        if (hintAnimator != null) {
            hintAnimator.cancel();
        }
        hintAnimator = ObjectAnimator.ofFloat(button, View.ROTATION, 0f, angle);
        hintAnimator.setDuration(700);
        hintAnimator.setStartDelay(150);
        hintAnimator.setRepeatCount(2);
        hintAnimator.start();

        handler.removeCallbacks(autoHideRunnable);
        if (Prefs.autoHide(this)) {
            handler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS);
        }
    }

    private void hideButton() {
        handler.removeCallbacks(autoHideRunnable);
        if (!buttonShown) {
            return;
        }
        buttonShown = false;
        offeredRotation = -1;
        if (hintAnimator != null) {
            hintAnimator.cancel();
        }
        button.animate().cancel();
        button.setRotation(0f);
        button.setVisibility(View.GONE);
    }

    /**
     * The configured corner is meant as seen by the user, who holds the phone in the
     * {@code target} orientation. Translates it to a corner of the screen as currently drawn.
     */
    private int buttonGravity(int target, int current) {
        // Corners numbered clockwise: 0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left.
        int corner;
        switch (Prefs.position(this)) {
            case Prefs.POS_TOP_LEFT: corner = 0; break;
            case Prefs.POS_TOP_RIGHT: corner = 1; break;
            case Prefs.POS_BOTTOM_LEFT: corner = 3; break;
            default: corner = 2; break;
        }
        // Each step of rotation between the screen and the phone moves the corner one place on.
        int steps = (target - current + 4) % 4;
        switch ((corner + steps) % 4) {
            case 0: return Gravity.TOP | Gravity.LEFT;
            case 1: return Gravity.TOP | Gravity.RIGHT;
            case 2: return Gravity.BOTTOM | Gravity.RIGHT;
            default: return Gravity.BOTTOM | Gravity.LEFT;
        }
    }

    private boolean createButton() {
        ImageView view = new ImageView(this);
        view.setImageResource(R.drawable.ic_rotate);
        view.setBackgroundResource(R.drawable.rotate_button_bg);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int padding = dp(12);
        view.setPadding(padding, padding, padding, padding);
        view.setContentDescription(getString(R.string.rotate_button));
        view.setVisibility(View.GONE);
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onButtonClicked();
            }
        });

        int size = dp(48);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        lp.x = dp(20);
        lp.y = dp(20);
        lp.setTitle("RotateButton");

        try {
            windowManager.addView(view, lp);
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot show overlay", e);
            stopSelf();
            return false;
        }
        button = view;
        return true;
    }

    private void removeButton() {
        if (button != null) {
            if (hintAnimator != null) {
                hintAnimator.cancel();
                hintAnimator = null;
            }
            try {
                windowManager.removeView(button);
            } catch (RuntimeException ignored) {
                // Already gone.
            }
            button = null;
            buttonShown = false;
            offeredRotation = -1;
        }
    }

    private void onButtonClicked() {
        final int target = offeredRotation;
        if (target == -1) {
            return;
        }
        if (!Prefs.canWriteSettings(this)) {
            Toast.makeText(this, R.string.need_write_settings, Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(intent);
            } catch (RuntimeException e) {
                Log.w(TAG, "Cannot open write settings screen", e);
            }
            return;
        }

        pendingRotation = target;
        Settings.System.putInt(getContentResolver(), Settings.System.USER_ROTATION, target);
        hideButton();

        // Some apps force a fixed orientation, so the rotation never happens. Don't keep
        // offering it in that case until the phone is turned differently.
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (pendingRotation == target) {
                    pendingRotation = -1;
                    if (settledRotation == target) {
                        suppressedRotation = target;
                    }
                }
                evaluate();
            }
        }, APPLY_CHECK_DELAY_MS);
    }
}
