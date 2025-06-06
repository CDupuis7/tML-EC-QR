package com.example.watchapp.presentation;

import com.samsung.android.service.health.tracking.HealthTrackerException;

public interface ConnectionObserver {
    void onConnectionResult(int stringResourceId);

    void onError(HealthTrackerException e);
}
