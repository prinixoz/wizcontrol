package com.example.wizcontrol;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.RemoteViews;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class WizWidgetProvider extends AppWidgetProvider {

    private static final String TOGGLE_SLOT_ACTION = "com.example.wizcontrol.TOGGLE_SLOT";
    private static final String EXTRA_SLOT_INDEX = "extra_slot_index";
    private static final int WIZ_PORT = 38899;

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        SharedPreferences prefs = context.getSharedPreferences("WizPrefs", Context.MODE_PRIVATE);
        
        String ipsStr = prefs.getString("ips", "192.168.29.39,192.168.29.108");
        String namesStr = prefs.getString("names", "desk lamp,Light 2");
        
        String[] ips = ipsStr.split(",");
        String[] names = namesStr.split(",");

        for (int id : appWidgetIds) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_layout);

            Intent mainIntent = new Intent(context, MainActivity.class);
            mainIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent backgroundPI = PendingIntent.getActivity(
                    context, 999, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.widget_root_bg, backgroundPI);

            updateSlotUI(context, prefs, views, 0, ips, names, R.id.widget_slot1, R.id.widget_toggle_zone1, R.id.widget_link_zone1, R.id.widget_title1, R.id.widget_subtitle1, R.id.widget_icon1, id);
            updateSlotUI(context, prefs, views, 1, ips, names, R.id.widget_slot2, R.id.widget_toggle_zone2, R.id.widget_link_zone2, R.id.widget_title2, R.id.widget_subtitle2, R.id.widget_icon2, id);
            updateSlotUI(context, prefs, views, 2, ips, names, R.id.widget_slot3, R.id.widget_toggle_zone3, R.id.widget_link_zone3, R.id.widget_title3, R.id.widget_subtitle3, R.id.widget_icon3, id);

            appWidgetManager.updateAppWidget(id, views);
        }
    }

    private void updateSlotUI(Context context, SharedPreferences prefs, RemoteViews views, int index, String[] ips, String[] names, int slotId, int toggleZoneId, int linkZoneId, int titleId, int subId, int iconId, int widgetId) {
        if (index < ips.length && !ips[index].trim().isEmpty() && index < names.length) {
            boolean isOn = prefs.getBoolean("state_" + index, true);
            
            views.setViewVisibility(slotId, View.VISIBLE);
            views.setTextViewText(titleId, names[index]);
            views.setTextViewText(subId, isOn ? "On" : "Off");
            
            views.setInt(slotId, "setBackgroundResource", isOn ? R.drawable.widget_tile_on : R.drawable.widget_tile_off);
            views.setInt(iconId, "setColorFilter", isOn ? 0xFFE5C158 : 0xFFCCCCCC);

            Intent toggleIntent = new Intent(context, WizWidgetProvider.class);
            toggleIntent.setAction(TOGGLE_SLOT_ACTION);
            toggleIntent.putExtra(EXTRA_SLOT_INDEX, index);
            toggleIntent.setData(android.net.Uri.parse(toggleIntent.toUri(Intent.URI_INTENT_SCHEME)));

            PendingIntent togglePI = PendingIntent.getBroadcast(
                    context, index + (widgetId * 20), toggleIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            views.setOnClickPendingIntent(toggleZoneId, togglePI);

            Intent deepLinkIntent = new Intent(context, MainActivity.class);
            deepLinkIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            deepLinkIntent.putExtra("TARGET_DEVICE_INDEX", index);
            deepLinkIntent.setData(android.net.Uri.parse("wiz://device/" + index));

            PendingIntent linkPI = PendingIntent.getActivity(
                    context, index + (widgetId * 40), deepLinkIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(linkZoneId, linkPI);
        } else {
            views.setViewVisibility(slotId, View.INVISIBLE);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);

        if (TOGGLE_SLOT_ACTION.equals(intent.getAction())) {
            int slotIdx = intent.getIntExtra(EXTRA_SLOT_INDEX, -1);
            SharedPreferences prefs = context.getSharedPreferences("WizPrefs", Context.MODE_PRIVATE);
            String ipsStr = prefs.getString("ips", "192.168.29.39,192.168.29.108");
            String[] ips = ipsStr.split(",");

            if (slotIdx >= 0 && slotIdx < ips.length) {
                boolean nextState = !prefs.getBoolean("state_" + slotIdx, true);
                prefs.edit().putBoolean("state_" + slotIdx, nextState).apply();

                final String targetIp = ips[slotIdx];
                final String payload = "{\"method\":\"setPilot\",\"params\":{\"state\":" + nextState + "}}";
                
                new Thread(() -> {
                    try (DatagramSocket socket = new DatagramSocket()) {
                        byte[] buf = payload.getBytes();
                        InetAddress address = InetAddress.getByName(targetIp);
                        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, WIZ_PORT);
                        socket.send(packet);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }).start();

                AppWidgetManager manager = AppWidgetManager.getInstance(context);
                int[] activeIds = manager.getAppWidgetIds(new ComponentName(context, WizWidgetProvider.class));
                onUpdate(context, manager, activeIds);
            }
        }
    }
}
