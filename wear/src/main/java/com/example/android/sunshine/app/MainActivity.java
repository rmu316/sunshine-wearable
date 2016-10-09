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
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public class MainActivity extends CanvasWatchFaceService {

    private static final String LOG_TAG = MainActivity.class.getSimpleName();

    private static final Typeface NORMAL_TYPEFACE = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MainActivity.Engine> mWeakReference;

        public EngineHandler(MainActivity.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MainActivity.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.updateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        private String mHighTemp = getString(R.string.default_high_temp_value);
        private String mLowTemp = getString(R.string.default_low_temp_value);
        private int mWeatherId = Integer.valueOf(getString(R.string.default_weather_id));

        Paint mBackgroundPaint;
        Paint mTitlePaint, mTextPaint, mSecondaryTextPaint, mTextPaintLight;
        boolean mRegisteredTimeZoneReceiver = false;
        boolean mAmbient;
        private GoogleApiClient mGoogleApiClient;
        Calendar mCalendar;

        private SimpleDateFormat mDayOfWeekFormat;

        Date mDate;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        boolean mLowBitAmbient;

        float mXStart;
        float mYStart;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            mGoogleApiClient = new GoogleApiClient.Builder(MainActivity.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
            mGoogleApiClient.connect();

            setWatchFaceStyle(new WatchFaceStyle.Builder(MainActivity.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            Resources resources = MainActivity.this.getResources();
            mYStart = resources.getDimension(R.dimen.y_axis_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.primary));

            mTitlePaint = new Paint();
            mTitlePaint = createTextPaint(resources.getColor(R.color.primary_text));

            mSecondaryTextPaint = new Paint();
            mSecondaryTextPaint = createTextPaint(resources.getColor(R.color.secondary_text));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.primary_text));

            mTextPaintLight = new Paint();
            mTextPaintLight = createTextPaint(resources.getColor(R.color.secondary_text));

            mCalendar = Calendar.getInstance();

            mDate = new Date();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            Log.d(LOG_TAG,"Visibility changed");
            if (visible) {
                registerReceiver();

                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    mGoogleApiClient.disconnect();
                }
            }

            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MainActivity.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MainActivity.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            Resources resources = MainActivity.this.getResources();
            boolean isRound = insets.isRound();
            mXStart = resources.getDimension(isRound ? R.dimen.x_axis_offset_round : R.dimen.x_axis_offset);
            float textSize = resources.getDimension(isRound ? R.dimen.text_size_round : R.dimen.text_size);
            float dateSize = resources.getDimension(isRound ? R.dimen.date_size_round : R.dimen.date_size);

            mTitlePaint.setTextSize(textSize);
            mTextPaint.setTextSize(dateSize);
            mTextPaintLight.setTextSize(dateSize);
            mSecondaryTextPaint.setTextSize(dateSize);
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
                    mTitlePaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // depending on whether we are in ambient mode, render the output canvas accordingly
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // drawing in the clock with current time
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            String text = String.format("%d:%02d", mCalendar.get(Calendar.HOUR),mCalendar.get(Calendar.MINUTE));

            canvas.drawText(text, bounds.centerX() - (mTitlePaint.measureText(text) / 2), mYStart, mTitlePaint);
            float basicOffset = getResources().getDimension(R.dimen.line_height);

            // render the actual image of the weather id
            Bitmap weatherIcon = BitmapFactory.decodeResource(getResources(), Utility.getIconResourceForWeatherCondition(mWeatherId));
            int scale = Integer.valueOf(getString(R.string.scale_size));
            Bitmap weather = Bitmap.createScaledBitmap(weatherIcon, scale, scale, true);

            mDayOfWeekFormat = new SimpleDateFormat("EEE, MMM dd yyyy");
            String date = mDayOfWeekFormat.format(mCalendar.getTime());

            // display the date
            canvas.drawText(date, bounds.centerX() - (mSecondaryTextPaint.measureText(date)/2), mYStart + basicOffset, mSecondaryTextPaint);

            // in the last row, display the weather icon, and then the high and low temperatures
            if (!mAmbient) {
                canvas.drawBitmap(weather, bounds.centerX() - (mSecondaryTextPaint.measureText(date)/2), mYStart + 1.5f*basicOffset, mTitlePaint);
                canvas.drawText(mHighTemp, bounds.centerX() - (mSecondaryTextPaint.measureText(mHighTemp)/2), mYStart + 2.5f*basicOffset, mTextPaint);
                canvas.drawText(mLowTemp, bounds.centerX() + (mSecondaryTextPaint.measureText(date)/2)-(mSecondaryTextPaint.measureText(mLowTemp)), mYStart + 2.5f*basicOffset, mTextPaintLight);
            }
        }

        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (isTimerRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        private boolean isTimerRunning() {
            return isVisible() && !isInAmbientMode();
        }

        private void updateTimeMessage() {
            invalidate();
            if (isTimerRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            for (DataEvent event : dataEvents) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    // DataItem changed
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().compareTo(getString(R.string.wear_weather_path)) == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        // grab data from Data item
                        mHighTemp = dataMap.getString(getString(R.string.wear_hi_key));
                        mLowTemp = dataMap.getString(getString(R.string.wear_low_key));
                        mWeatherId = dataMap.getInt(getString(R.string.wear_id_key));
                        Log.d(LOG_TAG, "High: " + mHighTemp + " Low: " + mLowTemp + " ID: " + mWeatherId);
                        invalidate();
                    }
                }
            }
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(LOG_TAG, "Google API Client Connection Suspended. Reason: " + i);
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            Log.d(LOG_TAG, "Google API client Connection failed: "+ connectionResult);
        }
    }
}
