package com.android.bluetooth.sap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetooth;
import android.bluetooth.IBluetoothSap;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.BluetoothSap;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import com.android.bluetooth.R;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.btservice.ProfileService.IProfileServiceBinder;
import com.android.bluetooth.sdp.SdpManager;

@TargetApi(Build.VERSION_CODES.ECLAIR)
public class SapService extends ProfileService {

    private static final String SDP_SAP_SERVICE_NAME = "SIM Access";
    private static final int SDP_SAP_VERSION = 0x0102;
    private static final String TAG = "SapService";
    public static final boolean DEBUG = true;
    public static final boolean VERBOSE = true;
    public static final boolean PTS_TEST = true;

    /* Message ID's */
    private static final int START_LISTENER = 1;
    private static final int USER_TIMEOUT = 2;
    private static final int SHUTDOWN = 3;

    public static final int MSG_SERVERSESSION_CLOSE = 5000;
    public static final int MSG_SESSION_ESTABLISHED = 5001;
    public static final int MSG_SESSION_DISCONNECTED = 5002;

    /* Intent indicating timeout for user confirmation. */
    public static final String USER_CONFIRM_TIMEOUT_ACTION =
            "com.android.bluetooth.sap.USER_CONFIRM_TIMEOUT";
    private static final int USER_CONFIRM_TIMEOUT_VALUE = 25000;

    private PowerManager.WakeLock mWakeLock = null;
    private BluetoothAdapter mAdapter;
    private SocketAcceptThread mAcceptThread = null;
    private BluetoothServerSocket mServerSocket = null;
    private int mSdpHandle = -1;
    private BluetoothSocket mConnSocket = null;
    private BluetoothDevice mRemoteDevice = null;
    private static String sRemoteDeviceName = null;
    private volatile boolean mInterrupted;
    private int mState;
    private SapServer mSapServer = null;
    private AlarmManager mAlarmManager = null;
    private boolean mRemoveTimeoutMsg = false;

    private boolean mIsWaitingAuthorization = false;

    // package and class name to which we send intent to check message access access permission
    private static final String ACCESS_AUTHORITY_PACKAGE = "com.android.settings";
    private static final String ACCESS_AUTHORITY_CLASS =
        "com.android.settings.bluetooth.BluetoothPermissionRequest";

    private static final ParcelUuid[] SAP_UUIDS = {
        BluetoothUuid.SAP,
    };


    public SapService() {
        mState = BluetoothSap.STATE_DISCONNECTED;
    }

    private void startRfcommSocketListener() {
        if (VERBOSE) Log.v(TAG, "Sap Service startRfcommSocketListener");

        if (mAcceptThread == null) {
            mAcceptThread = new SocketAcceptThread();
            mAcceptThread.setName("SapAcceptThread");
            mAcceptThread.start();
        }
    }

    private final boolean initSocket() {
        if (VERBOSE) Log.v(TAG, "Sap Service initSocket");

        boolean initSocketOK = false;
        final int CREATE_RETRY_TIME = 10;

        // It's possible that create will fail in some cases. retry for 10 times
        for (int i = 0; i < CREATE_RETRY_TIME && !mInterrupted; i++) {
            initSocketOK = true;
            try {
                // It is mandatory for MSE to support initiation of bonding and encryption.
                // TODO: Consider reusing the mServerSocket - it is indented to be reused
                //       for multiple connections.
                mServerSocket = mAdapter.listenUsingRfcommOn(
                        BluetoothAdapter.SOCKET_CHANNEL_AUTO_STATIC_NO_SDP, true);
                if (mSdpHandle >= 0) {
                    SdpManager.getDefaultManager().removeSdpRecord(mSdpHandle);
                    if (VERBOSE) Log.d(TAG, "Removing SDP record");
                }
                mSdpHandle = SdpManager.getDefaultManager().createSapsRecord(SDP_SAP_SERVICE_NAME,
                        mServerSocket.getChannel(), SDP_SAP_VERSION);
            } catch (IOException e) {
                Log.e(TAG, "Error create RfcommServerSocket ", e);
                initSocketOK = false;
            }

            if (!initSocketOK) {
                // Need to break out of this loop if BT is being turned off.
                if (mAdapter == null) break;
                int state = mAdapter.getState();
                if ((state != BluetoothAdapter.STATE_TURNING_ON) &&
                    (state != BluetoothAdapter.STATE_ON)) {
                    Log.w(TAG, "initServerSocket failed as BT is (being) turned off");
                    break;
                }
                try {
                    if (VERBOSE) Log.v(TAG, "wait 300 ms");
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Log.e(TAG, "socketAcceptThread thread was interrupted (3)", e);
                }
            } else {
                break;
            }
        }
        if (mInterrupted) {
            initSocketOK = false;
            // close server socket to avoid resource leakage
            closeServerSocket();
        }

        if (initSocketOK) {
            if (VERBOSE) Log.v(TAG, "Succeed to create listening socket ");

        } else {
            Log.e(TAG, "Error to create listening socket after " + CREATE_RETRY_TIME + " try");
        }
        return initSocketOK;
    }

    private final synchronized void closeServerSocket() {
        // exit SocketAcceptThread early
        if (mServerSocket != null) {
            try {
                // this will cause mServerSocket.accept() return early with IOException
                mServerSocket.close();
                mServerSocket = null;
            } catch (IOException ex) {
                Log.e(TAG, "Close Server Socket error: ", ex);
            }
        }
    }
    private final synchronized void closeConnectionSocket() {
        if (mConnSocket != null) {
            try {
                mConnSocket.close();
                mConnSocket = null;
            } catch (IOException e) {
                Log.e(TAG, "Close Connection Socket error: ", e);
            }
        }
    }

    private final void closeService() {
        if (VERBOSE) Log.v(TAG, "SAP Service closeService in");

        // exit initSocket early
        mInterrupted = true;
        closeServerSocket();

        if (mAcceptThread != null) {
            try {
                mAcceptThread.shutdown();
                mAcceptThread.join();
                mAcceptThread = null;
            } catch (InterruptedException ex) {
                Log.w(TAG, "mAcceptThread close error", ex);
            }
        }

        if (mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
        }

        closeConnectionSocket();

        if (VERBOSE) Log.v(TAG, "SAP Service closeService out");
    }

    private final void startSapServerSession() throws IOException {
        if (VERBOSE) Log.v(TAG, "Sap Service startSapServerSession");

        // acquire the wakeLock before start SAP transaction thread
        // TODO: Do we need this? I guess we will wake when ever incoming data is available?
        //        And/or when a SIM event occurs - same for MAP.
        // UPDATE: Change to use same approach as for MAP with a timer based wake-lock
        if (mWakeLock == null) {
            PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "StartingSapTransaction");
            mWakeLock.setReferenceCounted(false);
            mWakeLock.acquire();
        }

        setState(BluetoothSap.STATE_CONNECTED);

        /* Start the SAP I/O thread and associate with message handler */
        mSapServer = new SapServer(mSessionStatusHandler, this, mConnSocket.getInputStream(), mConnSocket.getOutputStream());
        mSapServer.start();
        /* Warning: at this point we most likely have already handled the initial connect
         *          request from the SAP client, hence we need to be prepared to handle the
         *          response. (the SapHandler should have been started before this point)*/

        if (VERBOSE) {
            Log.v(TAG, "startSapServerSession() success!");
        }
    }

    private void stopSapServerSession() {

        /* When we reach this point, the SapServer is closed down, and the client is
         * supposed to close the RFCOMM connection. */
        if (VERBOSE) Log.v(TAG, "SAP Service stopSapServerSession");

        // Release the wake lock if SAP transactions is over
        /* TODO: Why do we need to hold a wakeLock? I assume the lower layers will wake
         * the host at incoming data, hence I'm not sure why we need this wake lock?
         * Any SAP req/ind is triggered either by incoming data from the client, from the
         * RIL deamon or a user initiated disconnect.
         * Perhaps we should only hold a wakelock while handling a message?
         */

        mAcceptThread = null;
        closeConnectionSocket();
        closeServerSocket();

        setState(BluetoothSap.STATE_DISCONNECTED);

        if (mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
        }

        // Last SAP transaction is finished, we start to listen for incoming
        // rfcomm connection again
        if (mAdapter.isEnabled()) {
            startRfcommSocketListener();
        }
    }

    /**
     * A thread that runs in the background waiting for remote rfcomm
     * connect.Once a remote socket connected, this thread shall be
     * shutdown.When the remote disconnect,this thread shall run again waiting
     * for next request.
     */
    private class SocketAcceptThread extends Thread {

        private boolean stopped = false;

        @Override
        public void run() {
            BluetoothServerSocket serverSocket;
            if (mServerSocket == null) {
                if (!initSocket()) {
                    return;
                }
            }

            while (!stopped) {
                try {
                    if (VERBOSE) Log.v(TAG, "Accepting socket connection...");
                    serverSocket = mServerSocket;
                    if(serverSocket == null) {
                        Log.w(TAG, "mServerSocket is null");
                        break;
                    }
                    mConnSocket = mServerSocket.accept();
                    if (VERBOSE) Log.v(TAG, "Accepted socket connection...");
                    synchronized (SapService.this) {
                        if (mConnSocket == null) {
                            Log.w(TAG, "mConnSocket is null");
                            break;
                        }
                        mRemoteDevice = mConnSocket.getRemoteDevice();
                    }
                    if (mRemoteDevice == null) {
                        Log.i(TAG, "getRemoteDevice() = null");
                        break;
                    }

                    sRemoteDeviceName = mRemoteDevice.getName();
                    // In case getRemoteName failed and return null
                    if (TextUtils.isEmpty(sRemoteDeviceName)) {
                        sRemoteDeviceName = getString(R.string.defaultname);
                    }
                    int permission = mRemoteDevice.getSimAccessPermission();

                    if (VERBOSE) Log.v(TAG, "getSimAccessPermission() = " + permission);

                    if (permission == BluetoothDevice.ACCESS_ALLOWED) {
                        try {
                            if (VERBOSE) Log.v(TAG, "incoming connection accepted from: "
                                + sRemoteDeviceName + " automatically as trusted device");
                            startSapServerSession();
                        } catch (IOException ex) {
                            Log.e(TAG, "catch exception starting obex server session", ex);
                        }
                    } else if (permission != BluetoothDevice.ACCESS_REJECTED){
                        Intent intent = new
                                Intent(BluetoothDevice.ACTION_CONNECTION_ACCESS_REQUEST);
                        intent.setClassName(ACCESS_AUTHORITY_PACKAGE, ACCESS_AUTHORITY_CLASS);
                        intent.putExtra(BluetoothDevice.EXTRA_ACCESS_REQUEST_TYPE,
                                        BluetoothDevice.REQUEST_TYPE_SIM_ACCESS);
                        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mRemoteDevice);
                        intent.putExtra(BluetoothDevice.EXTRA_PACKAGE_NAME, getPackageName());

                        mIsWaitingAuthorization = true;
                        sendBroadcast(intent, BLUETOOTH_ADMIN_PERM);

                        if (VERBOSE) Log.v(TAG, "waiting for authorization for connection from: "
                                + sRemoteDeviceName);

                    } else {
                        // Assuming reject is the stored state - continue to accept new connection.
                        continue;
                    }
                    stopped = true; // job done ,close this thread;
                } catch (IOException ex) {
                    stopped=true;
                    if (VERBOSE) Log.v(TAG, "Accept exception: ", ex);
                }
            }
        }

        void shutdown() {
            stopped = true;
            interrupt();
        }
    }

    private final Handler mSessionStatusHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (VERBOSE) Log.v(TAG, "Handler(): got msg=" + msg.what);

            switch (msg.what) {
                case START_LISTENER:
                    if (mAdapter.isEnabled()) {
                        startRfcommSocketListener();
                    }
                    break;
                case USER_TIMEOUT:
                    if (mIsWaitingAuthorization){
                        sendCancelUserConfirmationIntent(mRemoteDevice);
                        cancelUserTimeoutAlarm();
                        mIsWaitingAuthorization = false;
                        stopSapServerSession(); // And restart RfcommListener if needed
                    }
                    break;
                case MSG_SERVERSESSION_CLOSE:
                    stopSapServerSession();
                    break;
                case MSG_SESSION_ESTABLISHED:
                    break;
                case MSG_SESSION_DISCONNECTED:
                    // handled elsewhere
                    break;
                case SHUTDOWN:
                    /* Ensure to call close from this handler to avoid starting new stuff
                       because of pending messages */
                    closeService();
                    break;
                default:
                    break;
            }
        }
    };

    private void setState(int state) {
        setState(state, BluetoothSap.RESULT_SUCCESS);
    }

    private synchronized void setState(int state, int result) {
        if (state != mState) {
            if (DEBUG) Log.d(TAG, "Sap state " + mState + " -> " + state + ", result = "
                    + result);
            int prevState = mState;
            mState = state;
            Intent intent = new Intent(BluetoothSap.ACTION_CONNECTION_STATE_CHANGED);
            intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState);
            intent.putExtra(BluetoothProfile.EXTRA_STATE, mState);
            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mRemoteDevice);
            sendBroadcast(intent, BLUETOOTH_PERM);
            AdapterService s = AdapterService.getAdapterService();
            if (s != null) {
                s.onProfileConnectionStateChanged(mRemoteDevice, BluetoothProfile.SAP,
                        mState, prevState);
            }
        }
    }

    public int getState() {
        return mState;
    }

    public BluetoothDevice getRemoteDevice() {
        return mRemoteDevice;
    }

    public static String getRemoteDeviceName() {
        return sRemoteDeviceName;
    }

    public boolean disconnect(BluetoothDevice device) {
        boolean result = false;
        synchronized (SapService.this) {
            if (getRemoteDevice().equals(device)) {
                switch (mState) {
                    case BluetoothSap.STATE_CONNECTED:
                        closeConnectionSocket();
                        setState(BluetoothSap.STATE_DISCONNECTED, BluetoothSap.RESULT_CANCELED);
                        result = true;
                        break;
                    default:
                        break;
                }
            }
        }
        return result;
    }

    public List<BluetoothDevice> getConnectedDevices() {
        List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();
        synchronized(this) {
            if (mState == BluetoothSap.STATE_CONNECTED && mRemoteDevice != null) {
                devices.add(mRemoteDevice);
            }
        }
        return devices;
    }

    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        List<BluetoothDevice> deviceList = new ArrayList<BluetoothDevice>();
        Set<BluetoothDevice> bondedDevices = mAdapter.getBondedDevices();
        int connectionState;
        synchronized (this) {
            for (BluetoothDevice device : bondedDevices) {
                ParcelUuid[] featureUuids = device.getUuids();
                if (!BluetoothUuid.containsAnyUuid(featureUuids, SAP_UUIDS)) {
                    continue;
                }
                connectionState = getConnectionState(device);
                for(int i = 0; i < states.length; i++) {
                    if (connectionState == states[i]) {
                        deviceList.add(device);
                    }
                }
            }
        }
        return deviceList;
    }

    public int getConnectionState(BluetoothDevice device) {
        synchronized(this) {
            if (getState() == BluetoothSap.STATE_CONNECTED && getRemoteDevice().equals(device)) {
                return BluetoothProfile.STATE_CONNECTED;
            } else {
                return BluetoothProfile.STATE_DISCONNECTED;
            }
        }
    }

    public boolean setPriority(BluetoothDevice device, int priority) {
        Settings.Global.putInt(getContentResolver(),
            Settings.Global.getBluetoothSapPriorityKey(device.getAddress()),
            priority);
        if (DEBUG) Log.d(TAG, "Saved priority " + device + " = " + priority);
        return true;
    }

    public int getPriority(BluetoothDevice device) {
        int priority = Settings.Global.getInt(getContentResolver(),
            Settings.Global.getBluetoothSapPriorityKey(device.getAddress()),
            BluetoothProfile.PRIORITY_UNDEFINED);
        return priority;
    }

    @Override
    protected IProfileServiceBinder initBinder() {
        return new SapBinder(this);
    }

    @Override
    protected boolean start() {
        Log.v(TAG, "start()");
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_CONNECTION_ACCESS_REPLY);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(USER_CONFIRM_TIMEOUT_ACTION);

        try {
            registerReceiver(mSapReceiver, filter);
        } catch (Exception e) {
            Log.w(TAG,"Unable to register sap receiver",e);
        }
        mInterrupted = false;
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        // start RFCOMM listener
        mSessionStatusHandler.sendMessage(mSessionStatusHandler
                .obtainMessage(START_LISTENER));
        return true;
    }

    @Override
    protected boolean stop() {
        Log.v(TAG, "stop()");
        try {
            unregisterReceiver(mSapReceiver);
        } catch (Exception e) {
            Log.w(TAG,"Unable to unregister sap receiver",e);
        }
        setState(BluetoothSap.STATE_DISCONNECTED, BluetoothSap.RESULT_CANCELED);
        sendShutdownMessage();
        return true;
    }

    public boolean cleanup()  {
        setState(BluetoothSap.STATE_DISCONNECTED, BluetoothSap.RESULT_CANCELED);
        closeService();
        if(mSessionStatusHandler != null) {
            mSessionStatusHandler.removeCallbacksAndMessages(null);
        }
        return true;
    }

    private void setUserTimeoutAlarm(){
        if(DEBUG)Log.d(TAG,"SetUserTimeOutAlarm()");
        if(mAlarmManager == null){
            mAlarmManager =(AlarmManager) this.getSystemService (Context.ALARM_SERVICE);
        }
        mRemoveTimeoutMsg = true;
        Intent timeoutIntent =
                new Intent(USER_CONFIRM_TIMEOUT_ACTION);
        PendingIntent pIntent = PendingIntent.getBroadcast(this, 0, timeoutIntent, 0);
        mAlarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis()
                + USER_CONFIRM_TIMEOUT_VALUE,pIntent);
    }

    private void cancelUserTimeoutAlarm(){
        if(DEBUG)Log.d(TAG,"cancelUserTimeOutAlarm()");
        Intent intent = new Intent(this, SapService.class);
        PendingIntent sender = PendingIntent.getBroadcast(this, 0, intent, 0);
        AlarmManager alarmManager = (AlarmManager) this.getSystemService(Context.ALARM_SERVICE);
        alarmManager.cancel(sender);
        mRemoveTimeoutMsg = false;
    }

    private void sendCancelUserConfirmationIntent(BluetoothDevice device) {
        Intent intent = new Intent(BluetoothDevice.ACTION_CONNECTION_ACCESS_CANCEL);
        intent.setClassName(ACCESS_AUTHORITY_PACKAGE, ACCESS_AUTHORITY_CLASS);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothDevice.EXTRA_ACCESS_REQUEST_TYPE,
                        BluetoothDevice.REQUEST_TYPE_SIM_ACCESS);
        sendBroadcast(intent, BLUETOOTH_PERM);
    }

    private void sendShutdownMessage() {
        /* Any pending messages are no longer valid.
        To speed up things, simply delete them. */
        if (mRemoveTimeoutMsg) {
            Intent timeoutIntent =
                    new Intent(USER_CONFIRM_TIMEOUT_ACTION);
            sendBroadcast(timeoutIntent, BLUETOOTH_PERM);
            mIsWaitingAuthorization = false;
            cancelUserTimeoutAlarm();
        }
        mSessionStatusHandler.removeCallbacksAndMessages(null);
        // Request release of all resources
        mSessionStatusHandler.obtainMessage(SHUTDOWN).sendToTarget();
    }

    private void sendConnectTimeoutMessage() {
        if (DEBUG) Log.d(TAG, "sendConnectTimeoutMessage()");
        if(mSessionStatusHandler != null) {
            Message msg = mSessionStatusHandler.obtainMessage(USER_TIMEOUT);
            msg.sendToTarget();
        } // Can only be null during shutdown
    }

    private SapBroadcastReceiver mSapReceiver = new SapBroadcastReceiver();

    private class SapBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {

            if (VERBOSE) Log.v(TAG, "onReceive");
            String action = intent.getAction();
            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                                               BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_TURNING_OFF) {
                    if (DEBUG) Log.d(TAG, "STATE_TURNING_OFF");
                    sendShutdownMessage();
                } else if (state == BluetoothAdapter.STATE_ON) {
                    if (DEBUG) Log.d(TAG, "STATE_ON");
                    // start RFCOMM listener
                    mSessionStatusHandler.sendMessage(mSessionStatusHandler
                                  .obtainMessage(START_LISTENER));
                }
            } else if (action.equals(BluetoothDevice.ACTION_CONNECTION_ACCESS_REPLY)) {
                Log.v(TAG, " - Received BluetoothDevice.ACTION_CONNECTION_ACCESS_REPLY");
                if (!mIsWaitingAuthorization) {
                    // this reply is not for us
                    return;
                }

                mIsWaitingAuthorization = false;

                if (intent.getIntExtra(BluetoothDevice.EXTRA_CONNECTION_ACCESS_RESULT,
                                       BluetoothDevice.CONNECTION_ACCESS_NO) ==
                    BluetoothDevice.CONNECTION_ACCESS_YES) {
                    //bluetooth connection accepted by user
                    if (intent.getBooleanExtra(BluetoothDevice.EXTRA_ALWAYS_ALLOWED, false)) {
                        boolean result = mRemoteDevice.setSimAccessPermission(
                                BluetoothDevice.ACCESS_ALLOWED);
                        if (VERBOSE) {
                            Log.v(TAG, "setSimAccessPermission(ACCESS_ALLOWED) result=" + result);
                        }                    }
                    try {
                        if (mConnSocket != null) {
                            // start obex server and rfcomm connection
                            startSapServerSession();
                        } else {
                            stopSapServerSession();
                        }
                    } catch (IOException ex) {
                        Log.e(TAG, "Caught the error: ", ex);
                    }
                } else {
                    if (intent.getBooleanExtra(BluetoothDevice.EXTRA_ALWAYS_ALLOWED, false)) {
                        boolean result = mRemoteDevice.setSimAccessPermission(
                                BluetoothDevice.ACCESS_REJECTED);
                        if (VERBOSE) {
                            Log.v(TAG, "setSimAccessPermission(ACCESS_REJECTED) result="
                                    + result);
                        }
                    }
                    stopSapServerSession();
                }
            } else if (action.equals(USER_CONFIRM_TIMEOUT_ACTION)){
                if (DEBUG) Log.d(TAG, "USER_CONFIRM_TIMEOUT ACTION Received.");
                // send us self a message about the timeout.
                sendConnectTimeoutMessage();
            }  else if (action.equals(BluetoothDevice.ACTION_ACL_DISCONNECTED) &&
                    mIsWaitingAuthorization) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

                if (mRemoteDevice == null || device == null) {
                    Log.e(TAG, "Unexpected error!");
                    return;
                }

                if (DEBUG) Log.d(TAG,"ACL disconnected for " + device);

                if (mRemoteDevice.equals(device) && mRemoveTimeoutMsg) {
                    // Send any pending timeout now, as ACL got disconnected.
                    mSessionStatusHandler.removeMessages(USER_TIMEOUT);
                    sendCancelUserConfirmationIntent(mRemoteDevice);
                    mIsWaitingAuthorization = false;
                    mRemoveTimeoutMsg = false;
                }
            }
        }
    };

    //Binder object: Must be static class or memory leak may occur
    /**
     * This class implements the IBluetoothSap interface - or actually it validates the
     * preconditions for calling the actual functionality in the SapService, and calls it.
     */
    private static class SapBinder extends IBluetoothSap.Stub
        implements IProfileServiceBinder {
        private SapService mService;

        private SapService getService() {
            if (!Utils.checkCaller()) {
                Log.w(TAG,"call not allowed for non-active user");
                return null;
            }

            if (mService != null && mService.isAvailable() ) {
                mService.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
                return mService;
            }
            return null;
        }

        SapBinder(SapService service) {
            Log.v(TAG, "SapBinder()");
            mService = service;
        }

        public boolean cleanup()  {
            mService = null;
            return true;
        }

        public int getState() {
            Log.v(TAG, "getState()");
            SapService service = getService();
            if (service == null) return BluetoothSap.STATE_DISCONNECTED;
            return getService().getState();
        }

        public BluetoothDevice getClient() {
            Log.v(TAG, "getClient()");
            SapService service = getService();
            if (service == null) return null;
            Log.v(TAG, "getClient() - returning " + service.getRemoteDevice());
            return service.getRemoteDevice();
        }

        public boolean isConnected(BluetoothDevice device) {
            Log.v(TAG, "isConnected()");
            SapService service = getService();
            if (service == null) return false;
            return (service.getState() == BluetoothSap.STATE_CONNECTED
                    && service.getRemoteDevice().equals(device));
        }

        public boolean connect(BluetoothDevice device) {
            Log.v(TAG, "connect()");
            SapService service = getService();
            if (service == null) return false;
            return false;
        }

        public boolean disconnect(BluetoothDevice device) {
            Log.v(TAG, "disconnect()");
            SapService service = getService();
            if (service == null) return false;
            return service.disconnect(device);
        }

        public List<BluetoothDevice> getConnectedDevices() {
            Log.v(TAG, "getConnectedDevices()");
            SapService service = getService();
            if (service == null) return new ArrayList<BluetoothDevice>(0);
            return service.getConnectedDevices();
        }

        public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
            Log.v(TAG, "getDevicesMatchingConnectionStates()");
            SapService service = getService();
            if (service == null) return new ArrayList<BluetoothDevice>(0);
            return service.getDevicesMatchingConnectionStates(states);
        }

        public int getConnectionState(BluetoothDevice device) {
            Log.v(TAG, "getConnectionState()");
            SapService service = getService();
            if (service == null) return BluetoothProfile.STATE_DISCONNECTED;
            return service.getConnectionState(device);
        }

        public boolean setPriority(BluetoothDevice device, int priority) {
            SapService service = getService();
            if (service == null) return false;
            return service.setPriority(device, priority);
        }

        public int getPriority(BluetoothDevice device) {
            SapService service = getService();
            if (service == null) return BluetoothProfile.PRIORITY_UNDEFINED;
            return service.getPriority(device);
        }
    }
}
