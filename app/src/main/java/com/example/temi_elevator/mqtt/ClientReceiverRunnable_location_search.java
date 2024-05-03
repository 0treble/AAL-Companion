package com.example.temi_elevator.mqtt;

import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.example.temi_elevator.MainActivity;
import com.robotemi.sdk.Robot;

import org.json.JSONArray;

import java.util.List;


class ClientReceiverRunnable_location_search implements Runnable {
    private final String receivedMessage;
    private final MQTT myMQTT = MQTT.getInstance(MainActivity.getInstance());

    public ClientReceiverRunnable_location_search(String receivedMessage) {
        this.receivedMessage = receivedMessage;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public void run() {
        if (receivedMessage != null) {

            String log = myMQTT.getTime() + " Receive message in topic extern/temi/location/search: " + receivedMessage;
            String TAG = "LocationSearchClass";
            Log.i(TAG, log);
        }
        Robot temi = MainActivity.getInstance().getTemi();
        List<String> locationList = temi.getLocations();
        locationList.remove("aufzug");
        JSONArray jsonMsg = new JSONArray(locationList);

        myMQTT.publish("{\"locations\":" + jsonMsg + "}", MQTT.PUBLISH_TOPIC.TOPIC_LOCATION_FIND.getTopic());
    }
}