package com.example.appbt;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.SmsManager;
import android.text.InputType;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;

import com.google.firebase.FirebaseApp;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_ENABLE_BT = 200;

    private FusedLocationProviderClient fusedLocationClient;
    private TextView statusText;

    private ArrayList<Contact> contacts = new ArrayList<>();
    private ContactAdapter adapter;
    private SharedPreferences preferences;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra("status");
            Log.i(TAG, "Estado recibido: " + status);
            if (status != null) {
                runOnUiThread(() -> statusText.setText("Estado: " + status));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        FirebaseApp.initializeApp(this);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        statusText = findViewById(R.id.statusText);
        preferences = getSharedPreferences("ContactsPrefs", MODE_PRIVATE);

        checkAndEnableBluetooth();
        connectIfPaired();
        showBatteryOptimizationDialog();
        loadContacts();

        RecyclerView recyclerView = findViewById(R.id.numbersRecyclerView);
        adapter = new ContactAdapter(contacts, new ContactAdapter.OnItemActionListener() {
            @Override
            public void onEdit(int position) {
                showEditContactDialog(position);
            }

            @Override
            public void onDelete(int position) {
                showDeleteConfirmationDialog(position);
            }
        });

        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        Button addNumberBtn = findViewById(R.id.addNumberBtn);
        addNumberBtn.setOnClickListener(v -> showAddContactDialog());

        Button testSmsBtn = findViewById(R.id.testSmsBtn);
        testSmsBtn.setOnClickListener(v -> sendTestSMS());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(new Intent(this, BluetoothForegroundService.class));
        } else {
            startService(new Intent(this, BluetoothForegroundService.class));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter("BT_SERVICE_STATUS");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_EXPORTED);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(statusReceiver);
    }

    private void checkAndEnableBluetooth() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter != null && !bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BT) {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                connectIfPaired();
            } else {
                Toast.makeText(this, "Bluetooth es requerido para la conexión", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void connectIfPaired() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
            Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
            for (BluetoothDevice device : pairedDevices) {
                if (device.getAddress().equals("08:A6:F7:47:01:62")) {
                    Log.i(TAG, "ESP32 emparejado encontrado: " + device.getName());
                    Intent serviceIntent = new Intent(this, BluetoothForegroundService.class);
                    serviceIntent.putExtra("device_address", device.getAddress());
                    ContextCompat.startForegroundService(this, serviceIntent);
                    return;
                }
            }
            Log.i(TAG, "ESP32 no emparejado. Iniciando servicio sin conexión directa.");
            Intent serviceIntent = new Intent(this, BluetoothForegroundService.class);
            ContextCompat.startForegroundService(this, serviceIntent);
        }
    }

    private void showBatteryOptimizationDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Optimización de batería")
                .setMessage("Para que la app funcione correctamente en segundo plano, por favor exclúyela de la optimización de batería.")
                .setPositiveButton("Configuración", (dialog, which) -> openBatteryOptimizationSettings())
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void openBatteryOptimizationSettings() {
        Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        startActivity(intent);
    }

    private void loadContacts() {
        String contactsString = preferences.getString("contacts", "");
        contacts.clear();
        if (!contactsString.isEmpty()) {
            String[] contactArray = contactsString.split(";");
            for (String contact : contactArray) {
                String[] parts = contact.split(",");
                if (parts.length == 2) {
                    contacts.add(new Contact(parts[0], parts[1]));
                }
            }
        }
    }

    private void saveContacts() {
        StringBuilder sb = new StringBuilder();
        for (Contact contact : contacts) {
            sb.append(contact.getName()).append(",").append(contact.getNumber()).append(";");
        }
        preferences.edit().putString("contacts", sb.toString()).apply();
    }

    private void showAddContactDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Agregar Contacto");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        final EditText nameInput = new EditText(this);
        nameInput.setHint("Nombre");
        layout.addView(nameInput);

        final EditText numberInput = new EditText(this);
        numberInput.setHint("Número de teléfono");
        numberInput.setInputType(InputType.TYPE_CLASS_PHONE);
        layout.addView(numberInput);

        builder.setView(layout);

        builder.setPositiveButton("Agregar", (dialog, which) -> {
            String name = nameInput.getText().toString().trim();
            String number = numberInput.getText().toString().trim();

            if (!name.isEmpty() && !number.isEmpty()) {
                contacts.add(new Contact(name, number));
                saveContacts();
                adapter.notifyDataSetChanged();
            }
        });

        builder.setNegativeButton("Cancelar", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void showEditContactDialog(int position) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Editar Contacto");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        final EditText nameInput = new EditText(this);
        nameInput.setText(contacts.get(position).getName());
        layout.addView(nameInput);

        final EditText numberInput = new EditText(this);
        numberInput.setText(contacts.get(position).getNumber());
        numberInput.setInputType(InputType.TYPE_CLASS_PHONE);
        layout.addView(numberInput);

        builder.setView(layout);

        builder.setPositiveButton("Guardar", (dialog, which) -> {
            String name = nameInput.getText().toString().trim();
            String number = numberInput.getText().toString().trim();

            if (!name.isEmpty() && !number.isEmpty()) {
                contacts.set(position, new Contact(name, number));
                saveContacts();
                adapter.notifyDataSetChanged();
            }
        });

        builder.setNegativeButton("Cancelar", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void showDeleteConfirmationDialog(int position) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Eliminar Contacto")
                .setMessage("¿Deseas eliminar este contacto?")
                .setPositiveButton("Eliminar", (dialog, which) -> {
                    contacts.remove(position);
                    saveContacts();
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void getLocationAsync(LocationResultCallback callback) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permiso de ubicación no concedido", Toast.LENGTH_SHORT).show();
            return;
        }

        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null) {
                callback.onLocationResult(location);
            } else {
                LocationRequest request = LocationRequest.create()
                        .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                        .setInterval(1000)
                        .setFastestInterval(500);

                LocationCallback tempCallback = new LocationCallback() {
                    @Override
                    public void onLocationResult(LocationResult locationResult) {
                        fusedLocationClient.removeLocationUpdates(this);
                        callback.onLocationResult(locationResult.getLastLocation());
                    }
                };

                fusedLocationClient.requestLocationUpdates(request, tempCallback, Looper.getMainLooper());
            }
        });
    }

    private interface LocationResultCallback {
        void onLocationResult(Location location);
    }

    private void sendTestSMS() {
        loadContacts();
        getLocationAsync(location -> {
            String locationInfo = "Ubicación no disponible";
            if (location != null) {
                locationInfo = String.format(Locale.US,
                        "Ubicación: https://maps.google.com/?q=%.6f,%.6f\nPrecisión: %.0f m",
                        location.getLatitude(), location.getLongitude(), location.getAccuracy());
            }

            String finalMessage = "🚨 MENSAJE DE PRUEBA 🚨\n\n" + locationInfo;

            try {
                SmsManager smsManager = SmsManager.getDefault();
                for (Contact contact : contacts) {
                    ArrayList<String> parts = smsManager.divideMessage(finalMessage);
                    smsManager.sendMultipartTextMessage(contact.getNumber(), null, parts, null, null);
                }
                Toast.makeText(this, "SMS de prueba enviado", Toast.LENGTH_SHORT).show();
                guardarMensajeEnFirestore("PRUEBA", finalMessage, location.getLatitude(), location.getLongitude());
            } catch (Exception e) {
                Toast.makeText(this, "Error enviando SMS: " + e.getMessage(), Toast.LENGTH_LONG).show();
                Log.e(TAG, "Error enviando SMS", e);
            }
        });
    }

    private void guardarMensajeEnFirestore(String tipo, String mensaje, double lat, double lon) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        Map<String, Object> datos = new HashMap<>();
        datos.put("tipo", tipo);
        datos.put("mensaje", mensaje);
        datos.put("fecha", System.currentTimeMillis());
        datos.put("latitud", lat);
        datos.put("longitud", lon);

        db.collection("ubicacion").add(datos)
                .addOnSuccessListener(documentReference -> {
                    Log.d("FIREBASE", "Mensaje prueba guardado con ID: " + documentReference.getId());
                })
                .addOnFailureListener(e -> {
                    Log.e("FIREBASE", "Error al guardar mensaje de prueba", e);
                });
    }
}