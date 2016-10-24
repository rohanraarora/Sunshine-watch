package com.example.android.sunshine.app.wear;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.example.android.sunshine.app.Utility;
import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallbacks;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayOutputStream;

/**
 * Created by rohanarora on 24/10/16.
 */

public class WearSyncService implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener{

    private static final String WEATHER_PATH = "/update-weather";


    private static final String[] NOTIFY_WEATHER_PROJECTION = new String[] {
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP,
            WeatherContract.WeatherEntry.COLUMN_SHORT_DESC
    };

    // these indices must match the projection
    private static final int INDEX_WEATHER_ID = 0;
    private static final int INDEX_MAX_TEMP = 1;
    private static final int INDEX_MIN_TEMP = 2;

    static WearSyncService wearSyncService;
    static GoogleApiClient mGoogleApiClient;
    static Context mContext;


    private WearSyncService(Context context){
        mContext = context;
        mGoogleApiClient = new GoogleApiClient.Builder(context)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();
        mGoogleApiClient.connect();
    }

    public static WearSyncService getInstance(Context context){
        if(wearSyncService == null){
            wearSyncService = new WearSyncService(context);
        }
        return wearSyncService;
    }

    public void updateWatch(){
        String locationQuery = Utility.getPreferredLocation(mContext);

        Uri weatherUri = WeatherContract.WeatherEntry.buildWeatherLocationWithDate(locationQuery, System.currentTimeMillis());

        // we'll query our contentProvider, as always
        Cursor cursor = mContext.getContentResolver().query(weatherUri, NOTIFY_WEATHER_PROJECTION, null, null, null);

        if (cursor.moveToFirst()) {
            int weatherId = cursor.getInt(INDEX_WEATHER_ID);
            double high = cursor.getDouble(INDEX_MAX_TEMP);
            int highTemp = (int)Math.round(high);
            double low = cursor.getDouble(INDEX_MIN_TEMP);
            int lowTemp =(int) Math.round(low);
            int iconId = Utility.getIconResourceForWeatherCondition(weatherId);
            Bitmap iconBitmap = BitmapFactory.decodeResource(mContext.getResources(), iconId);

            PutDataMapRequest mapRequest = PutDataMapRequest.create(WEATHER_PATH);
            mapRequest.getDataMap().putString("high-temp", highTemp + "°");
            mapRequest.getDataMap().putString("low-temp", lowTemp + "°");
            mapRequest.getDataMap().putAsset("icon-asset", createAssetFromBitmap(iconBitmap));

            PutDataRequest request = mapRequest.asPutDataRequest();
            Wearable.DataApi.putDataItem(mGoogleApiClient, request).setResultCallback(new ResultCallbacks<DataApi.DataItemResult>() {
                @Override
                public void onSuccess(DataApi.DataItemResult dataItemResult) {
                    Log.d("rohan", "sent Success wear!");
                }

                @Override
                public void onFailure(Status status) {
                    Log.d("rohan", "sent Failure!");
                }
            });
        }

    }

    private static Asset createAssetFromBitmap(Bitmap bitmap) {
        final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteStream);
        return Asset.createFromBytes(byteStream.toByteArray());
    }

    @Override
    public void onConnected(Bundle bundle) {

    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {

    }


}
