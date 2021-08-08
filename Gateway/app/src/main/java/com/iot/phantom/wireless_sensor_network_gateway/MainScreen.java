package com.iot.phantom.wireless_sensor_network_gateway;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.json.JSONException;
import org.json.JSONObject;

import com.squareup.okhttp.Callback;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;

public class MainScreen extends AppCompatActivity implements SerialInputOutputManager.Listener, View.OnClickListener {
    public static final String EXTRA_MESSAGE = "com.iot.phantom.wireless_sensor_network_gateway.MESSAGE";
    public static String key = "<insert_key>";
    private View decorView;

    ImageView temp, light;
    String username;
    TextView hello_message;
    Button button_log_out, button_temp, button_light;
    GoogleSignInClient mGoogleSignInClient;
    MQTTService mqttService;

    UsbSerialPort port;
    private static final String ACTION_USB_PERMISSION = "com.android.recipes.USB_PERMISSION";
    private static final String INTENT_ACTION_GRANT_USB = BuildConfig.APPLICATION_ID + ".GRANT_USB";

    public void initUSBPort(){
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);

        if (availableDrivers.isEmpty()) {
            Log.d("UART", "UART is not available");

        }else {
            Log.d("UART", "UART is available");

            UsbSerialDriver driver = availableDrivers.get(0);
            UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
            if (connection == null) {

                PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(INTENT_ACTION_GRANT_USB), 0);
                manager.requestPermission(driver.getDevice(), usbPermissionIntent);

                manager.requestPermission(driver.getDevice(), PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0));

                return;
            } else {

                port = driver.getPorts().get(0);
                try {
                    Log.d("UART", "openned succesful");
                    port.open(connection);
                    port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

                    //port.write("ABC#".getBytes(), 1000);

                    SerialInputOutputManager usbIoManager = new SerialInputOutputManager(port, this);
                    Executors.newSingleThreadExecutor().submit(usbIoManager);

                } catch (Exception e) {
                    Log.d("UART", "There is error");
                }
            }
        }

    }

    private String buffer = "";
    @Override
    public void onNewData(byte[] data) {
        buffer += new String(data);
        Log.d("UART", "Received: " + new String(data));
        if (buffer.contains("#") && buffer.contains("!")) {
            try {
                int index_soc = buffer.indexOf("#");
                int index_eoc = buffer.indexOf("!");
                if (index_soc < index_eoc) {
                    String sentData = buffer.substring(index_soc + 1, index_eoc);
                    int colonIndex = sentData.indexOf(":");
                    String ID = sentData.substring(0, colonIndex);
                    String value = sentData.substring(colonIndex + 1, sentData.length());
                    if (ID.equals("TEMP")) {
                        button_temp.setText(value);
                    } else if (ID.equals("LIGHT")) {
                        button_light.setText(value);
                    }

                    Bitmap bitmap = BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher);
                    NotificationCompat.Builder builder = new NotificationCompat.Builder(this, Notification.CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_launcher_foreground)
                            .setLargeIcon(bitmap)
                            .setContentTitle(ID + " update")
                            .setContentText(ID + " = " + value)
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT);
                    NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
                    notificationManager.notify(getNotificationId(), builder.build());

                    sendDataMQTT(value, ID);
                }
            } catch (Exception e) {

            }
            buffer = "";
        }
    }

    @Override
    public void onRunError(Exception e) {

    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            decorView.setSystemUiVisibility(hideSystemBar());
        }
    }

    private int hideSystemBar() {
        return View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
    }

    @Override
    public void onClick(View view) {
        // Do something in response to button click
        switch (view.getId()) {
            case R.id.button_log_out:
                googleSignOut();
                finish();
                break;
            case R.id.buttonTemp:
                showGraph("TEMP");
                break;
            case R.id.buttonLight:
                showGraph("LIGHT");
                break;
        }
    }

    public void showGraph(String ID) {
        Intent intent = new Intent(this, GraphActivity.class);
        String message = ID;
        intent.putExtra(EXTRA_MESSAGE, message);
        startActivity(intent);
    }

    public final int getNotificationId() {
        return (int) new Date().getTime();
    }

    private void googleSignOut() {
        mGoogleSignInClient.signOut()
                .addOnCompleteListener(this, new OnCompleteListener<Void>() {
                    @Override
                    public void onComplete(@NonNull Task<Void> task) {
                        Toast.makeText(MainScreen.this, "Signed out successfully!", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void sendDataMQTT(String data, String ID){
        MqttMessage msg = new MqttMessage();
        msg.setId(1234);
        msg.setQos(0);
        msg.setRetained(true);

        byte[] b = data.getBytes(StandardCharsets.UTF_8);
        msg.setPayload(b);

        Log.d("ABC","Publish :" + msg);
        try {
            if (ID.equals("TEMP")) {
                mqttService.mqttAndroidClient.publish("tiviluson/feeds/iot-final.temperature-sensor", msg);
            } else if (ID.equals("LIGHT")) {
                mqttService.mqttAndroidClient.publish("tiviluson/feeds/iot-final.light-sensor", msg);
            }
        } catch (MqttException e){

        }
    }

    private void receiveLastDataFromServer(final String ID) {
        final String apiURL;
        if (ID.equals("TEMP")) {
            apiURL = "https://io.adafruit.com/api/v2/tiviluson/feeds/iot-final.temperature-sensor/data/last?x-aio-key=" + key;
        } else if (ID.equals("LIGHT")) {
            apiURL = "https://io.adafruit.com/api/v2/tiviluson/feeds/iot-final.light-sensor/data/last?x-aio-key=" + key;
        } else {
            apiURL = "none";
        }
        final OkHttpClient okHttpClient = new OkHttpClient();
        Request.Builder builder = new Request.Builder();
        Request request = builder.url(apiURL).build();
        try {
            okHttpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Request request, IOException e) {
                    if (ID.equals("TEMP") || ID.equals("LIGHT")) {
                        receiveLastDataFromServer(apiURL);
                    }
                }
                @Override
                public void onResponse(Response response) throws IOException {
                    String initDataReceived = response.body().string();
                    try {
                        JSONObject jsonObjectInitReceive = new JSONObject(initDataReceived);
                        String value = jsonObjectInitReceive.getString("value");
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (ID.equals("TEMP")) {
                                    button_temp.setText(value);
                                } else {
                                    button_light.setText(value);
                                }
                            }
                        });
                    } catch (JSONException e) {
                        Log.e("JSONException", "Error: " + e.toString());
                    }
                }
            });
        } catch (Exception e) {
            Log.d("Fail", "Get last data");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main_screen);

        temp = findViewById(R.id.imageViewTemp);
        temp.bringToFront();
        light = findViewById(R.id.imageViewLight);
        light.bringToFront();

        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .build();

        // Build a GoogleSignInClient with the options specified by gso.
        mGoogleSignInClient = GoogleSignIn.getClient(this, gso);

        GoogleSignInAccount acct = GoogleSignIn.getLastSignedInAccount(this);
        if (acct != null) {
            username = acct.getDisplayName();
            String personGivenName = acct.getGivenName();
            String personFamilyName = acct.getFamilyName();
            String personEmail = acct.getEmail();
            String personId = acct.getId();
            Uri personPhoto = acct.getPhotoUrl();
        } else {
            Intent intent = getIntent();
            username = intent.getStringExtra(MainActivity.EXTRA_MESSAGE);
        }

        initUSBPort();

        receiveLastDataFromServer("TEMP");
        receiveLastDataFromServer("LIGHT");

        mqttService = new MQTTService(this);
        mqttService.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {

            }

            @Override
            public void connectionLost(Throwable cause) {

            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {

            }
        });

        hello_message = findViewById(R.id.textViewHello);
        hello_message.setText("Welcome back " + username);

        decorView = getWindow().getDecorView();
        decorView.setOnSystemUiVisibilityChangeListener(new View.OnSystemUiVisibilityChangeListener() {
            @Override
            public void onSystemUiVisibilityChange(int visibility) {
                if (visibility == 0) {
                    hideSystemBar();
                }
            }
        });

        button_log_out = findViewById(R.id.button_log_out);
        button_log_out.setOnClickListener(this);
        button_temp = findViewById(R.id.buttonTemp);
        button_temp.setOnClickListener(this);
        button_light = findViewById(R.id.buttonLight);
        button_light.setOnClickListener(this);
    }
}
