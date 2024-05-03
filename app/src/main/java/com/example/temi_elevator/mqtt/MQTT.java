package com.example.temi_elevator.mqtt;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;

import com.example.temi_elevator.MainActivity;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

public class MQTT {
    private final String TAG = "MQTTClass";
    final Context myContext;
    @SuppressLint("StaticFieldLeak")
    private static MQTT myMqtt;
    private MqttAndroidClient myMqttAndroidClient;
    private MqttConnectOptions myMqttConnectOptions;
    private final static String HOST = "tcp://192.168.29.108:1883";
    private final static int QUALITY_OF_SERVICE = 2;
    private String clientId = "Temi";

    public enum PUBLISH_TOPIC {
        TOPIC_LOCATION_FIND {
            @Override
            public String getTopic() {
                return "extern/temi/location/find";
            }
        },
        TOPIC_LOCATION_SEARCH {
            @Override
            public String getTopic() {
                return "extern/temi/location/search";
            }
        },
        TOPIC_TRANSPORT {
            @Override
            public String getTopic() {
                return "extern/temi/transport";
            }
        },
        TOPIC_FLOOR {
            @Override
            public String getTopic() {
                return "extern/temi/floor";
            }
        },
        TOPIC_ARRIVED {
            @Override
            public String getTopic() {
                return "extern/temi/arrived";
            }
        };

        public abstract String getTopic();
    }

    private enum SUBSCRIBE_TOPIC {
        TOPIC_LOCATION_FIND {
            @Override
            public String getTopic() {
                return "extern/temi/location/find";
            }
        },
        TOPIC_LOCATION_SEARCH {
            @Override
            public String getTopic() {
                return "extern/temi/location/search";
            }
        },
        TOPIC_TRANSPORT {
            @Override
            public String getTopic() {
                return "extern/temi/transport";
            }
        },
        TOPIC_FLOOR {
            @Override
            public String getTopic() {
                return "extern/temi/floor";
            }
        },
        TOPIC_ARRIVED {
            @Override
            public String getTopic() {
                return "extern/temi/arrived";
            }
        };

        public abstract String getTopic();
    }

    private MQTT(Context context) {
        this.myContext = context;
        init();
    }

    public static MQTT getInstance(Context context) {
        if (myMqtt == null) {
            myMqtt = new MQTT(context);
        }
        return myMqtt;
    }

    public void init() {
        long suffix = new Random().nextLong();
        clientId = clientId.concat(String.valueOf(suffix));

        myMqttConnectOptions = new MqttConnectOptions();
        myMqttConnectOptions.setCleanSession(true);
        myMqttConnectOptions.setConnectionTimeout(10);
        myMqttConnectOptions.setKeepAliveInterval(30);
    }

    private final MqttCallback myMqttCallback = new MqttCallback() {
        @Override
        public void connectionLost(Throwable cause) {
            Log.i(TAG, "Mqtt Connection Lost");
            myMqttCallback.connectionLost(cause);
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            iGetMessage(topic, message);
            Log.i(TAG, "messageArrived: " + message.toString());

        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            Log.i(TAG, "delivery Complete");
        }

    };

    public void connect() {
        myMqttAndroidClient = new MqttAndroidClient(MainActivity.getInstance().getApplicationContext(), HOST, clientId);
        myMqttAndroidClient.setCallback(myMqttCallback);
        new Thread(() -> {
            try {
                myMqttAndroidClient.connect(myMqttConnectOptions, null, new IMqttActionListener() {
                    @Override
                    public void onSuccess(IMqttToken iMqttToken) {
                        String log = getTime() + " Connect Success to Mqtt Sever Broker";
                        Log.i(TAG, log);
                        try {
                            for (SUBSCRIBE_TOPIC topic : SUBSCRIBE_TOPIC.values()) {
                                subscribe(topic.getTopic());
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        getLocations();
                    }

                    @Override
                    public void onFailure(IMqttToken iMqttToken, Throwable throwable) {
                        throwable.printStackTrace();
                        String log = getTime() + " Mqtt connect failed";
                        Log.i(TAG, log);
                    }
                });
            } catch (Exception e) {
                String log = getTime() + " Mqtt connect: " + e.getMessage();
                Log.i(TAG, log);
                e.printStackTrace();
            }
        }).start();
    }

    public void subscribe(String topic) {
        try {
            myMqttAndroidClient.subscribe(topic, QUALITY_OF_SERVICE, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken iMqttToken) {
                    String log = getTime() + " subscribed success: " + topic;
                    Log.i(TAG, log);
                }

                @Override
                public void onFailure(IMqttToken iMqttToken, Throwable throwable) {
                    String log = getTime() + " subscribed failed: " + topic;
                    Log.i(TAG, log);
                }
            });
        } catch (MqttException e) {
            String log = getTime() + " subscribe MqttException " + topic + e.getMessage();
            Log.i(TAG, log);
            e.printStackTrace();
        }

    }

    public void publish(String payload, String topic) {
        MqttMessage message = new MqttMessage();
        message.setPayload(payload.getBytes());
        message.setQos(QUALITY_OF_SERVICE);
        message.setRetained(false);

        try {
            if (myMqttAndroidClient != null && myMqttAndroidClient.isConnected()) {
                myMqttAndroidClient.publish(topic, message, null, new IMqttActionListener() {
                    @Override
                    public void onSuccess(IMqttToken iMqttToken) {
                        Log.i(TAG, "onSuccess: message sent successfully");
                    }

                    @Override
                    public void onFailure(IMqttToken iMqttToken, Throwable throwable) {
                        Log.i(TAG, "onFailure: publish failed = " + throwable.getMessage());
                    }
                });
            } else {
                String log = getTime() + " MqttAndroidClient is Null or is not connected ";
                Log.i(TAG, log);
            }
        } catch (MqttException e) {
            String log = getTime() + " publish MqttException " + e.getMessage();
            Log.i(TAG, log);
            e.printStackTrace();
        }
    }

    public void getLocations() {
        Log.i(TAG, "Trying to get Locations");
        String getLocationMsg = "{\"getLocations\": true}";
        publish(getLocationMsg, PUBLISH_TOPIC.TOPIC_LOCATION_SEARCH.getTopic());
    }

    public String getTime() {
        SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss.SSS", Locale.GERMAN);
        return format.format(new Date());
    }

    public void iGetMessage(String topic, MqttMessage message) {
        if (SUBSCRIBE_TOPIC.TOPIC_LOCATION_SEARCH.getTopic().equals(topic)) {
            new Thread(new ClientReceiverRunnable_location_search(message.toString())).start();
        }
        if (SUBSCRIBE_TOPIC.TOPIC_LOCATION_FIND.getTopic().equals(topic)) {
            new Thread(new ClientReceiverRunnable_location_find(message.toString())).start();
        }
        if (SUBSCRIBE_TOPIC.TOPIC_FLOOR.getTopic().equals(topic)) {
            new Thread(new ClientReceiverRunnable_floor(message.toString())).start();
        }
        if (SUBSCRIBE_TOPIC.TOPIC_TRANSPORT.getTopic().equals(topic)) {
            new Thread(new ClientReceiverRunnable_transport(message.toString())).start();
        }
        if (SUBSCRIBE_TOPIC.TOPIC_ARRIVED.getTopic().equals(topic)) {
            new Thread(new ClientReceiverRunnable_arrived(message.toString())).start();
        }
    }
}
