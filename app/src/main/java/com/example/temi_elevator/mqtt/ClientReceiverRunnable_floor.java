package com.example.temi_elevator.mqtt;

import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.example.temi_elevator.MainActivity;

import org.json.JSONException;
import org.json.JSONObject;

class ClientReceiverRunnable_floor implements Runnable {
    private final String receivedMessage;
    private final MQTT myMQTT = MQTT.getInstance(MainActivity.getInstance());

    public ClientReceiverRunnable_floor(String receivedMessage) {
        this.receivedMessage = receivedMessage;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public void run() {
        if (receivedMessage != null) {

            String log = myMQTT.getTime() + " Receive message in topic extern/temi/floor: " + receivedMessage;
            String TAG = "FloorClass";
            Log.i(TAG, log);

            try {
                JSONObject msg = new JSONObject(receivedMessage);
                int destinationFloor = msg.getInt("floor");

                if (destinationFloor != MainActivity.getInstance().getMyfloorNumber()) {
                    MainActivity.getInstance().setDestinationFloor(destinationFloor);
                    Log.i(TAG, "destination floor is " + destinationFloor);
                    if (msg.getString("task").equals("found")) {
                        if (MainActivity.getInstance().isWaitingForFloor()) {
                            MainActivity.getInstance().receivedFloor(destinationFloor);
                        }
                    }
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }
}