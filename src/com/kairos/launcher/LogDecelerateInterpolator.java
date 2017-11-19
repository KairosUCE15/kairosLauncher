package com.kairos.launcher;

import android.animation.TimeInterpolator;

/**--------------------------------------------------------------
 * TEAM KAIROS :: DEV CARLOS :: CLASS SYNOPSIS
 * Copyright (c) 2017 KAIROS
 *
 * Defines the rate of change of a animation. Optimization of standard TimeInterpolator for animation deceleration
 ----------------------------------------------------------------*/

public class LogDecelerateInterpolator implements TimeInterpolator {

    int mBase;
    int mDrift;
    final float mLogScale;

    public LogDecelerateInterpolator(int base, int drift) {
        mBase = base;
        mDrift = drift;

        mLogScale = 1f / computeLog(1, mBase, mDrift);
    }

    static float computeLog(float t, int base, int drift) {
        return (float) -Math.pow(base, -t) + 1 + (drift * t);
    }

    @Override
    public float getInterpolation(float t) {
        // Due to rounding issues, the interpolation doesn't quite reach 1 even though it should.
        // To account for this, we short-circuit to return 1 if the input is 1.
        return Float.compare(t, 1f) == 0 ? 1f : computeLog(t, mBase, mDrift) * mLogScale;
    }
}
