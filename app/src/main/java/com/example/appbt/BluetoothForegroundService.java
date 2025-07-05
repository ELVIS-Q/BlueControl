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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;
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
        createNotificationChannel();
        btAdapter = BluetoothAdapter.getDefaultAdapter();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        contacts = loadContacts();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (btAdapter == null) {
            Log.e(TAG, "Bluetooth no soportado");
            stopSelf();
            return START_NOT_STICKY;
        }

        String deviceAddress = null;
        if (intent != null && intent.hasExtra("device_address")) {
            deviceAddress = intent.getStringExtra("device_address");
        }

        if (deviceAddress != null) {
            BluetoothDevice device = btAdapter.getRemoteDevice(deviceAddress);
            Log.i(TAG, "Conectando directamente al ESP32 emparejado: " + deviceAddress);
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
                        Log.i(TAG, "ESP32 detectado automáticamente: " + device.getAddress());
                        if (btAdapter.isDiscovering()) {
                            btAdapter.cancelDiscovery();
                            Log.i(TAG, "Discovery cancelado para conectar");
                            try {
                                Thread.sleep(500);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                        if (!isConnected) {
                            connectToESP32(device);
                        }
                    }
                }
            }
        };
        registerReceiver(bluetoothReceiver, filter);

        if (hasPermission(android.Manifest.permission.BLUETOOTH_SCAN)) {
            if (btAdapter.isDiscovering()) {
                btAdapter.cancelDiscovery();
            }
            btAdapter.startDiscovery();
            Log.i(TAG, "Discovery iniciado para detectar ESP32");
            startForeground(1, buildNotification("Buscando ESP32..."));
            sendStatusBroadcast("Buscando ESP32...");
        } else {
            Log.w(TAG, "Permiso BLUETOOTH_SCAN no concedido, no inicia discovery");
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
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void connectToESP32(BluetoothDevice device) {
        new Thread(() -> {
            try {
                if (!hasPermission(android.Manifest.permission.BLUETOOTH_CONNECT)) {
                    Log.w(TAG, "Permiso BLUETOOTH_CONNECT no concedido");
                    sendStatusBroadcast("Permiso BLUETOOTH_CONNECT denegado");
                    return;
                }
                Log.i(TAG, "Intentando conectar a ESP32...");
                btSocket = device.createRfcommSocketToServiceRecord(BT_UUID);
                btSocket.connect();
                btOutputStream = btSocket.getOutputStream();
                isConnected = true;

                startForeground(1, buildNotification("Conectado a ESP32"));
                sendStatusBroadcast("Conectado a ESP32 ✅");

                listenForBluetoothMessages();

            } catch (IOException e) {
                Log.e(TAG, "Error conexión ESP32", e);
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
                Log.i(TAG, "Mensaje enviado al ESP32: " + message);
            } else {
                Log.w(TAG, "No conectado, no se pudo enviar mensaje al ESP32");
            }
        } catch (IOException e) {
            Log.e(TAG, "Error enviando mensaje al ESP32", e);
        }
    }


    private void listenForBluetoothMessages() {
        new Thread(() -> {
            try (InputStream inputStream = btSocket.getInputStream()) {
                StringBuilder messageBuilder = new StringBuilder();
                int byteRead;

                while ((byteRead = inputStream.read()) != -1) {
                    char readChar = (char) byteRead;

                    // Log detallado de cada carácter recibido
                    Log.d(TAG, "Caracter recibido: [" + readChar + "] (" + (int) readChar + ")");

                    if (readChar == '\n' || readChar == '\r') {
                        if (messageBuilder.length() > 0) {
                            String message = messageBuilder.toString().trim();
                            Log.d(TAG, "Mensaje completo recibido: [" + message + "]");

                            // Limpieza de posibles residuos
                            message = message.replace("\r", "").replace("\n", "").trim();

                            if (message.equalsIgnoreCase("SEND_SMS")) {
                                Log.d(TAG, "Comando SEND_SMS detectado, enviando SMS...");
                                sendStatusBroadcast("Comando SEND_SMS recibido");
                                getLocationAsync(location -> {
                                    sendEmergencySMS(location);
                                    sendBluetoothMessage("SMS_ENVIADO\n"); // Confirmación al ESP32
                                });
                            } else {
                                Log.d(TAG, "Comando no reconocido: " + message);
                            }
                            messageBuilder.setLength(0);
                        }
                    } else {
                        messageBuilder.append(readChar);
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Conexión perdida", e);
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
                    if (!btAdapter.isDiscovering()) {
                        btAdapter.startDiscovery();
                        sendStatusBroadcast("Buscando ESP32...");
                        Log.i(TAG, "Reiniciando discovery para reconectar ESP32");
                    }
                }
                isReconnecting = false;
            } catch (InterruptedException e) {
                Log.e(TAG, "Error en restartDiscoveryWithDelay", e);
                isReconnecting = false;
            }
        }).start();
    }

    private void closeBluetoothSocketSafely() {
        try {
            if (btSocket != null) {
                btSocket.close();
                btSocket = null;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error cerrando socket", e);
        }
    }

    private void getLocationAsync(LocationResultCallback callback) {
        if (!hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) &&
                !hasPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)) {
            Log.w(TAG, "Permiso de ubicación no concedido");
            return;
        }

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

    private void sendEmergencySMS(Location location) {
        contacts = loadContacts();

        String locationInfo = "Ubicación no disponible";
        if (location != null) {
            locationInfo = String.format(Locale.US,
                    "Ubicación: https://maps.google.com/?q=%.6f,%.6f\nPrecisión: %.0f m",
                    location.getLatitude(), location.getLongitude(), location.getAccuracy());
        }

        String finalMessage = "🚨 EMERGENCIA DETECTADA 🚨\n\n" + locationInfo;

        try {
            SmsManager smsManager = SmsManager.getDefault();
            for (Contact contact : contacts) {
                ArrayList<String> parts = smsManager.divideMessage(finalMessage);
                smsManager.sendMultipartTextMessage(contact.getNumber(), null, parts, null, null);
            }
            Log.i(TAG, "SMS enviado a contactos");
            sendStatusBroadcast("SMS enviado");
        } catch (Exception e) {
            Log.e(TAG, "Error enviando SMS", e);
            sendStatusBroadcast("Error enviando SMS");
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
        Log.i(TAG, "Enviando broadcast con estado: " + status);
        Intent intent = new Intent("BT_SERVICE_STATUS");
        intent.putExtra("status", status);
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (btSocket != null) btSocket.close();
        } catch (IOException ignored) {
        }
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
