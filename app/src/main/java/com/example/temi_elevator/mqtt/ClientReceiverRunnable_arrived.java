package com.example.temi_elevator.mqtt;

import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.example.temi_elevator.MainActivity;

import org.json.JSONException;
import org.json.JSONObject;

class ClientReceiverRunnable_arrived implements Runnable {
    private final String receivedMessage;
    private final MQTT myMQTT = MQTT.getInstance(MainActivity.getInstance());

    public ClientReceiverRunnable_arrived(String receivedMessage) {
        this.receivedMessage = receivedMessage;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public void run() {
        if (receivedMessage != null) {

            String log = myMQTT.getTime() + " Receive message in topic extern/temi/arrived: " + receivedMessage;
            String TAG = "ArrivedClass";
            Log.i(TAG, log);

            try {
                JSONObject msg = new JSONObject(receivedMessage);
                Log.d(TAG, "destination floor ist: " + MainActivity.getInstance().getDestinationFloor());
                Log.d(TAG, "is waiting for reset: "+ MainActivity.getInstance().isWaitingForReset());
                if (msg.getInt("floor") == MainActivity.getInstance().getDestinationFloor() && MainActivity.getInstance().isWaitingForReset()) {
                    MainActivity.getInstance().reset();
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }
}