package com.aurora.app;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

public class AuroraApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
