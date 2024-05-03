package com.example.temi_elevator.mqtt;

import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.example.temi_elevator.MainActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

class ClientReceiverRunnable_location_find implements Runnable {
    private final String receivedMessage;

    private final MQTT myMQTT = MQTT.getInstance(MainActivity.getInstance());

    public ClientReceiverRunnable_location_find(String receivedMessage) {
        this.receivedMessage = receivedMessage;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public void run() {
        if (receivedMessage != null) {

            String log = myMQTT.getTime() + " Receive message in topic extern/temi/location/find: " + receivedMessage;
            String TAG = "LocationFindClass";
            Log.i(TAG, log);

            try {
                JSONObject msg = new JSONObject(receivedMessage);
                JSONArray locationArray = msg.getJSONArray("locations");

                List<String> locationsList = new ArrayList<>();
                for (int i = 0; i < locationArray.length(); i++) {
                    locationsList.add(locationArray.getString(i));
                }

                String[] locations = locationsList.toArray(new String[0]);
                MainActivity.getInstance().addLocations(locations);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }
}