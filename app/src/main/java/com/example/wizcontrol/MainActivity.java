package com.example.wizcontrol;

import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    private final List<String> LIGHT_IPS = new ArrayList<>();
    private final List<String> LIGHT_NAMES = new ArrayList<>();
    private final int WIZ_PORT = 38899;
    
    private FrameLayout rootContainer;
    private LinearLayout dashboardLayout;
    private LinearLayout detailedViewLayout;
    private LinearLayout tileContainerRow;
    
    private String activeTargetIp;
    private int activeDeviceIndex = 0;
    private boolean activeDevicePowerState = true;
    
    private TextView detailedTitleText;
    private GooglePillSlider customSlider;
    private int currentActiveColor = 0xFFFBE29F;
    private final List<FrameLayout> colorCirclesList = new ArrayList<>();
    private final List<GoogleDashboardTile> dashboardTilesList = new ArrayList<>();
    
    private final int[] presetTemps = {0, 3000, 4500, 6000, 8000};
    private final int[] presetColors = {0xFF444444, 0xFFFBE29F, 0xFFFCEBBE, 0xFFF5EFEB, 0xFFE3EDF7};

    private Thread pollingThread;
    private volatile boolean isPollingActive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String[] initialIps = {"192.168.29.39","192.168.29.108"};
        LIGHT_IPS.addAll(Arrays.asList(initialIps));
        LIGHT_NAMES.add("desk lamp");
        for (int i = 2; i <= initialIps.length; i++) {
            LIGHT_NAMES.add("Light " + i);
        }

        rootContainer = new FrameLayout(this);
        rootContainer.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        
        setupDashboardView();
        setupDetailedControlView();
        
        rootContainer.addView(dashboardLayout);
        setContentView(rootContainer);
        syncWidgetMemoryMatrix();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startPollingEngine();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPollingEngine();
    }

    private void startPollingEngine() {
        isPollingActive = true;
        pollingThread = new Thread(() -> {
            while (isPollingActive) {
                pollLightStatuses();
                try {
                    Thread.sleep(3000); 
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        pollingThread.start();
    }

    private void stopPollingEngine() {
        isPollingActive = false;
        if (pollingThread != null) {
            pollingThread.interrupt();
        }
    }

    private void syncWidgetMemoryMatrix() {
        StringBuilder ipsBuilder = new StringBuilder();
        StringBuilder namesBuilder = new StringBuilder();
        for (int i = 0; i < LIGHT_IPS.size(); i++) {
            if (i > 0) { ipsBuilder.append(","); namesBuilder.append(","); }
            ipsBuilder.append(LIGHT_IPS.get(i));
            namesBuilder.append(LIGHT_NAMES.get(i));
        }
        SharedPreferences prefs = getSharedPreferences("WizPrefs", MODE_PRIVATE);
        prefs.edit()
             .putString("ips", ipsBuilder.toString())
             .putString("names", namesBuilder.toString())
             .apply();

        Intent intent = new Intent(this, WizWidgetProvider.class);
        intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        int[] ids = AppWidgetManager.getInstance(getApplication()).getAppWidgetIds(new ComponentName(getApplication(), WizWidgetProvider.class));
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
        sendBroadcast(intent);
    }

    private void setupDashboardView() {
        dashboardLayout = new LinearLayout(this);
        dashboardLayout.setOrientation(LinearLayout.VERTICAL);
        dashboardLayout.setBackgroundColor(0xFF1E1E1E);
        dashboardLayout.setPadding(40, 80, 40, 40);

        LinearLayout headerWrapper = new LinearLayout(this);
        headerWrapper.setOrientation(LinearLayout.HORIZONTAL);
        headerWrapper.setGravity(Gravity.CENTER_VERTICAL);
        headerWrapper.setPadding(20, 0, 20, 60);

        TextView homeHeader = new TextView(this);
        homeHeader.setText("Devices");
        homeHeader.setTextSize(26);
        homeHeader.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        homeHeader.setTextColor(Color.WHITE);
        headerWrapper.addView(homeHeader);

        View spacer = new View(this);
        LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(0, 1, 1.0f);
        spacer.setLayoutParams(spacerParams);
        headerWrapper.addView(spacer);

        TextView addDeviceBtn = new TextView(this);
        addDeviceBtn.setText("＋");
        addDeviceBtn.setTextSize(28);
        addDeviceBtn.setTextColor(Color.WHITE);
        addDeviceBtn.setPadding(20, 20, 20, 20);
        addDeviceBtn.setOnClickListener(v -> showAddDeviceDialog());
        headerWrapper.addView(addDeviceBtn);

        dashboardLayout.addView(headerWrapper);

        tileContainerRow = new LinearLayout(this);
        tileContainerRow.setOrientation(LinearLayout.HORIZONTAL);
        dashboardLayout.addView(tileContainerRow);

        refreshDashboardTiles();
    }

    private void refreshDashboardTiles() {
        tileContainerRow.removeAllViews();
        dashboardTilesList.clear();

        float totalWeightSum = (float) LIGHT_IPS.size();
        if (totalWeightSum < 1.0f) totalWeightSum = 1.0f;
        tileContainerRow.setWeightSum(totalWeightSum);

        for (int i = 0; i < LIGHT_IPS.size(); i++) {
            final int index = i;
            final String targetIp = LIGHT_IPS.get(i);
            final String label = LIGHT_NAMES.get(i);

            GoogleDashboardTile tile = new GoogleDashboardTile(this, label);
            LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, 220, 1.0f);
            tileParams.setMargins(15, 0, 15, 0);
            tile.setLayoutParams(tileParams);

            tile.setTileListener(new GoogleDashboardTile.TileInteractionListener() {
                @Override
                public void onToggleAction(boolean isCurrentlyOn) {
                    if (index == activeDeviceIndex) activeDevicePowerState = isCurrentlyOn;
                    sendWizMsg(targetIp, "{\"method\":\"setPilot\",\"params\":{\"state\":" + isCurrentlyOn + "}}");
                }
                @Override
                public void onBrightnessSlideAction(int currentBrightness) {
                    sendWizMsg(targetIp, "{\"method\":\"setPilot\",\"params\":{\"dimming\":" + currentBrightness + "}}");
                }
                @Override
                public void onLongPressTrigger() {
                    activeTargetIp = targetIp;
                    activeDeviceIndex = index;
                    openDetailedControlScreen(label);
                }
            });

            dashboardTilesList.add(tile);
            tileContainerRow.addView(tile);
        }
    }

    private void setupDetailedControlView() {
        detailedViewLayout = new LinearLayout(this);
        detailedViewLayout.setOrientation(LinearLayout.VERTICAL);
        detailedViewLayout.setBackgroundColor(0xFF1E1E1E); 
        detailedViewLayout.setPadding(40, 60, 40, 40);
        detailedViewLayout.setVisibility(View.GONE); 

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(10, 0, 10, 80);

        TextView closeBtn = new TextView(this);
        closeBtn.setText("✕");
        closeBtn.setTextSize(24);
        closeBtn.setTextColor(Color.WHITE);
        closeBtn.setPadding(10, 0, 40, 0);
        closeBtn.setOnClickListener(v -> closeDetailedControlScreen());
        headerRow.addView(closeBtn);

        detailedTitleText = new TextView(this);
        detailedTitleText.setTextSize(22);
        detailedTitleText.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        detailedTitleText.setTextColor(Color.WHITE);
        
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        detailedTitleText.setLayoutParams(titleParams);
        headerRow.addView(detailedTitleText);

        ImageView topPowerBtn = new ImageView(this);
        topPowerBtn.setImageResource(android.R.drawable.ic_lock_power_off);
        topPowerBtn.setColorFilter(Color.WHITE);
        topPowerBtn.setPadding(20, 20, 20, 20);
        topPowerBtn.setOnClickListener(v -> {
            activeDevicePowerState = !activeDevicePowerState;
            topPowerBtn.setColorFilter(activeDevicePowerState ? 0xFFE5C158 : Color.WHITE);
            sendWizMsg(activeTargetIp, "{\"method\":\"setPilot\",\"params\":{\"state\":" + activeDevicePowerState + "}}");
        });
        headerRow.addView(topPowerBtn);

        ImageView topEditSettingsBtn = new ImageView(this);
        topEditSettingsBtn.setImageResource(R.drawable.ic_edit_pencil); 
        topEditSettingsBtn.setColorFilter(Color.WHITE);
        topEditSettingsBtn.setPadding(20, 20, 20, 20);
        topEditSettingsBtn.setOnClickListener(v -> showEditDeviceSettingsDialog());
        headerRow.addView(topEditSettingsBtn);

        detailedViewLayout.addView(headerRow);

        FrameLayout sliderContainer = new FrameLayout(this);
        LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(520, 950); 
        containerParams.gravity = Gravity.CENTER_HORIZONTAL;
        containerParams.bottomMargin = 50;
        sliderContainer.setLayoutParams(containerParams);

        customSlider = new GooglePillSlider(this);
        customSlider.setProgress(43);
        customSlider.setFillColor(currentActiveColor);
        sliderContainer.addView(customSlider);
        detailedViewLayout.addView(sliderContainer);

        LinearLayout textBlock = new LinearLayout(this);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        textBlock.setGravity(Gravity.CENTER_HORIZONTAL);
        
        LinearLayout.LayoutParams textBlockParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        textBlockParams.bottomMargin = 80;
        textBlock.setLayoutParams(textBlockParams);

        final TextView percentText = new TextView(this);
        percentText.setText("43%");
        percentText.setTextSize(24);
        percentText.setTextColor(Color.WHITE);
        percentText.setGravity(Gravity.CENTER);
        percentText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textBlock.addView(percentText);

        TextView subtitleText = new TextView(this);
        subtitleText.setText("Brightness");
        subtitleText.setTextSize(14);
        subtitleText.setTextColor(0xFF999999);
        subtitleText.setGravity(Gravity.CENTER);
        subtitleText.setPadding(0, 8, 0, 0);
        textBlock.addView(subtitleText);
        detailedViewLayout.addView(textBlock);

        customSlider.setListener(progress -> {
            percentText.setText(progress + "%");
            if (progress > 0) {
                sendWizMsg(activeTargetIp, "{\"method\":\"setPilot\",\"params\":{\"dimming\":" + progress + "}}");
            }
        });

        LinearLayout optionsRow = new LinearLayout(this);
        optionsRow.setOrientation(LinearLayout.HORIZONTAL);
        optionsRow.setGravity(Gravity.CENTER);
        
        for (int i = 0; i < presetColors.length; i++) {
            final int index = i;
            FrameLayout optionPill = createColorOptionContainer(presetColors[i], index == 0);
            optionPill.setOnClickListener(v -> {
                updateActiveCheckmark(index);
                if (index == 0) {
                    showColorWheelPickerDialog();
                } else if (presetTemps[index] > 0) {
                    currentActiveColor = presetColors[index];
                    customSlider.setFillColor(presetColors[index]);
                    sendWizMsg(activeTargetIp, "{\"method\":\"setPilot\",\"params\":{\"temp\":" + presetTemps[index] + "}}");
                }
            });
            colorCirclesList.add(optionPill);
            optionsRow.addView(optionPill);
        }
        updateActiveCheckmark(3); 
        detailedViewLayout.addView(optionsRow);
        
        rootContainer.addView(detailedViewLayout);
    }

    private void showAddDeviceDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Add New WiZ Light");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 20);

        final EditText nameInput = new EditText(this);
        nameInput.setHint("Device Name (e.g. Bed Lamp)");
        layout.addView(nameInput);

        final EditText ipInput = new EditText(this);
        ipInput.setHint("IP Address (e.g. 192.168.29.50)");
        ipInput.setInputType(InputType.TYPE_CLASS_TEXT);
        layout.addView(ipInput);

        builder.setView(layout);

        builder.setPositiveButton("Add", (dialog, which) -> {
            String name = nameInput.getText().toString().trim();
            String ip = ipInput.getText().toString().trim();

            if (!name.isEmpty() && !ip.isEmpty()) {
                LIGHT_NAMES.add(name);
                LIGHT_IPS.add(ip);
                refreshDashboardTiles();
                syncWidgetMemoryMatrix();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void showEditDeviceSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Edit Device Configuration");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 20);

        final EditText nameInput = new EditText(this);
        nameInput.setHint("Device Name");
        nameInput.setText(LIGHT_NAMES.get(activeDeviceIndex));
        layout.addView(nameInput);

        final EditText ipInput = new EditText(this);
        ipInput.setHint("IP Address");
        ipInput.setText(LIGHT_IPS.get(activeDeviceIndex));
        ipInput.setInputType(InputType.TYPE_CLASS_TEXT);
        layout.addView(ipInput);

        builder.setView(layout);

        builder.setNeutralButton("Delete Device", (dialog, which) -> {
            LIGHT_NAMES.remove(activeDeviceIndex);
            LIGHT_IPS.remove(activeDeviceIndex);
            refreshDashboardTiles();
            closeDetailedControlScreen();
            syncWidgetMemoryMatrix();
        });

        builder.setPositiveButton("Save", (dialog, which) -> {
            String newName = nameInput.getText().toString().trim();
            String newIp = ipInput.getText().toString().trim();

            if (!newName.isEmpty() && !newIp.isEmpty()) {
                LIGHT_NAMES.set(activeDeviceIndex, newName);
                LIGHT_IPS.set(activeDeviceIndex, newIp);
                activeTargetIp = newIp;
                detailedTitleText.setText(newName);
                refreshDashboardTiles();
                syncWidgetMemoryMatrix();
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        
        AlertDialog currentDialog = builder.create();
        currentDialog.show();

        Button neutralBtn = currentDialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (neutralBtn != null) {
            neutralBtn.setTextColor(0xFFD32F2F);
        }
    }

    private void pollLightStatuses() {
        for (int i = 0; i < LIGHT_IPS.size(); i++) {
            final int index = i;
            final String targetIp = LIGHT_IPS.get(i);

            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(800); 
                byte[] payload = "{\"method\":\"getPilot\",\"params\":{}}".getBytes();
                InetAddress address = InetAddress.getByName(targetIp);
                DatagramPacket sendPacket = new DatagramPacket(payload, payload.length, address, WIZ_PORT);
                socket.send(sendPacket);

                byte[] buffer = new byte[1024];
                DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);
                socket.receive(receivePacket);

                String response = new String(receivePacket.getData(), 0, receivePacket.getLength());
                JSONObject json = new JSONObject(response).getJSONObject("result");
                
                final boolean hardwarePowerState = json.getBoolean("state");
                final int hardwareBrightness = json.getInt("dimming");

                new Handler(Looper.getMainLooper()).post(() -> {
                    if (index < dashboardTilesList.size()) {
                        GoogleDashboardTile tile = dashboardTilesList.get(index);
                        tile.updateStateFromHardware(hardwarePowerState, hardwareBrightness);
                    }
                    if (detailedViewLayout.getVisibility() == View.VISIBLE && index == activeDeviceIndex) {
                        activeDevicePowerState = hardwarePowerState;
                        customSlider.setProgress(hardwareBrightness);
                    }
                });
            } catch (Exception e) {
                // Ignore timeouts
            }
        }
    }

    private void showColorWheelPickerDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select Custom Color");

        LinearLayout wrapperLayout = new LinearLayout(this);
        wrapperLayout.setOrientation(LinearLayout.VERTICAL);
        wrapperLayout.setGravity(Gravity.CENTER);
        wrapperLayout.setPadding(40, 40, 40, 40);

        final ColorWheelView wheelView = new ColorWheelView(this);
        LinearLayout.LayoutParams wheelParams = new LinearLayout.LayoutParams(650, 650);
        wheelView.setLayoutParams(wheelParams);
        wrapperLayout.addView(wheelView);

        builder.setView(wrapperLayout);
        
        wheelView.setOnColorChangeListener(selectedColor -> {
            currentActiveColor = selectedColor;
            customSlider.setFillColor(selectedColor);
            
            int r = (selectedColor >> 16) & 0xFF;
            int g = (selectedColor >> 8) & 0xFF;
            int b = selectedColor & 0xFF;
            
            sendWizMsg(activeTargetIp, "{\"method\":\"setPilot\",\"params\":{\"r\":" + r + ",\"g\":" + g + ",\"b\":" + b + "}}");
        });

        builder.setPositiveButton("Done", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    private void openDetailedControlScreen(String title) {
        detailedTitleText.setText(title);
        TransitionSet set = new TransitionSet().addTransition(new Fade()).setDuration(300);
        TransitionManager.beginDelayedTransition(rootContainer, set);
        dashboardLayout.setVisibility(View.GONE);
        detailedViewLayout.setVisibility(View.VISIBLE);
    }

    private void closeDetailedControlScreen() {
        TransitionSet set = new TransitionSet().addTransition(new Fade()).setDuration(250);
        TransitionManager.beginDelayedTransition(rootContainer, set);
        detailedViewLayout.setVisibility(View.GONE);
        dashboardLayout.setVisibility(View.VISIBLE);
    }

    private FrameLayout createColorOptionContainer(int colorHex, boolean isPaletteSlot) {
        FrameLayout container = new FrameLayout(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(145, 145);
        params.setMargins(14, 0, 14, 0);
        container.setLayoutParams(params);

        android.graphics.drawable.GradientDrawable baseBg = new android.graphics.drawable.GradientDrawable();
        baseBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        baseBg.setColor(colorHex);
        container.setBackground(baseBg);

        ImageView iconLayer = new ImageView(this);
        iconLayer.setTag("iconLayer");
        iconLayer.setScaleType(ImageView.ScaleType.FIT_CENTER);
        
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(65, 65);
        iconParams.gravity = Gravity.CENTER;
        iconLayer.setLayoutParams(iconParams);

        if (isPaletteSlot) {
            iconLayer.setImageResource(R.drawable.ic_palette); 
            iconLayer.setColorFilter(Color.WHITE); 
        } else {
            iconLayer.setImageResource(R.drawable.ic_tick); 
            iconLayer.setVisibility(View.GONE);
        }

        container.addView(iconLayer);
        return container;
    }

    private void updateActiveCheckmark(int activeIndex) {
        for (int i = 0; i < colorCirclesList.size(); i++) {
            FrameLayout container = colorCirclesList.get(i);
            ImageView icon = container.findViewWithTag("iconLayer");
            android.graphics.drawable.GradientDrawable draw = (android.graphics.drawable.GradientDrawable) container.getBackground();
            
            if (i == activeIndex) {
                draw.setStroke(6, Color.WHITE); 
                container.setPadding(2, 2, 2, 2);
                if (icon != null) {
                    icon.setVisibility(View.VISIBLE);
                    icon.setColorFilter(Color.WHITE); 
                }
            } else {
                draw.setStroke(0, Color.TRANSPARENT);
                if (icon != null) {
                    if (i == 0) {
                        icon.setVisibility(View.VISIBLE);
                        icon.setColorFilter(Color.WHITE);
                    } else {
                        icon.setVisibility(View.GONE);
                    }
                }
            }
        }
    }

    private void sendWizMsg(String ip, String jsonPayload) {
        new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                byte[] buf = jsonPayload.getBytes();
                InetAddress address = InetAddress.getByName(ip);
                DatagramPacket packet = new DatagramPacket(buf, buf.length, address, WIZ_PORT);
                socket.send(packet);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    // ==========================================
    // NESTED COMPONENTS AND VIEW GRAPHICS CLASSES
    // ==========================================
    private static class ColorWheelView extends View {
        private final Paint wheelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint selectorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int selectedColor = Color.RED;
        private float selectorX, selectorY;
        private boolean isInitialized = false;
        private OnColorChangeListener changeListener;

        public interface OnColorChangeListener { void onColorChanged(int colorHex); }
        public ColorWheelView(Context context) {
            super(context);
            selectorPaint.setStyle(Paint.Style.STROKE);
            selectorPaint.setStrokeWidth(5f);
            selectorPaint.setColor(Color.BLACK);
        }
        public void setOnColorChangeListener(OnColorChangeListener l) { this.changeListener = l; }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float centerX = getWidth() / 2f;
            float centerY = getHeight() / 2f;
            float radius = Math.min(centerX, centerY) - 20f;

            if (!isInitialized) {
                selectorX = centerX;
                selectorY = centerY;
                int[] colors = {Color.RED, Color.MAGENTA, Color.BLUE, Color.CYAN, Color.GREEN, Color.YELLOW, Color.RED};
                SweepGradient gradient = new SweepGradient(centerX, centerY, colors, null);
                wheelPaint.setShader(gradient);
                isInitialized = true;
            }
            canvas.drawCircle(centerX, centerY, radius, wheelPaint);
            canvas.drawCircle(selectorX, selectorY, 15f, selectorPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float centerX = getWidth() / 2f;
            float centerY = getHeight() / 2f;
            float touchX = event.getX() - centerX;
            float touchY = event.getY() - centerY;
            
            float radius = Math.min(centerX, centerY) - 20f;
            float distance = (float) Math.sqrt(touchX * touchX + touchY * touchY);

            if (distance > radius) {
                touchX = (touchX / distance) * radius;
                touchY = (touchY / distance) * radius;
                distance = radius;
            }

            selectorX = touchX + centerX;
            selectorY = touchY + centerY;

            float angle = (float) Math.atan2(touchY, touchX);
            float hue = (float) Math.toDegrees(angle);
            if (hue < 0) hue += 360f;
            
            float saturation = distance / radius;
            selectedColor = Color.HSVToColor(new float[]{hue, saturation, 1.0f});
            
            invalidate();
            if (changeListener != null) changeListener.onColorChanged(selectedColor);
            return true;
        }
    }

    public static class GoogleDashboardTile extends FrameLayout {
        private final String deviceName;
        private boolean isPowerOn = true;
        private int currentBrightness = 64;
        
        private final View fillBackgroundMaskView;
        private final ImageView iconView;
        private final TextView titleLabel;
        private final TextView subtitleLabel;
        
        private TileInteractionListener interactionListener;
        private float initialTouchX, initialTouchY;
        private boolean isSlidingActive = false;
        private boolean isLongPressFired = false;

        private final Handler longPressHandler = new Handler(Looper.getMainLooper());
        private final Runnable longPressRunnable = new Runnable() {
            @Override
            public void run() {
                isLongPressFired = true;
                if (interactionListener != null) interactionListener.onLongPressTrigger();
            }
        };

        public interface TileInteractionListener {
            void onToggleAction(boolean isOn);
            void onBrightnessSlideAction(int brValue);
            void onLongPressTrigger();
        }

        public GoogleDashboardTile(Context context, String name) {
            super(context);
            this.deviceName = name;

            android.graphics.drawable.GradientDrawable containerBg = new android.graphics.drawable.GradientDrawable();
            containerBg.setCornerRadius(55f);
            containerBg.setColor(0xFF333333);
            this.setBackground(containerBg);

            fillBackgroundMaskView = new View(context);
            android.graphics.drawable.GradientDrawable progressFillBg = new android.graphics.drawable.GradientDrawable();
            progressFillBg.setCornerRadius(55f);
            progressFillBg.setColor(0xFF5B5335);
            fillBackgroundMaskView.setBackground(progressFillBg);
            addView(fillBackgroundMaskView);

            iconView = new ImageView(context);
            iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(75, 75);
            iconParams.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
            iconParams.leftMargin = 45;
            iconView.setLayoutParams(iconParams);
            addView(iconView);

            LinearLayout labelLayout = new LinearLayout(context);
            labelLayout.setOrientation(LinearLayout.VERTICAL);
            FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            layoutParams.gravity = Gravity.CENTER_VERTICAL;
            layoutParams.leftMargin = 145;
            layoutParams.rightMargin = 20;
            labelLayout.setLayoutParams(layoutParams);

            titleLabel = new TextView(context);
            titleLabel.setTextSize(15);
            titleLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            labelLayout.addView(titleLabel);

            subtitleLabel = new TextView(context);
            subtitleLabel.setTextSize(12);
            labelLayout.addView(subtitleLabel);

            addView(labelLayout);
            syncLayoutStateProperties();
        }

        public void setTileListener(TileInteractionListener l) { this.interactionListener = l; }

        public void updateStateFromHardware(boolean powerState, int brValue) {
            if (!isSlidingActive) {
                this.isPowerOn = powerState;
                this.currentBrightness = brValue;
                syncLayoutStateProperties();
            }
        }

        private void syncLayoutStateProperties() {
            titleLabel.setText(deviceName);
            titleLabel.setTextColor(isPowerOn ? 0xFFE5C158 : 0xFFCCCCCC);
            subtitleLabel.setText(isPowerOn ? "On • " + currentBrightness + "%" : "Off");
            subtitleLabel.setTextColor(isPowerOn ? 0xFFD8C395 : 0xFF888888);
            
            iconView.setImageResource(isPowerOn ? R.drawable.ic_lightbulb_on : R.drawable.ic_lightbulb_off);
            iconView.setColorFilter(isPowerOn ? 0xFFE5C158 : 0xFFCCCCCC);

            post(() -> {
                int baseWidth = getWidth();
                ViewGroup.LayoutParams params = fillBackgroundMaskView.getLayoutParams();
                params.height = getHeight();
                params.width = isPowerOn ? (int) (baseWidth * (currentBrightness / 100f)) : 0;
                fillBackgroundMaskView.setLayoutParams(params);
            });
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            float currentX = ev.getX();
            float currentY = ev.getY();
            switch (ev.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialTouchX = currentX;
                    initialTouchY = currentY;
                    isSlidingActive = false;
                    isLongPressFired = false;
                    longPressHandler.postDelayed(longPressRunnable, 450);
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float horizontalDelta = Math.abs(currentX - initialTouchX);
                    float verticalDelta = Math.abs(currentY - initialTouchY);
                    if (horizontalDelta > 20f || verticalDelta > 20f) longPressHandler.removeCallbacks(longPressRunnable);
                    if ((horizontalDelta > 25f || isSlidingActive) && !isLongPressFired) {
                        isSlidingActive = true;
                        int updatedBrightness = Math.round((currentX / getWidth()) * 100f);
                        updatedBrightness = Math.max(10, Math.min(100, updatedBrightness));
                        if (updatedBrightness != currentBrightness) {
                            currentBrightness = updatedBrightness;
                            isPowerOn = true;
                            syncLayoutStateProperties();
                            if (interactionListener != null) interactionListener.onBrightnessSlideAction(currentBrightness);
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    longPressHandler.removeCallbacks(longPressRunnable);
                    if (!isSlidingActive && !isLongPressFired && ev.getAction() == MotionEvent.ACTION_UP) {
                        isPowerOn = !isPowerOn;
                        syncLayoutStateProperties();
                        if (interactionListener != null) interactionListener.onToggleAction(isPowerOn);
                    }
                    isSlidingActive = false;
                    return true;
            }
            return super.onTouchEvent(ev);
        }
    }

    private static class GooglePillSlider extends View {
        private int progress = 43;
        private int fillColor = 0xFFFBE29F; 
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private SliderListener listener;

        public interface SliderListener { void onProgressChanged(int progress); }
        public GooglePillSlider(Context context) { super(context); }
        public void setProgress(int p) { this.progress = p; invalidate(); }
        public void setFillColor(int color) { this.fillColor = color; invalidate(); }
        public void setListener(SliderListener l) { this.listener = l; }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float radius = w / 2f;

            rect.set(0, 0, w, h);
            paint.setColor(0xFF444444); 
            canvas.drawRoundRect(rect, radius, radius, paint);

            float fillHeight = h * (progress / 100f);
            canvas.save();
            canvas.clipRect(0, h - fillHeight, w, h);
            rect.set(0, 0, w, h);
            paint.setColor(fillColor); 
            canvas.drawRoundRect(rect, radius, radius, paint);
            canvas.restore();

            if (fillHeight > 180) {
                Drawable sunIcon = ContextCompat.getDrawable(getContext(), R.drawable.ic_brightness);
                if (sunIcon != null) {
                    sunIcon.setTint(0xFF5D4037);
                    int targetIconSize = 96;
                    int leftOffset = (int) (w / 2f - targetIconSize / 2f);
                    int topOffset = (int) (h - 150f);
                    
                    canvas.save();
                    canvas.translate(leftOffset, topOffset);
                    float scaleFactor = (float) targetIconSize / 960f;
                    canvas.scale(scaleFactor, scaleFactor);
                    sunIcon.setBounds(0, 0, 960, 960);
                    sunIcon.draw(canvas);
                    canvas.restore();
                }
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    float y = event.getY();
                    float h = getHeight();
                    int p = Math.round((1f - (y / h)) * 100f);
                    p = Math.max(0, Math.min(100, p));
                    if (p != progress) {
                        progress = p;
                        invalidate();
                        if (listener != null) listener.onProgressChanged(p);
                    }
                    return true;
            }
            return super.onTouchEvent(event);
        }
    }
}
