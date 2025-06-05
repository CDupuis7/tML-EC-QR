package com.example.watchapp.presentation;


import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.watchapp.R;
import com.samsung.android.service.health.tracking.ConnectionListener;
import com.samsung.android.service.health.tracking.HealthTracker;
import com.samsung.android.service.health.tracking.HealthTrackerException;
import com.samsung.android.service.health.tracking.HealthTrackingService;
import com.samsung.android.service.health.tracking.data.HealthTrackerType;

import java.util.List;

public class ConnectionManager {
    private final static String TAG = "Connection Manager";
    private final ConnectionObserver connectionObserver;
    private HealthTrackingService healthTrackingService = null;
    private final ConnectionListener connectionListener = new ConnectionListener() {
        @Override
        public void onConnectionSuccess() {
            Log.i(TAG, "Connected");
            connectionObserver.onConnectionResult(R.string.ConnectedToHs);
            if (!isSpO2Available(healthTrackingService)) {
                Log.i(TAG, "Device does not support SpO2 tracking");
                connectionObserver.onConnectionResult(R.string.NoSpo2Support);
            }
            if (!isHeartRateAvailable(healthTrackingService)) {
                Log.i(TAG, "Device does not support Heart Rate tracking");
                connectionObserver.onConnectionResult(R.string.NoHrSupport);
            }
        }

        @Override
        public void onConnectionEnded() {
            Log.i(TAG, "Disconnected");
        }

        @Override
        public void onConnectionFailed(HealthTrackerException e) {
            connectionObserver.onError(e);
        }
    };

    ConnectionManager(ConnectionObserver observer) {
        connectionObserver = observer;
    }

    public void connect(Context context) {
        healthTrackingService = new HealthTrackingService(connectionListener, context);
        healthTrackingService.connectService();
    }

    public void disconnect() {
        if (healthTrackingService != null)
            healthTrackingService.disconnectService();
    }

    public void initSpO2(SpO2Listener spO2Listener) {
        final HealthTracker healthTracker = healthTrackingService.getHealthTracker(HealthTrackerType.SPO2_ON_DEMAND);
        spO2Listener.setHealthTracker(healthTracker);
        setHandlerForBaseListener(spO2Listener);

        // 🔥 Start SpO2 tracker as well
        spO2Listener.startTracker();

    }

    public void initHeartRate(HeartRateListener heartRateListener) {
        final HealthTracker healthTracker = healthTrackingService.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS);
        heartRateListener.setHealthTracker(healthTracker);
        setHandlerForBaseListener(heartRateListener);

        // 🔥 This starts the tracker!
        heartRateListener.startTracker();
    }

    private void setHandlerForBaseListener(BaseListener baseListener) {
        baseListener.setHandler(new Handler(Looper.getMainLooper()));
    }

    private boolean isSpO2Available(@NonNull HealthTrackingService healthTrackingService) {
        final List<HealthTrackerType> availableTrackers = healthTrackingService.getTrackingCapability().getSupportHealthTrackerTypes();
        return availableTrackers.contains(HealthTrackerType.SPO2_ON_DEMAND);
    }

    private boolean isHeartRateAvailable(@NonNull HealthTrackingService healthTrackingService) {
        final List<HealthTrackerType> availableTrackers = healthTrackingService.getTrackingCapability().getSupportHealthTrackerTypes();
        return availableTrackers.contains(HealthTrackerType.HEART_RATE_CONTINUOUS);
    }
    public void logSupportedTrackers() {
        if (healthTrackingService != null && healthTrackingService.getTrackingCapability() != null) {
            List<HealthTrackerType> trackerTypes = healthTrackingService.getTrackingCapability().getSupportHealthTrackerTypes();
            for (HealthTrackerType type : trackerTypes) {
                Log.d("HealthTrackers", "Supported tracker: " + type.name());
            }
        } else {
            Log.w("HealthTrackers", "HealthTrackingService or TrackingCapability is null.");
        }
    }

}
