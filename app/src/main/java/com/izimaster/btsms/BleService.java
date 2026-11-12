package com.izimaster.btsms;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Runs in the background and makes the TC52 visible over Bluetooth LE.
 * The laptop web page connects to it and exchanges small JSON messages.
 *
 * Message framing (both directions): each BLE packet = 1 flag byte + data.
 * Flag 1 = more packets follow, flag 0 = last packet of the message.
 */
public class BleService extends Service implements NoteStore.Listener {

    public static final UUID SERVICE_UUID = UUID.fromString("6f1d0001-8c4a-4b6e-9b2e-3a1c5e7d9f01");
    public static final UUID TX_UUID = UUID.fromString("6f1d0002-8c4a-4b6e-9b2e-3a1c5e7d9f01"); // TC52 -> laptop
    public static final UUID RX_UUID = UUID.fromString("6f1d0003-8c4a-4b6e-9b2e-3a1c5e7d9f01"); // laptop -> TC52
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final String CHANNEL_ID = "btsms";

    // ---- status shown in the app ----
    public interface StatusListener { void onStatus(String status); }

    public static volatile boolean running = false;
    private static volatile String status = "Stopped";
    private static volatile StatusListener statusListener;

    public static String getStatus() { return status; }

    public static void setStatusListener(StatusListener l) { statusListener = l; }

    // ---- per-connection state ----
    private static final class Client {
        int mtu = 23;
        boolean subscribed = false;
        final ByteArrayOutputStream message = new ByteArrayOutputStream();
        final ByteArrayOutputStream prepared = new ByteArrayOutputStream();
        BluetoothGatt rssiGatt;   // client link back to the laptop, used only to read signal strength
        Runnable rssiPoll;
    }

    private static final class Packet {
        final BluetoothDevice device;
        final byte[] data;

        Packet(BluetoothDevice d, byte[] b) { device = d; data = b; }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Client> clients = new HashMap<>();
    private final Map<String, BluetoothDevice> devices = new HashMap<>();
    private final ArrayDeque<Packet> queue = new ArrayDeque<>();
    private boolean sending = false;
    private int retries = 0;
    private final Runnable sendWatchdog = () -> { sending = false; sendNext(); };

    private BluetoothManager manager;
    private BluetoothGattServer server;
    private BluetoothGattCharacteristic tx;
    private BluetoothLeAdvertiser advertiser;
    private boolean advertisingWithName = true;
    private NoteStore store;

    private final BroadcastReceiver btStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            if (state == BluetoothAdapter.STATE_ON) {
                startBle();
            } else if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                stopBle();
                setStatus("Bluetooth is off");
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        store = NoteStore.get(this);
        store.addListener(this);
        Alarm.addListener(alarmListener);
        manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        startInForeground();
        registerReceiver(btStateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        startBle();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && Alarm.ACTION_STOP.equals(intent.getAction())) Alarm.stop();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        store.removeListener(this);
        Alarm.removeListener(alarmListener);
        Alarm.stop();
        try { unregisterReceiver(btStateReceiver); } catch (Exception ignored) { }
        stopBle();
        setStatus("Stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---------------------------------------------------------------- setup

    private void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "BTsms sharing", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("BTsms")
                .setContentText("Sharing notes over Bluetooth")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(1, n);
        }
    }

    private void startBle() {
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) { setStatus("No Bluetooth on this device"); return; }
        if (!adapter.isEnabled()) { setStatus("Bluetooth is off"); return; }
        if (server != null) return;
        try {
            server = manager.openGattServer(this, gattCallback);
            if (server == null) { setStatus("Could not start Bluetooth server"); return; }

            BluetoothGattService service = new BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
            tx = new BluetoothGattCharacteristic(TX_UUID,
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ);
            BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
            tx.addDescriptor(cccd);
            BluetoothGattCharacteristic rx = new BluetoothGattCharacteristic(RX_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE);
            service.addCharacteristic(tx);
            service.addCharacteristic(rx);
            server.addService(service); // advertising starts in onServiceAdded
            setStatus("Starting…");
        } catch (SecurityException e) {
            setStatus("Bluetooth permission missing");
        }
    }

    private void startAdvertising() {
        try {
            BluetoothAdapter adapter = manager.getAdapter();
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) { setStatus("This device cannot advertise BLE"); return; }
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                    .setConnectable(true)
                    .setTimeout(0)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                    .setIncludeDeviceName(false)
                    .build();
            AdvertiseData scanResponse = new AdvertiseData.Builder()
                    .setIncludeDeviceName(advertisingWithName)
                    .build();
            advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback);
        } catch (SecurityException e) {
            setStatus("Bluetooth permission missing");
        }
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            updateConnectedStatus();
        }

        @Override
        public void onStartFailure(int errorCode) {
            if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && advertisingWithName) {
                advertisingWithName = false; // device name too long, advertise without it
                main.post(BleService.this::startAdvertising);
                return;
            }
            if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) { updateConnectedStatus(); return; }
            setStatus("Advertising failed (code " + errorCode + ")");
        }
    };

    private void stopBle() {
        try {
            if (advertiser != null) advertiser.stopAdvertising(advertiseCallback);
        } catch (Exception ignored) { }
        advertiser = null;
        try {
            if (server != null) server.close();
        } catch (Exception ignored) { }
        server = null;
        for (Client c : clients.values()) closeRssiLink(c);
        clients.clear();
        devices.clear();
        queue.clear();
        sending = false;
    }

    // ---------------------------------------------------------------- GATT callbacks

    private final BluetoothGattServerCallback gattCallback = new BluetoothGattServerCallback() {
        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            main.post(() -> {
                if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising();
                else setStatus("Could not add Bluetooth service (" + status + ")");
            });
        }

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            main.post(() -> {
                String key = device.getAddress();
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    clients.put(key, new Client());
                    devices.put(key, device);
                    // Keep advertising while connected (lets the page also measure signal itself).
                    restartAdvertising();
                    main.postDelayed(() -> openRssiLink(device), 1500);
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    closeRssiLink(clients.get(key));
                    clients.remove(key);
                    devices.remove(key);
                    queue.removeIf(p -> p.device.getAddress().equals(key));
                    // Some phones stop advertising after a connection; make sure it is on again.
                    restartAdvertising();
                }
                updateConnectedStatus();
            });
        }

        @Override
        public void onMtuChanged(BluetoothDevice device, int mtu) {
            main.post(() -> {
                Client c = client(device);
                c.mtu = mtu;
            });
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic) {
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, new byte[0]);
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset,
                                            BluetoothGattDescriptor descriptor) {
            Client c = clients.get(device.getAddress());
            byte[] v = (c != null && c.subscribed)
                    ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, v);
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor,
                                             boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, value);
            main.post(() -> {
                Client c = client(device);
                c.subscribed = value != null && value.length > 0 && (value[0] & 0x01) != 0;
            });
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {
            if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, value);
            if (!RX_UUID.equals(characteristic.getUuid()) || value == null) return;
            final byte[] copy = Arrays.copyOf(value, value.length);
            main.post(() -> {
                Client c = client(device);
                if (preparedWrite) {
                    c.prepared.write(copy, 0, copy.length);
                } else {
                    onPacket(device, c, copy);
                }
            });
        }

        @Override
        public void onExecuteWrite(BluetoothDevice device, int requestId, boolean execute) {
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, null);
            main.post(() -> {
                Client c = client(device);
                byte[] all = c.prepared.toByteArray();
                c.prepared.reset();
                if (execute && all.length > 0) onPacket(device, c, all);
            });
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            main.post(() -> {
                main.removeCallbacks(sendWatchdog);
                sending = false;
                sendNext();
            });
        }
    };

    private Client client(BluetoothDevice device) {
        String key = device.getAddress();
        Client c = clients.get(key);
        if (c == null) {
            c = new Client();
            clients.put(key, c);
            devices.put(key, device);
        }
        return c;
    }

    private void respond(BluetoothDevice device, int requestId, int status, byte[] value) {
        try {
            if (server != null) server.sendResponse(device, requestId, status, 0, value);
        } catch (Exception ignored) { }
    }

    private void restartAdvertising() {
        try {
            if (advertiser != null) advertiser.stopAdvertising(advertiseCallback);
        } catch (Exception ignored) { }
        if (server != null) startAdvertising();
    }

    // ---------------------------------------------------------------- messages in

    private void onPacket(BluetoothDevice device, Client c, byte[] packet) {
        if (packet.length == 0) return;
        c.message.write(packet, 1, packet.length - 1);
        if (packet[0] != 0) return; // more to come
        String json = new String(c.message.toByteArray(), StandardCharsets.UTF_8);
        c.message.reset();
        try {
            JSONObject msg = new JSONObject(json);
            String t = msg.optString("t");
            switch (t) {
                case "sync":
                    sendNotes(device);
                    break;
                case "add":
                    store.add(msg.optString("text"));
                    break;
                case "edit":
                    store.edit(msg.optString("id"), msg.optString("text"));
                    break;
                case "del":
                    store.delete(msg.optString("id"));
                    break;
                case "find":
                    Alarm.start(this);
                    break;
                case "stopfind":
                    Alarm.stop();
                    break;
                default:
                    break;
            }
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- messages out

    @Override
    public void onNotesChanged() {
        for (Map.Entry<String, Client> e : clients.entrySet()) {
            if (e.getValue().subscribed) {
                BluetoothDevice d = devices.get(e.getKey());
                if (d != null) sendNotes(d);
            }
        }
    }

    private void sendNotes(BluetoothDevice device) {
        Client c = client(device);
        try {
            JSONObject msg = new JSONObject();
            msg.put("t", "notes");
            msg.put("mtu", c.mtu);
            msg.put("alarm", Alarm.isRinging());
            msg.put("notes", store.toJson());
            sendJson(device, msg);
        } catch (Exception ignored) {
        }
    }

    private void sendAlarmState(boolean on) {
        for (Map.Entry<String, Client> e : clients.entrySet()) {
            if (!e.getValue().subscribed) continue;
            BluetoothDevice d = devices.get(e.getKey());
            if (d == null) continue;
            try {
                JSONObject msg = new JSONObject();
                msg.put("t", "alarm");
                msg.put("on", on);
                sendJson(d, msg);
            } catch (Exception ignored) { }
        }
    }

    private void sendJson(BluetoothDevice device, JSONObject msg) {
        Client c = client(device);
        byte[] bytes = msg.toString().getBytes(StandardCharsets.UTF_8);
        int chunk = Math.max(19, Math.min(c.mtu - 3, 500) - 1);
        for (int pos = 0; pos < bytes.length; pos += chunk) {
            int len = Math.min(chunk, bytes.length - pos);
            byte[] p = new byte[len + 1];
            p[0] = (byte) (pos + len >= bytes.length ? 0 : 1);
            System.arraycopy(bytes, pos, p, 1, len);
            queue.add(new Packet(device, p));
        }
        sendNext();
    }

    private final Alarm.Listener alarmListener = this::sendAlarmState;

    @SuppressWarnings("deprecation")
    private void sendNext() {
        if (sending || server == null || tx == null) return;
        Packet p = queue.poll();
        if (p == null) return;
        boolean ok;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ok = server.notifyCharacteristicChanged(p.device, tx, false, p.data) == 0;
            } else {
                tx.setValue(p.data);
                ok = server.notifyCharacteristicChanged(p.device, tx, false);
            }
        } catch (Exception e) {
            ok = false;
        }
        if (ok) {
            sending = true;
            main.postDelayed(sendWatchdog, 1000);
        } else if (++retries < 60) {
            // busy: try again shortly
            queue.addFirst(p);
            main.postDelayed(this::sendNext, 30);
            return;
        } else {
            // give up on this packet (laptop probably gone)
            main.post(this::sendNext);
        }
        retries = 0;
    }

    // ---------------------------------------------------------------- signal strength
    // A GATT server cannot read RSSI, so we also open a client connection over the same
    // Bluetooth link to the laptop and poll readRemoteRssi() once a second.

    private void openRssiLink(BluetoothDevice device) {
        Client c = clients.get(device.getAddress());
        if (c == null || c.rssiGatt != null || server == null) return;
        try {
            c.rssiGatt = device.connectGatt(this, false, rssiCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) {
            c.rssiGatt = null;
        }
    }

    private void closeRssiLink(Client c) {
        if (c == null) return;
        if (c.rssiPoll != null) main.removeCallbacks(c.rssiPoll);
        c.rssiPoll = null;
        try { if (c.rssiGatt != null) c.rssiGatt.close(); } catch (Exception ignored) { }
        c.rssiGatt = null;
    }

    private final BluetoothGattCallback rssiCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            final BluetoothDevice device = gatt.getDevice();
            main.post(() -> {
                Client c = clients.get(device.getAddress());
                if (c == null || c.rssiGatt != gatt) return;
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (c.rssiPoll != null) main.removeCallbacks(c.rssiPoll);
                    c.rssiPoll = new Runnable() {
                        @Override
                        public void run() {
                            try { gatt.readRemoteRssi(); } catch (Exception ignored) { }
                            main.postDelayed(this, 1000);
                        }
                    };
                    main.post(c.rssiPoll);
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // client side dropped but laptop may still be connected: try again later
                    closeRssiLink(c);
                    main.postDelayed(() -> openRssiLink(device), 5000);
                }
            });
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) return;
            final BluetoothDevice device = gatt.getDevice();
            main.post(() -> sendRssi(device, rssi));
        }
    };

    private void sendRssi(BluetoothDevice device, int rssi) {
        Client c = clients.get(device.getAddress());
        if (c == null || !c.subscribed || queue.size() > 20) return;
        try {
            JSONObject msg = new JSONObject();
            msg.put("t", "rssi");
            msg.put("v", rssi);
            sendJson(device, msg);
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- status

    private void updateConnectedStatus() {
        int n = clients.size();
        if (server == null) return;
        setStatus(n == 0 ? "Waiting for laptop…" : "Laptop connected" + (n > 1 ? " (" + n + ")" : ""));
    }

    private void setStatus(String s) {
        status = s;
        main.post(() -> {
            StatusListener l = statusListener;
            if (l != null) l.onStatus(s);
        });
    }
}
