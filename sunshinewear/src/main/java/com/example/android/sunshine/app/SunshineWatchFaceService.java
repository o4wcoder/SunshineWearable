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
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.example.android.sunshine.app.R;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFaceService extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);
    final String TAG = SunshineWatchFaceService.class.getSimpleName();
    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    GoogleApiClient mGoogleApiClient;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFaceService.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFaceService.Engine engine = mWeakReference.get();
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
            DataApi.DataListener, OnLoadBitmapListener{



        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        Paint mDateTextPaint;
        Paint mLinePaint;
        Paint mTempPaint;

        boolean mAmbient;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        int mTapCount;

        float mXOffset;
        float mYOffset;

        //Date offsets
        float mDateXOffset;
        float mDateYOffset;

        //Line Offsets
        float mLineXStart;
        float mLineXEnd;
        float mLineY;

        float mTempYOffset;
        float mHightTempXOffset;
        float mLowTempXOffset;

        //High and Low temps
        String mHighTemp = "";
        String mLowTemp = "";

        //Keys to the data sent from the phone
        private static final String TEMP_PATH = "/temp";
        private static final String HIGH_TEMP_KEY = "hightemp";
        private static final String LOW_TEMP_KEY = "lowtemp";
        private static final String IMAGE_KEY = "forecast_icon";

        private static final String DATA_ITEM_RECEIVED_PATH = "/data-item-received";

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;



        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            Log.e(TAG,"---- onCreate() ----");
            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            //Get offsets from dimensions
            Resources resources = SunshineWatchFaceService.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);
            mDateYOffset = resources.getDimension(R.dimen.date_y_offset);
            mTempYOffset = resources.getDimension(R.dimen.temp_y_offset);

            mLineXStart = resources.getDimension(R.dimen.line_x_start);
            mLineXEnd = resources.getDimension(R.dimen.line_x_end);
            mLineY = resources.getDimension(R.dimen.line_y);
            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            //Create paint for date
            mDateTextPaint = new Paint();
            mDateTextPaint = createTextPaint(resources.getColor(R.color.digital_text_grey));

            //Create paint for divider line
            mLinePaint = new Paint();
            mLinePaint.setColor(resources.getColor(R.color.divider_line));
            mLinePaint.setStrokeWidth(1f);
            mLinePaint.setStyle(Paint.Style.STROKE);

            //Create high/low temperature paint
            mTempPaint = new Paint();
            mTempPaint = createTextPaint(resources.getColor(R.color.digital_text));


            mTime = new Time();

            //Connect to Google Play Services to recieve data from the phone
            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFaceService.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
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
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
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
            SunshineWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFaceService.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            mTextPaint.setTextSize(textSize);

            //Set Date offsets
            mDateXOffset = resources.getDimension(isRound
                    ? R.dimen.date_x_offset_round : R.dimen.date_x_offset);
            float dateTextSize = resources.getDimension(isRound
                    ? R.dimen.date_text_size_round : R.dimen.date_text_size);
            mDateTextPaint.setTextSize(dateTextSize);

            //Set Temperature offsets
            mHightTempXOffset = resources.getDimension(isRound
                    ? R.dimen.high_temp_x_offset_round : R.dimen.high_temp_x_offset);
            mLowTempXOffset = resources.getDimension(isRound
                    ? R.dimen.low_temp_x_offset_round : R.dimen.low_temp_x_offset);

            float tempTextSize = resources.getDimension(isRound
                    ? R.dimen.temp_text_size_round : R.dimen.temp_text_size);

            mTempPaint.setTextSize(tempTextSize);


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
                    mTextPaint.setAntiAlias(!inAmbientMode);
                    mDateTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = SunshineWatchFaceService.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.background2));
                    break;
            }
            invalidate();
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
            mTime.setToNow();

//            String text = mAmbient
//                    ? String.format("%d:%02d", mTime.hour, mTime.minute)
//                    : String.format("%d:%02d:%02d", mTime.hour, mTime.minute, mTime.second);

            String text = String.format("%d:%02d", mTime.hour, mTime.minute);
            canvas.drawText(text, mXOffset, mYOffset, mTextPaint);

            Date currentDate = new Date();
            String strCurrentDate = new SimpleDateFormat("MMM d, yyyy").format(currentDate);
            canvas.drawText(strCurrentDate,mXOffset,mDateYOffset,mDateTextPaint);

            float centerX = bounds.width() / 2f;
            canvas.drawLine(centerX - 30,mLineY,centerX + 30,mLineY,mLinePaint);

           // String highStr = "72" + "\u00B0";
           // String lowStr = "54" + "\u00B0";

            canvas.drawText(mHighTemp,mHightTempXOffset,mTempYOffset,mTempPaint);
            canvas.drawText(mLowTemp,mLowTempXOffset,mTempYOffset,mTempPaint);


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
        public void onConnected(Bundle bundle) {

            Log.e(TAG,"onConnected()");
            Wearable.DataApi.addListener(mGoogleApiClient, this);

        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {

            Log.e(TAG, "onDataChanged in watchface: " + dataEvents);
//            if (!mGoogleApiClient.isConnected() || !mGoogleApiClient.isConnecting()) {
//                ConnectionResult connectionResult = mGoogleApiClient
//                        .blockingConnect(30, TimeUnit.SECONDS);
//                if (!connectionResult.isSuccess()) {
//                    Log.e(TAG, "DataLayerListenerService failed to connect to GoogleApiClient, "
//                            + "error code: " + connectionResult.getErrorCode());
//                    return;
//                }
//            }

            // Loop through the events and send a message back to the node that created the data item.
            for (DataEvent event : dataEvents) {
                Uri uri = event.getDataItem().getUri();
                String path = uri.getPath();
                Log.e(TAG,"Path to data = " + path);
                if (TEMP_PATH.equals(path)) {
                    // Get the node id of the node that created the data item from the host portion of
                    // the uri.
                    String nodeId = uri.getHost();
                    // Set the data of the message to be the bytes of the Uri.
                    byte[] payload = uri.toString().getBytes();

                    Log.e(TAG,"Pull datamap");
                    DataMap dataMap = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();
                    Log.e(TAG, "High temp = " + dataMap.getString(HIGH_TEMP_KEY));
                    Log.e(TAG, "Low temp = " + dataMap.getString(LOW_TEMP_KEY));
                    // Send the rpc
                    mHighTemp = dataMap.getString(HIGH_TEMP_KEY);
                    mLowTemp = dataMap.getString(LOW_TEMP_KEY);

                    final Asset photoAsset = dataMap.getAsset(IMAGE_KEY);
                    Wearable.MessageApi.sendMessage(mGoogleApiClient, nodeId, DATA_ITEM_RECEIVED_PATH,
                            payload);

                    new AssetToBitimapAsyncTask(this).execute(photoAsset);



                }
            }
        }

        @Override
        public void onLoadBitmapFinished(Bitmap bitmap) {

            Log.e(TAG,"onLoadBitmapFinished()! invalidate and redraw");
            if(bitmap != null)
                Log.e(TAG,"Bitmap not null! horray!");

            invalidate();
        }
    }

    private class AssetToBitimapAsyncTask extends AsyncTask<Asset, Void, Bitmap> {

        private OnLoadBitmapListener listener;

        public AssetToBitimapAsyncTask(OnLoadBitmapListener listener) {
            this.listener = listener;
        }
        @Override
        protected Bitmap doInBackground(Asset... params) {

            if(params.length > 0) {

                Asset asset = params[0];

                InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                        mGoogleApiClient, asset).await().getInputStream();

                if (assetInputStream == null) {
                    Log.w(TAG, "Requested an unknown Asset.");
                    return null;
                }
                return BitmapFactory.decodeStream(assetInputStream);

            } else {
                Log.e(TAG, "Asset must be non-null");
                return null;
            }
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {

            if(bitmap != null) {
                Log.e(TAG, "Created Bitmap!..");


                listener.onLoadBitmapFinished(bitmap);
                //redraw watch face
              //  SunshineWatchFaceService.Engine.this.invalidate();
            }
        }

    }

    interface OnLoadBitmapListener {

        void onLoadBitmapFinished(Bitmap bitmap);
    }
}
