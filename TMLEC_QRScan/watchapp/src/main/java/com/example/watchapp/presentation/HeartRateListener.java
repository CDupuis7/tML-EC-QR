package com.example.watchapp.presentation;

import android.util.Log;

import androidx.annotation.NonNull;

import com.example.watchapp.R;
import com.samsung.android.service.health.tracking.HealthTracker;
import com.samsung.android.service.health.tracking.data.DataPoint;
import com.samsung.android.service.health.tracking.data.ValueKey;

import java.util.List;

public class HeartRateListener extends BaseListener {
    private final static String APP_TAG = "HeartRateListener";

    HeartRateListener() {
        final HealthTracker.TrackerEventListener trackerEventListener = new HealthTracker.TrackerEventListener() {
            @Override
            public void onDataReceived(@NonNull List<DataPoint> list) {
                Log.d(APP_TAG, "onDataReceived: HR List Size = " + list.size());
                for (DataPoint dataPoint : list) {
                    readValuesFromDataPoint(dataPoint);
                }
            }

            @Override
            public void onFlushCompleted() {
                Log.i(APP_TAG, " onFlushCompleted called");
            }

            @Override
            public void onError(HealthTracker.TrackerError trackerError) {
                Log.e(APP_TAG, " onError called: " + trackerError);
                setHandlerRunning(false);
                if (trackerError == HealthTracker.TrackerError.PERMISSION_ERROR) {
                    TrackerDataNotifier.getInstance().notifyError(R.string.NoPermission);
                }
                if (trackerError == HealthTracker.TrackerError.SDK_POLICY_ERROR) {
                    TrackerDataNotifier.getInstance().notifyError(R.string.SdkPolicyError);
                }
            }
        };
        setTrackerEventListener(trackerEventListener);
    }

    public void readValuesFromDataPoint(DataPoint dataPoint) {
        final HeartRateData hrData = new HeartRateData();
        final List<Integer> hrIbiList = dataPoint.getValue(ValueKey.HeartRateSet.IBI_LIST);
        final List<Integer> hrIbiStatus = dataPoint.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST);

        hrData.status = dataPoint.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS);
        hrData.hr = dataPoint.getValue(ValueKey.HeartRateSet.HEART_RATE);
        if (hrIbiList != null && hrIbiList.size() != 0) {
            hrData.ibi = hrIbiList.get(hrIbiList.size() - 1); // Inter-Beat Interval (ms)
        }
        if (hrIbiStatus != null && hrIbiStatus.size() != 0) {
            hrData.qIbi =  hrIbiStatus.size() - 1; // 1: bad, 0: good
        }
        TrackerDataNotifier.getInstance().notifyHeartRateTrackerObservers(hrData);
        Log.d(APP_TAG, dataPoint.toString());
    }

}
