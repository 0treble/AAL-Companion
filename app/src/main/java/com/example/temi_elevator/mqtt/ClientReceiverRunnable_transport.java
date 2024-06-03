package com.example.temi_elevator.mqtt;

import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.example.temi_elevator.MainActivity;
import com.robotemi.sdk.Robot;

import org.json.JSONException;
import org.json.JSONObject;

class ClientReceiverRunnable_transport implements Runnable {
    private final String receivedMessage;
    private final MQTT myMQTT = MQTT.getInstance(MainActivity.getInstance());

    public ClientReceiverRunnable_transport(String receivedMessage) {
        this.receivedMessage = receivedMessage;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public void run() {
        if (receivedMessage != null) {

            String log = myMQTT.getTime() + " Receive message in topic extern/temi/transport: " + receivedMessage;
            String TAG = "TransportClass";
            Log.i(TAG, log);

            try {
                JSONObject msg = new JSONObject(receivedMessage);
                String destination = msg.getString("destination");
                Robot temi = MainActivity.getInstance().getTemi();

                if (temi.getLocations().contains(destination)) {
                    MainActivity.getInstance().hideModeElements();
                    temi.goTo(destination);

                    MainActivity.getInstance().showContent(destination);
                    MainActivity.getInstance().setNewDest(destination);
                    myMQTT.publish("{\"destination\":\"" + destination + "\",\"floor\":\"" + MainActivity.getInstance().getMyfloorNumber() + "\",\"task\":\"found\"}", MQTT.PUBLISH_TOPIC.TOPIC_FLOOR.getTopic());
                }

            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }
}