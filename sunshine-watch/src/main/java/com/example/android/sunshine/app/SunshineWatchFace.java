/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.icu.text.SimpleDateFormat;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;


import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener,
        DataApi.DataListener {

        private static final String WEATHER_PATH = "/update-weather";

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTimePaint;
        Paint mDatePaint;
        Paint mIconPaint;
        Paint mHighTempPaint;
        Paint mLowTempPaint;
        boolean mAmbient;
        Calendar mCalendar;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mXOffset;
        float mYOffset;
        String highTemp = "";
        String lowTemp = "";
        Bitmap iconBitmap;
        Boolean isRound = false;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        GoogleApiClient mGoogleApiClient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));
            mIconPaint = new Paint();
            mTimePaint = createTextPaint(resources.getColor(R.color.digital_text));
            mDatePaint = createLightTextPaint(resources.getColor(R.color.digital_text_light));
            mHighTempPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mLowTempPaint = createLightTextPaint(resources.getColor(R.color.digital_text_light));

            mCalendar = Calendar.getInstance();

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();
            mGoogleApiClient.connect();

        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(BOLD_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createLightTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float largeSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float smallSize = resources.getDimension(R.dimen.digital_text_small_size);

            mTimePaint.setTextSize(largeSize);
            mHighTempPaint.setTextSize(largeSize);
            mDatePaint.setTextSize(smallSize);
            mLowTempPaint.setTextSize(smallSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimePaint.setAntiAlias(!inAmbientMode);
                    mDatePaint.setAntiAlias(!inAmbientMode);
                    mHighTempPaint.setAntiAlias(!inAmbientMode);
                    mLowTempPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            float centerX = bounds.centerX();
            float centerY = bounds.centerY();
            float margin = getResources().getDimension(R.dimen.margin);
            margin = convertPixelsToDp(margin);

            String timeText = mAmbient
                    ? String.format("%d:%02d", mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE))
                    : String.format("%d:%02d:%02d", mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE), mCalendar.get(Calendar.SECOND));

            float timeWidth = mTimePaint.measureText(timeText);
            canvas.drawText(timeText, centerX - timeWidth/2, mYOffset, mTimePaint);

            if(!mAmbient) {
                java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("EEE, MMM d");
                String dateText = dateFormat.format(new Date(now));

                float dateWidth = mDatePaint.measureText(dateText);
                float dateOffset = mYOffset + margin/2 + convertPixelsToDp(mTimePaint.getTextSize());
                canvas.drawText(dateText,centerX - dateWidth/2,dateOffset ,mDatePaint);
            }

            float tempYOffset;
            if(isRound){
                tempYOffset = 2 * centerY - mYOffset + convertPixelsToDp(mHighTempPaint.getTextSize());
            }
            else {
                tempYOffset = centerY + 2*margin + 2*convertPixelsToDp(mHighTempPaint.getTextSize());
            }



            float tempWidth = mHighTempPaint.measureText(highTemp) + margin/2
                    + mLowTempPaint.measureText(lowTemp);
            if(!mAmbient){
                float highTempXOffset;
                if(isRound) {
                    highTempXOffset = centerX + margin;
                }
                else {
                    highTempXOffset = centerX - tempWidth/2;
                }
                float lowTempXOffset = highTempXOffset + margin/2
                        + mHighTempPaint.measureText(highTemp);
                canvas.drawText(highTemp,highTempXOffset,tempYOffset,mHighTempPaint);
                canvas.drawText(lowTemp,lowTempXOffset,tempYOffset,mLowTempPaint);
            }
            else {
                float highTempXOffset = centerX - tempWidth/2;
                float lowTempXOffset = highTempXOffset + margin/2
                        + mHighTempPaint.measureText(highTemp);
                canvas.drawText(highTemp,highTempXOffset,tempYOffset,mHighTempPaint);
                canvas.drawText(lowTemp,lowTempXOffset,tempYOffset,mLowTempPaint);
            }

            if(!mAmbient && iconBitmap!=null){
                float iconYOffset = centerY + margin;
                if(isRound) {
                    canvas.drawBitmap(iconBitmap, centerX - iconBitmap.getWidth() - margin, iconYOffset, mIconPaint);
                }
                else {
                    canvas.drawBitmap(iconBitmap,centerX + margin,15,mIconPaint);
                }
            }


        }

        public float convertDpToPixel(float dp){
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            float px = dp * ((float)metrics.densityDpi / DisplayMetrics.DENSITY_DEFAULT);
            return px;
        }


        public float convertPixelsToDp(float px){
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            float dp = px / ((float)metrics.densityDpi / DisplayMetrics.DENSITY_DEFAULT);
            return dp;
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }



        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d("rohan","fail " + connectionResult.getErrorCode() + " " + connectionResult.getErrorMessage());
        }


        @Override
        public void onConnected(Bundle bundle) {
            Log.d("rohan","connected");
            Wearable.DataApi.addListener(mGoogleApiClient, this);
        }

        @Override
        public void onConnectionSuspended(int i) {

        }


        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            Log.d("rohan","receive");
            for (DataEvent event : dataEvents) {
                if (event.getType() == DataEvent.TYPE_CHANGED &&
                        event.getDataItem().getUri().getPath().equals(WEATHER_PATH)) {
                    processDataItem(event.getDataItem());
                    // Do something with the bitmap
                }
            }
        }

        public void processDataItem(DataItem dataItem){
            DataMapItem dataMapItem = DataMapItem.fromDataItem(dataItem);
            highTemp = dataMapItem.getDataMap().getString("high-temp");
            lowTemp = dataMapItem.getDataMap().getString("low-temp");
            Asset iconAsset = dataMapItem.getDataMap().getAsset("icon-asset");
            GetBitmapTask task = new GetBitmapTask();
            task.execute(iconAsset);
        }

        public Bitmap loadBitmapFromAsset(Asset asset) {
            if (asset == null) {
                throw new IllegalArgumentException("Asset must be non-null");
            }
            ConnectionResult result =
                    mGoogleApiClient.blockingConnect(1500, TimeUnit.MILLISECONDS);
            if (!result.isSuccess()) {
                return null;
            }
            // convert asset into a file descriptor and block until it's ready
            InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                    mGoogleApiClient, asset).await().getInputStream();
            mGoogleApiClient.disconnect();

            if (assetInputStream == null) {
                return null;
            }
            // decode the stream into a bitmap
            return BitmapFactory.decodeStream(assetInputStream);
        }

        public class GetBitmapTask extends AsyncTask<Asset, Void, Bitmap> {

            @Override
            protected Bitmap doInBackground(Asset... assets) {
                Asset asset = assets[0];
                Bitmap iconBitmap = loadBitmapFromAsset(asset);

                int size = Double.valueOf(SunshineWatchFace.this.getResources().getDimension(R.dimen.icon_size)).intValue();
                return Bitmap.createScaledBitmap(iconBitmap, size, size, false);
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                iconBitmap = bitmap;
                postInvalidate();
            }
        }
    }
}
