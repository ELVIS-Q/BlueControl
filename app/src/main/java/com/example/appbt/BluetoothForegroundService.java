package com.example.appbt;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class BluetoothForegroundService extends Service {

    private static final String TAG = "BT_SERVICE";
    private static final String ESP32_MAC_E = "08:A6:F7:47:01:62";
    private static final UUID BT_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String CHANNEL_ID = "BT_SMS_CHANNEL";

    private BluetoothAdapter btAdapter;
    private BluetoothSocket btSocket;
    private OutputStream btOutputStream;
    private boolean isConnected = false;
    private boolean isReconnecting = false;

    private FusedLocationProviderClient fusedLocationClient;
    private BroadcastReceiver bluetoothReceiver;
    private ArrayList<Contact> contacts;

    @Override
    public void onCreate() {
        super.onCreate();
        FirebaseApp.initializeApp(this);
        createNotificationChannel();
        btAdapter = BluetoothAdapter.getDefaultAdapter();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        contacts = loadContacts();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (btAdapter == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String deviceAddress = intent != null ? intent.getStringExtra("device_address") : null;

        if (deviceAddress != null) {
            BluetoothDevice device = btAdapter.getRemoteDevice(deviceAddress);
            connectToESP32(device);
            startForeground(1, buildNotification("Conectando al ESP32..."));
            sendStatusBroadcast("Conectando al ESP32...");
        } else {
            iniciarDiscoveryNormal();
        }

        return START_STICKY;
    }

    private void iniciarDiscoveryNormal() {
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        bluetoothReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device != null && ESP32_MAC_E.equals(device.getAddress())) {
                        if (btAdapter.isDiscovering()) {
                            btAdapter.cancelDiscovery();
                            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                        }
                        if (!isConnected) connectToESP32(device);
                    }
                }
            }
        };
        registerReceiver(bluetoothReceiver, filter);

        if (hasPermission(android.Manifest.permission.BLUETOOTH_SCAN)) {
            btAdapter.startDiscovery();
            startForeground(1, buildNotification("Buscando ESP32..."));
            sendStatusBroadcast("Buscando ESP32...");
        } else {
            sendStatusBroadcast("Falta permiso BLUETOOTH_SCAN");
            stopSelf();
        }
    }

    private Notification buildNotification(String contentText) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Servicio Bluetooth activo")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_alert)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Servicio Bluetooth SMS", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void connectToESP32(BluetoothDevice device) {
        new Thread(() -> {
            try {
                if (!hasPermission(android.Manifest.permission.BLUETOOTH_CONNECT)) {
                    sendStatusBroadcast("Permiso BLUETOOTH_CONNECT denegado");
                    return;
                }
                btSocket = device.createRfcommSocketToServiceRecord(BT_UUID);
                btSocket.connect();
                btOutputStream = btSocket.getOutputStream();
                isConnected = true;

                startForeground(1, buildNotification("Conectado a ESP32"));
                sendStatusBroadcast("Conectado a ESP32 ✅");

                listenForBluetoothMessages();
            } catch (IOException e) {
                sendStatusBroadcast("Error conexión ESP32");
                isConnected = false;
                restartDiscoveryWithDelay();
            }
        }).start();
    }

    private void sendBluetoothMessage(String message) {
        try {
            if (btOutputStream != null && isConnected) {
                btOutputStream.write(message.getBytes());
            }
        } catch (IOException ignored) {}
    }

    private void listenForBluetoothMessages() {
        new Thread(() -> {
            try (InputStream inputStream = btSocket.getInputStream()) {
                StringBuilder messageBuilder = new StringBuilder();
                int byteRead;

                while ((byteRead = inputStream.read()) != -1) {
                    char readChar = (char) byteRead;

                    if (readChar == '\n' || readChar == '\r') {
                        if (messageBuilder.length() > 0) {
                            String message = messageBuilder.toString().trim();

                            if (message.equalsIgnoreCase("SEND_SMS")) {
                                sendStatusBroadcast("Comando SEND_SMS recibido");
                                getLocationAsync(location -> {
                                    sendEmergencySMS(location);
                                    sendBluetoothMessage("SMS_ENVIADO\n");
                                });
                            }
                            messageBuilder.setLength(0);
                        }
                    } else {
                        messageBuilder.append(readChar);
                    }
                }
            } catch (IOException e) {
                sendStatusBroadcast("Conexión perdida");
                isConnected = false;
                restartDiscoveryWithDelay();
            }
        }).start();
    }

    private void restartDiscoveryWithDelay() {
        if (isReconnecting) return;
        isReconnecting = true;

        new Thread(() -> {
            try {
                Thread.sleep(7000);
                closeBluetoothSocketSafely();
                if (hasPermission(android.Manifest.permission.BLUETOOTH_SCAN)) {
                    btAdapter.startDiscovery();
                    sendStatusBroadcast("Buscando ESP32...");
                }
            } catch (InterruptedException ignored) {}
            isReconnecting = false;
        }).start();
    }

    private void closeBluetoothSocketSafely() {
        try {
            if (btSocket != null) {
                btSocket.close();
                btSocket = null;
            }
        } catch (IOException ignored) {}
    }

    private void getLocationAsync(LocationResultCallback callback) {
        if (!hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) &&
                !hasPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)) return;

        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null) {
                callback.onLocationResult(location);
            } else {
                LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).build();
                LocationCallback tempCallback = new LocationCallback() {
                    @Override
                    public void onLocationResult(LocationResult locationResult) {
                        fusedLocationClient.removeLocationUpdates(this);
                        callback.onLocationResult(locationResult.getLastLocation());
                    }
                };
                fusedLocationClient.requestLocationUpdates(request, tempCallback, null);
            }
        });
    }

    // ✅ MÉTODO CORREGIDO PARA GUARDAR UBICACIÓN COMO ARRAY DENTRO DEL DOCUMENTO DEL USUARIO
    private void sendEmergencySMS(Location location) {
        contacts = loadContacts();

        String mensajeFinal = "Ubicación no disponible";
        double lat = 0, lon = 0;
        float accuracy = 0;

        if (location != null) {
            lat = location.getLatitude();
            lon = location.getLongitude();
            accuracy = location.getAccuracy();

            mensajeFinal = String.format(Locale.US,
                    "🚨 MENSAJE DE PRUEBA 🚨 Ubicación: https://maps.google.com/?q=%.6f,%.6f\nPrecisión: %.0f m",
                    lat, lon, accuracy);
        }

        try {
            SmsManager smsManager = SmsManager.getDefault();
            for (Contact contact : contacts) {
                ArrayList<String> parts = smsManager.divideMessage(mensajeFinal);
                smsManager.sendMultipartTextMessage(contact.getNumber(), null, parts, null, null);
            }

            String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
            FirebaseFirestore firestore = FirebaseFirestore.getInstance();

            Map<String, Object> ubicacionData = new HashMap<>();
            ubicacionData.put("fecha", System.currentTimeMillis());
            ubicacionData.put("latitud", lat);
            ubicacionData.put("longitud", lon);
            ubicacionData.put("mensaje", mensajeFinal);
            ubicacionData.put("tipo", "PRUEBA");

            firestore.collection("Usuarios").document(userId)
                    .update("Ubicación", FieldValue.arrayUnion(ubicacionData))
                    .addOnSuccessListener(aVoid -> Log.d(TAG, "Ubicación agregada correctamente"))
                    .addOnFailureListener(e -> Log.e(TAG, "Error al guardar ubicación", e));

            sendStatusBroadcast("SMS enviado");

        } catch (Exception e) {
            sendStatusBroadcast("SMS enviado");
        }
    }

    private ArrayList<Contact> loadContacts() {
        ArrayList<Contact> loadedContacts = new ArrayList<>();
        SharedPreferences preferences = getSharedPreferences("ContactsPrefs", MODE_PRIVATE);
        String contactsString = preferences.getString("contacts", "");
        if (!contactsString.isEmpty()) {
            String[] contactArray = contactsString.split(";");
            for (String contactStr : contactArray) {
                String[] parts = contactStr.split(",");
                if (parts.length == 2) {
                    loadedContacts.add(new Contact(parts[0], parts[1]));
                }
            }
        }
        return loadedContacts;
    }

    private void sendStatusBroadcast(String status) {
        Intent intent = new Intent("BT_SERVICE_STATUS");
        intent.putExtra("status", status);
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (btSocket != null) btSocket.close();
        } catch (IOException ignored) {}
        if (bluetoothReceiver != null) unregisterReceiver(bluetoothReceiver);
        sendStatusBroadcast("Servicio detenido");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private interface LocationResultCallback {
        void onLocationResult(Location location);
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }
}
