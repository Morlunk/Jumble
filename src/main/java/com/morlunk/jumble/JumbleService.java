/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.morlunk.jumble;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.util.Log;

import com.morlunk.jumble.audio.AudioOutput;
import com.morlunk.jumble.audio.BluetoothScoReceiver;
import com.morlunk.jumble.exception.AudioException;
import com.morlunk.jumble.model.Channel;
import com.morlunk.jumble.model.IChannel;
import com.morlunk.jumble.model.IUser;
import com.morlunk.jumble.model.Message;
import com.morlunk.jumble.model.Server;
import com.morlunk.jumble.model.TalkState;
import com.morlunk.jumble.model.User;
import com.morlunk.jumble.net.JumbleConnection;
import com.morlunk.jumble.util.JumbleException;
import com.morlunk.jumble.net.JumbleTCPMessageType;
import com.morlunk.jumble.protobuf.Mumble;
import com.morlunk.jumble.protocol.AudioHandler;
import com.morlunk.jumble.protocol.ModelHandler;
import com.morlunk.jumble.util.JumbleCallbacks;
import com.morlunk.jumble.util.JumbleLogger;
import com.morlunk.jumble.util.ParcelableByteArray;

import java.security.Security;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

public class JumbleService extends Service implements JumbleConnection.JumbleConnectionListener, JumbleLogger, BluetoothScoReceiver.Listener {

    static {
        // Use Spongy Castle for crypto implementation so we can create and manage PKCS #12 (.p12) certificates.
        Security.insertProviderAt(new org.spongycastle.jce.provider.BouncyCastleProvider(), 1);
    }

    /**
     * The default state of Jumble, before connection to a server and after graceful/expected
     * disconnection from a server.
     */
    public static final int STATE_DISCONNECTED = 0;
    /**
     * A connection to the server is currently in progress.
     */
    public static final int STATE_CONNECTING = 1;
    /**
     * Jumble has received all data necessary for normal protocol communication with the server.
     */
    public static final int STATE_CONNECTED = 2;
    /**
     * The connection was lost due to either a kick/ban or socket I/O error.
     * Jumble can be reconnecting in this state.
     * @see IJumbleService#isReconnecting()
     * @see IJumbleService#cancelReconnect()
     */
    public static final int STATE_CONNECTION_LOST = 3;

    /**
     * An action to immediately connect to a given Mumble server.
     * Requires that {@link #EXTRAS_SERVER} is provided.
     */
    public static final String ACTION_CONNECT = "com.morlunk.jumble.CONNECT";

    /** A {@link Server} specifying the server to connect to. */
    public static final String EXTRAS_SERVER = "server";
    public static final String EXTRAS_AUTO_RECONNECT = "auto_reconnect";
    public static final String EXTRAS_AUTO_RECONNECT_DELAY = "auto_reconnect_delay";
    public static final String EXTRAS_CERTIFICATE = "certificate";
    public static final String EXTRAS_CERTIFICATE_PASSWORD = "certificate_password";
    public static final String EXTRAS_DETECTION_THRESHOLD = "detection_threshold";
    public static final String EXTRAS_AMPLITUDE_BOOST = "amplitude_boost";
    public static final String EXTRAS_TRANSMIT_MODE = "transmit_mode";
    public static final String EXTRAS_INPUT_RATE = "input_frequency";
    public static final String EXTRAS_INPUT_QUALITY = "input_quality";
    public static final String EXTRAS_USE_OPUS = "use_opus";
    public static final String EXTRAS_FORCE_TCP = "force_tcp";
    public static final String EXTRAS_USE_TOR = "use_tor";
    public static final String EXTRAS_CLIENT_NAME = "client_name";
    public static final String EXTRAS_ACCESS_TOKENS = "access_tokens";
    public static final String EXTRAS_AUDIO_SOURCE = "audio_source";
    public static final String EXTRAS_AUDIO_STREAM = "audio_stream";
    public static final String EXTRAS_FRAMES_PER_PACKET = "frames_per_packet";
    /** An optional path to a trust store for CA certificates. */
    public static final String EXTRAS_TRUST_STORE = "trust_store";
    /** The trust store's password. */
    public static final String EXTRAS_TRUST_STORE_PASSWORD = "trust_store_password";
    /** The trust store's format. */
    public static final String EXTRAS_TRUST_STORE_FORMAT = "trust_store_format";
    public static final String EXTRAS_TRUST_EVERYONE = "trust_everyone";
    public static final String EXTRAS_HALF_DUPLEX = "half_duplex";
    /** A list of users that should be local muted upon connection. */
    public static final String EXTRAS_LOCAL_MUTE_HISTORY = "local_mute_history";
    /** A list of users that should be local ignored upon connection. */
    public static final String EXTRAS_LOCAL_IGNORE_HISTORY = "local_ignore_history";
    public static final String EXTRAS_ENABLE_PREPROCESSOR = "enable_preprocessor";

    // Service settings
    private Server mServer;
    private boolean mAutoReconnect;
    private int mAutoReconnectDelay;
    private byte[] mCertificate;
    private String mCertificatePassword;
    private boolean mUseOpus;
    private boolean mForceTcp;
    private boolean mUseTor;
    private String mClientName;
    private List<String> mAccessTokens;
    private String mTrustStore;
    private String mTrustStorePassword;
    private String mTrustStoreFormat;
    private boolean mTrustEveryone;
    private List<Integer> mLocalMuteHistory;
    private List<Integer> mLocalIgnoreHistory;
    private AudioHandler.Builder mAudioBuilder;

    private PowerManager.WakeLock mWakeLock;
    private Handler mHandler;
    private JumbleCallbacks mCallbacks;
    private IJumbleService.Stub mBinder = new JumbleBinder();

    private JumbleConnection mConnection;
    private int mConnectionState;
    private ModelHandler mModelHandler;
    private AudioHandler mAudioHandler;
    private BluetoothScoReceiver mBluetoothReceiver;

    private boolean mReconnecting;

    /**
     * Listen for connectivity changes in the reconnection state, and reconnect accordingly.
     */
    private BroadcastReceiver mConnectivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!mReconnecting) {
                unregisterReceiver(this);
                return;
            }

            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm.getActiveNetworkInfo() != null && cm.getActiveNetworkInfo().isConnected()) {
                Log.v(Constants.TAG, "Connectivity restored, attempting reconnect.");
                connect();
            }
        }
    };

    private AudioHandler.AudioEncodeListener mAudioInputListener =
            new AudioHandler.AudioEncodeListener() {
        @Override
        public void onAudioEncoded(byte[] data, int length) {
            if(mConnection != null && mConnection.isSynchronized()) {
                mConnection.sendUDPMessage(data, length, false);
            }
        }

        @Override
        public void onTalkStateChange(final TalkState state) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if(!isConnected()) return;
                    final User currentUser = mModelHandler.getUser(mConnection.getSession());
                    if(currentUser == null) return;

                    currentUser.setTalkState(state);
                    try {
                        mCallbacks.onUserTalkStateUpdated(currentUser);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }
            });
        }
    };

    private AudioOutput.AudioOutputListener mAudioOutputListener = new AudioOutput.AudioOutputListener() {
        @Override
        public void onUserTalkStateUpdated(final User user) {
            try {
                mCallbacks.onUserTalkStateUpdated(user);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        @Override
        public User getUser(int session) {
            return mModelHandler.getUser(session);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            Bundle extras = intent.getExtras();
            if (extras != null) {
                try {
                    configureExtras(extras);
                } catch (AudioException e) {
                    throw new RuntimeException("Attempted to initialize audio in onStartCommand erroneously.");
                }
            }

            if (ACTION_CONNECT.equals(intent.getAction())) {
                if (extras == null || !extras.containsKey(EXTRAS_SERVER)) {
                    // Ensure that we have been provided all required attributes.
                    throw new RuntimeException(ACTION_CONNECT + " requires a server provided in extras.");
                }
                connect();
            }
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jumble");
        mHandler = new Handler(getMainLooper());
        mCallbacks = new JumbleCallbacks();
        mAudioBuilder = new AudioHandler.Builder()
                .setContext(this)
                .setLogger(this)
                .setEncodeListener(mAudioInputListener)
                .setTalkingListener(mAudioOutputListener);
        mConnectionState = STATE_DISCONNECTED;
        mBluetoothReceiver = new BluetoothScoReceiver(this, this);
        registerReceiver(mBluetoothReceiver, new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED));
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(mBluetoothReceiver);
        mCallbacks.kill();
        super.onDestroy();
    }

    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public IJumbleService getBinder() {
        return mBinder;
    }

    public void connect() {
        try {
            setReconnecting(false);
            mConnectionState = STATE_DISCONNECTED;

            mConnection = new JumbleConnection(this);
            mConnection.setForceTCP(mForceTcp);
            mConnection.setUseTor(mUseTor);
            mConnection.setKeys(mCertificate, mCertificatePassword);
            mConnection.setTrustStore(mTrustStore, mTrustStorePassword, mTrustStoreFormat);
            mConnection.setTrustEveryone(mTrustEveryone);

            mModelHandler = new ModelHandler(this, mCallbacks, this,
                    mLocalMuteHistory, mLocalIgnoreHistory);
            mConnection.addTCPMessageHandlers(mModelHandler);

            mConnectionState = STATE_CONNECTING;

            try {
                mCallbacks.onConnecting();
            } catch (RemoteException e) {
                e.printStackTrace();
            }

            mConnection.connect(mServer.getHost(), mServer.getPort());
        } catch (JumbleException e) {
            e.printStackTrace();
            try {
                mCallbacks.onDisconnected(e);
            } catch (RemoteException e1) {
                e1.printStackTrace();
            }
        }
    }

    public void disconnect() {
        mConnection.disconnect();
    }

    public boolean isConnected() {
        return mConnection != null && mConnection.isConnected();
    }

    @Override
    public void onConnectionEstablished() {
        // Send version information and authenticate.
        final Mumble.Version.Builder version = Mumble.Version.newBuilder();
        version.setRelease(mClientName);
        version.setVersion(Constants.PROTOCOL_VERSION);
        version.setOs("Android");
        version.setOsVersion(Build.VERSION.RELEASE);

        final Mumble.Authenticate.Builder auth = Mumble.Authenticate.newBuilder();
        auth.setUsername(mServer.getUsername());
        auth.setPassword(mServer.getPassword());
        auth.addCeltVersions(Constants.CELT_7_VERSION);
        // FIXME: resolve issues with CELT 11 robot voices.
//            auth.addCeltVersions(Constants.CELT_11_VERSION);
        auth.setOpus(mUseOpus);
        auth.addAllTokens(mAccessTokens);

        mConnection.sendTCPMessage(version.build(), JumbleTCPMessageType.Version);
        mConnection.sendTCPMessage(auth.build(), JumbleTCPMessageType.Authenticate);
    }

    @Override
    public void onConnectionSynchronized() {
        mConnectionState = STATE_CONNECTED;

        Log.v(Constants.TAG, "Connected");
        mWakeLock.acquire();

        try {
            mAudioHandler = mAudioBuilder.initialize(
                    mModelHandler.getUser(mConnection.getSession()),
                    mConnection.getMaxBandwidth(), mConnection.getCodec());
            mConnection.addTCPMessageHandlers(mAudioHandler);
            mConnection.addUDPMessageHandlers(mAudioHandler);
        } catch (AudioException e) {
            e.printStackTrace();
            onConnectionWarning(e.getMessage());
        }

        try {
            mCallbacks.onConnected();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onConnectionHandshakeFailed(X509Certificate[] chain) {
        try {
            final ParcelableByteArray encodedCert = new ParcelableByteArray(chain[0].getEncoded());
            mCallbacks.onTLSHandshakeFailed(encodedCert);
        } catch (CertificateEncodingException e) {
            e.printStackTrace();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onConnectionDisconnected(JumbleException e) {
        if (e != null) {
            Log.e(Constants.TAG, "Error: " + e.getMessage() +
                    " (reason: " + e.getReason().name() + ")");
            mConnectionState = STATE_CONNECTION_LOST;

            setReconnecting(mAutoReconnect
                    && e.getReason() == JumbleException.JumbleDisconnectReason.CONNECTION_ERROR);
        } else {
            Log.v(Constants.TAG, "Disconnected");
            mConnectionState = STATE_DISCONNECTED;
        }

        if(mWakeLock.isHeld()) {
            mWakeLock.release();
        }

        if (mAudioHandler != null) {
            mAudioHandler.shutdown();
        }

        mModelHandler = null;
        mAudioHandler = null;

        // Halt SCO connection on shutdown.
        mBluetoothReceiver.stopBluetoothSco();

        try {
            mCallbacks.onDisconnected(e);
        } catch (RemoteException re) {
            re.printStackTrace();
        }
    }

    @Override
    public void onConnectionWarning(String warning) {
        logWarning(warning);
    }

    @Override
    public void logInfo(String message) {
        if (mConnection == null || !mConnection.isSynchronized())
            return; // don't log info prior to synchronization
        try {
            mCallbacks.onLogInfo(message);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void logWarning(String message) {
        try {
            mCallbacks.onLogWarning(message);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void logError(String message) {
        try {
            mCallbacks.onLogError(message);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void setReconnecting(boolean reconnecting) {
        mReconnecting = reconnecting;
        if (reconnecting) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo info = cm.getActiveNetworkInfo();
            if (info != null && info.isConnected()) {
                Log.v(Constants.TAG, "Connection lost due to non-connectivity issue. Start reconnect polling.");
                Handler mainHandler = new Handler();
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (mReconnecting) connect();
                    }
                }, mAutoReconnectDelay);
            } else {
                // In the event that we've lost connectivity, don't poll. Wait until network
                // returns before we resume connection attempts.
                Log.v(Constants.TAG, "Connection lost due to connectivity issue. Waiting until network returns.");
                try {
                    registerReceiver(mConnectivityReceiver,
                            new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
                } catch (IllegalArgumentException e) {
                    e.printStackTrace();
                }
            }
        } else {
            try {
                unregisterReceiver(mConnectivityReceiver);
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Instantiates an audio handler with the current service settings, destroying any previous
     * handler. Requires synchronization with the server, as the maximum bandwidth and session must
     * be known.
     */
    private void createAudioHandler() throws AudioException {
        if (BuildConfig.DEBUG && mConnectionState != STATE_CONNECTED) {
            throw new AssertionError("Attempted to instantiate audio handler when not connected!");
        }

        if (mAudioHandler != null) {
            mConnection.removeTCPMessageHandler(mAudioHandler);
            mConnection.removeUDPMessageHandler(mAudioHandler);
            mAudioHandler.shutdown();
        }

        mAudioHandler = mAudioBuilder.initialize(
                mModelHandler.getUser(mConnection.getSession()),
                mConnection.getMaxBandwidth(), mConnection.getCodec());
        mConnection.addTCPMessageHandlers(mAudioHandler);
        mConnection.addUDPMessageHandlers(mAudioHandler);
    }

    /**
     * Loads all defined settings from the given bundle into the JumbleService.
     * Some settings may only take effect after a reconnect.
     * @param extras A bundle with settings.
     * @return true if a reconnect is required for changes to take effect.
     * @see com.morlunk.jumble.JumbleService
     */
    private boolean configureExtras(Bundle extras) throws AudioException {
        boolean reconnectNeeded = false;
        if (extras.containsKey(EXTRAS_SERVER)) {
            mServer = extras.getParcelable(EXTRAS_SERVER);
            reconnectNeeded = true;
        }
        if (extras.containsKey(EXTRAS_AUTO_RECONNECT)) {
            mAutoReconnect = extras.getBoolean(EXTRAS_AUTO_RECONNECT);
        }
        if (extras.containsKey(EXTRAS_AUTO_RECONNECT_DELAY)) {
            mAutoReconnectDelay = extras.getInt(EXTRAS_AUTO_RECONNECT_DELAY);
        }
        if (extras.containsKey(EXTRAS_CERTIFICATE)) {
            mCertificate = extras.getByteArray(EXTRAS_CERTIFICATE);
            reconnectNeeded = true;
        }
        if (extras.containsKey(EXTRAS_CERTIFICATE_PASSWORD)) {
            mCertificatePassword = extras.getString(EXTRAS_CERTIFICATE_PASSWORD);
            reconnectNeeded = true;
        }
        if (extras.containsKey(EXTRAS_DETECTION_THRESHOLD)) {
            mAudioBuilder.setVADThreshold(extras.getFloat(EXTRAS_DETECTION_THRESHOLD));
        }
        if (extras.containsKey(EXTRAS_AMPLITUDE_BOOST)) {
            mAudioBuilder.setAmplitudeBoost(extras.getFloat(EXTRAS_AMPLITUDE_BOOST));
        }
        if (extras.containsKey(EXTRAS_TRANSMIT_MODE)) {
            mAudioBuilder.setTransmitMode(extras.getInt(EXTRAS_TRANSMIT_MODE));
        }
        if (extras.containsKey(EXTRAS_INPUT_RATE)) {
            mAudioBuilder.setInputSampleRate(extras.getInt(EXTRAS_INPUT_RATE));
        }
        if (extras.containsKey(EXTRAS_INPUT_QUALITY)) {
            mAudioBuilder.setTargetBitrate(extras.getInt(EXTRAS_INPUT_QUALITY));
        }
        if (extras.containsKey(EXTRAS_USE_OPUS)) {
            mUseOpus = extras.getBoolean(EXTRAS_USE_OPUS);
            reconnectNeeded = true;
        }
        if (extras.containsKey(EXTRAS_USE_TOR)) {
            mUseTor = extras.getBoolean(EXTRAS_USE_TOR);
            mForceTcp |= mUseTor; // Tor requires TCP connections to work- if it's on, force TCP.
            reconnectNeeded = true;
        }
        if (extras.containsKey(EXTRAS_FORCE_TCP)) {
            mForceTcp |= extras.getBoolean(EXTRAS_FORCE_TCP);
            reconnectNeeded = true;
        }
        if (extras.containsKey(EXTRAS_CLIENT_NAME)) {
            mClientName = extras.getString(EXTRAS_CLIENT_NAME);
            reconnectNeeded = true;
        }
        if (extras.containsKey(EXTRAS_ACCESS_TOKENS)) {
            mAccessTokens = extras.getStringArrayList(EXTRAS_ACCESS_TOKENS);
            if (mConnection != null && mConnection.isConnected()) {
                mConnection.sendAccessTokens(mAccessTokens);
            }
        }
        if (extras.containsKey(EXTRAS_AUDIO_SOURCE)) {
            mAudioBuilder.setAudioSource(extras.getInt(EXTRAS_AUDIO_SOURCE));
        }
        if (extras.containsKey(EXTRAS_AUDIO_STREAM)) {
            mAudioBuilder.setAudioStream(extras.getInt(EXTRAS_AUDIO_STREAM));
        }
        if (extras.containsKey(EXTRAS_FRAMES_PER_PACKET)) {
            mAudioBuilder.setTargetFramesPerPacket(extras.getInt(EXTRAS_FRAMES_PER_PACKET));
        }
        if (extras.containsKey(EXTRAS_TRUST_STORE)) {
            mTrustStore = extras.getString(EXTRAS_TRUST_STORE);
            reconnectNeeded = true;
        }
        if (extras.containsKey(EXTRAS_TRUST_STORE_PASSWORD)) {
            mTrustStorePassword = extras.getString(EXTRAS_TRUST_STORE_PASSWORD);
            reconnectNeeded = true;
        }
        if (extras.containsKey(EXTRAS_TRUST_STORE_FORMAT)) {
            mTrustStoreFormat = extras.getString(EXTRAS_TRUST_STORE_FORMAT);
            reconnectNeeded = true;
        }
        if (extras.containsKey(EXTRAS_TRUST_EVERYONE)) {
            mTrustEveryone = extras.getBoolean(EXTRAS_TRUST_EVERYONE);
            reconnectNeeded = true;
        }
        if (extras.containsKey(EXTRAS_HALF_DUPLEX)) {
            mAudioBuilder.setHalfDuplexEnabled(extras.getBoolean(EXTRAS_HALF_DUPLEX));
        }
        if (extras.containsKey(EXTRAS_LOCAL_MUTE_HISTORY)) {
            mLocalMuteHistory = extras.getIntegerArrayList(EXTRAS_LOCAL_MUTE_HISTORY);
            reconnectNeeded = true;
        }
        if (extras.containsKey(EXTRAS_LOCAL_IGNORE_HISTORY)) {
            mLocalIgnoreHistory = extras.getIntegerArrayList(EXTRAS_LOCAL_IGNORE_HISTORY);
            reconnectNeeded = true;
        }
        if (extras.containsKey(EXTRAS_ENABLE_PREPROCESSOR)) {
            mAudioBuilder.setPreprocessorEnabled(extras.getBoolean(EXTRAS_ENABLE_PREPROCESSOR));
        }

        // Reload audio subsystem if initialized
        if (mAudioHandler != null && mAudioHandler.isInitialized()) {
            createAudioHandler();
            Log.i(Constants.TAG, "Audio subsystem reloaded after settings change.");
        }
        return reconnectNeeded;
    }

    @Override
    public void onBluetoothScoConnected() {
        // After an SCO connection is established, audio is rerouted to be compatible with SCO.
        mAudioBuilder.setBluetoothEnabled(true);
        if (mAudioHandler != null) {
            try {
                createAudioHandler();
            } catch (AudioException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onBluetoothScoDisconnected() {
        // Restore audio settings after disconnection.
        mAudioBuilder.setBluetoothEnabled(false);
        if (mAudioHandler != null) {
            try {
                createAudioHandler();
            } catch (AudioException e) {
                e.printStackTrace();
            }
        }
    }

    public class JumbleBinder extends IJumbleService.Stub {
        @Override
        public int getConnectionState() throws RemoteException {
            return mConnectionState;
        }

        @Override
        public JumbleException getConnectionError() throws RemoteException {
            return mConnection != null ? mConnection.getError() : null;
        }

        @Override
        public boolean isReconnecting() throws RemoteException {
            return mReconnecting;
        }

        @Override
        public void cancelReconnect() throws RemoteException {
            setReconnecting(false);
        }

        @Override
        public void disconnect() throws RemoteException {
            JumbleService.this.disconnect();
        }

        @Override
        public long getTCPLatency() throws RemoteException {
            return mConnection.getTCPLatency();
        }

        @Override
        public long getUDPLatency() throws RemoteException {
            return mConnection.getUDPLatency();
        }

        @Override
        public int getMaxBandwidth() throws RemoteException {
            return mConnection.getMaxBandwidth();
        }

        @Override
        public int getCurrentBandwidth() throws RemoteException {
            return mAudioHandler.getCurrentBandwidth();
        }

        @Override
        public int getServerVersion() throws RemoteException {
            return mConnection.getServerVersion();
        }

        @Override
        public String getServerRelease() throws RemoteException {
            return mConnection.getServerRelease();
        }

        @Override
        public String getServerOSName() throws RemoteException {
            return mConnection.getServerOSName();
        }

        @Override
        public String getServerOSVersion() throws RemoteException {
            return mConnection.getServerOSVersion();
        }

        @Override
        public int getSession() throws RemoteException {
            return mConnection != null ? mConnection.getSession() : -1;
        }

        @Override
        public IUser getSessionUser() throws RemoteException {
            return mModelHandler != null ? mModelHandler.getUser(getSession()) : null;
        }

        @Override
        public IChannel getSessionChannel() throws RemoteException {
            IUser user = getSessionUser();
            if (user != null) {
                return user.getChannel();
            }
            return null;
        }

        @Override
        public Server getConnectedServer() throws RemoteException {
            return mServer;
        }

        @Override
        public IUser getUser(int id) throws RemoteException {
            if (mModelHandler != null)
                return mModelHandler.getUser(id);
            return null;
        }

        @Override
        public IChannel getChannel(int id) throws RemoteException {
            if (mModelHandler != null)
                return mModelHandler.getChannel(id);
            return null;
        }

        @Override
        public IChannel getRootChannel() throws RemoteException {
            return getChannel(0);
        }

        @Override
        public int getPermissions() throws RemoteException {
            return mModelHandler != null ? mModelHandler.getPermissions() : 0;
        }

        @Override
        public int getTransmitMode() throws RemoteException {
            return mAudioHandler.getTransmitMode();
        }

        @Override
        public int getCodec() throws RemoteException {
            return mConnection.getCodec().ordinal(); // FIXME: ordinal is bad, make enum method
        }

        @Override
        public boolean usingBluetoothSco() throws RemoteException {
            return mBluetoothReceiver.isBluetoothScoOn();
        }

        @Override
        public void enableBluetoothSco() throws RemoteException {
            mBluetoothReceiver.startBluetoothSco();
        }

        @Override
        public void disableBluetoothSco() throws RemoteException {
            mBluetoothReceiver.stopBluetoothSco();
        }

        @Override
        public boolean isTalking() throws RemoteException {
            return mAudioHandler != null && mAudioHandler.isRecording();
        }

        @Override
        public void setTalkingState(boolean talking) throws RemoteException {
            if(getSessionUser() != null &&
                    (getSessionUser().isSelfMuted() || getSessionUser().isMuted())) {
                return;
            }

            if (mAudioHandler.getTransmitMode() != Constants.TRANSMIT_PUSH_TO_TALK) {
                Log.w(Constants.TAG, "Attempted to set talking state when not using PTT");
                return;
            }

            try {
                mAudioHandler.setTalking(talking);
            } catch (AudioException e) {
                logError(e.getMessage());
            }
        }

        @Override
        public void joinChannel(int channel) throws RemoteException {
            moveUserToChannel(getSession(), channel);
        }

        @Override
        public void moveUserToChannel(int session, int channel) throws RemoteException {
            Mumble.UserState.Builder usb = Mumble.UserState.newBuilder();
            usb.setSession(session);
            usb.setChannelId(channel);
            mConnection.sendTCPMessage(usb.build(), JumbleTCPMessageType.UserState);
        }

        @Override
        public void createChannel(int parent, String name, String description, int position, boolean temporary) throws RemoteException {
            Mumble.ChannelState.Builder csb = Mumble.ChannelState.newBuilder();
            csb.setParent(parent);
            csb.setName(name);
            csb.setDescription(description);
            csb.setPosition(position);
            csb.setTemporary(temporary);
            mConnection.sendTCPMessage(csb.build(), JumbleTCPMessageType.ChannelState);
        }

        @Override
        public void sendAccessTokens(List tokens) throws RemoteException {
            mConnection.sendAccessTokens(tokens);
        }

        @Override
        public void requestBanList() throws RemoteException {
            throw new UnsupportedOperationException("Not yet implemented"); // TODO
        }

        @Override
        public void requestUserList() throws RemoteException {
            throw new UnsupportedOperationException("Not yet implemented"); // TODO
        }

        @Override
        public void requestPermissions(int channel) throws RemoteException {
            Mumble.PermissionQuery.Builder pqb = Mumble.PermissionQuery.newBuilder();
            pqb.setChannelId(channel);
            mConnection.sendTCPMessage(pqb.build(), JumbleTCPMessageType.PermissionQuery);
        }

        @Override
        public void requestComment(int session) throws RemoteException {
            Mumble.RequestBlob.Builder rbb = Mumble.RequestBlob.newBuilder();
            rbb.addSessionComment(session);
            mConnection.sendTCPMessage(rbb.build(), JumbleTCPMessageType.RequestBlob);
        }

        @Override
        public void requestAvatar(int session) throws RemoteException {
            Mumble.RequestBlob.Builder rbb = Mumble.RequestBlob.newBuilder();
            rbb.addSessionTexture(session);
            mConnection.sendTCPMessage(rbb.build(), JumbleTCPMessageType.RequestBlob);
        }

        @Override
        public void requestChannelDescription(int channel) throws RemoteException {
            Mumble.RequestBlob.Builder rbb = Mumble.RequestBlob.newBuilder();
            rbb.addChannelDescription(channel);
            mConnection.sendTCPMessage(rbb.build(), JumbleTCPMessageType.RequestBlob);
        }

        @Override
        public void registerUser(int session) throws RemoteException {
            Mumble.UserState.Builder usb = Mumble.UserState.newBuilder();
            usb.setSession(session);
            usb.setUserId(0);
            mConnection.sendTCPMessage(usb.build(), JumbleTCPMessageType.UserState);
        }

        @Override
        public void kickBanUser(int session, String reason, boolean ban) throws RemoteException {
            Mumble.UserRemove.Builder urb = Mumble.UserRemove.newBuilder();
            urb.setSession(session);
            urb.setReason(reason);
            urb.setBan(ban);
            mConnection.sendTCPMessage(urb.build(), JumbleTCPMessageType.UserRemove);
        }

        @Override
        public Message sendUserTextMessage(int session, String message) throws RemoteException {
            Mumble.TextMessage.Builder tmb = Mumble.TextMessage.newBuilder();
            tmb.addSession(session);
            tmb.setMessage(message);
            mConnection.sendTCPMessage(tmb.build(), JumbleTCPMessageType.TextMessage);

            User self = mModelHandler.getUser(getSession());
            User user = mModelHandler.getUser(session);
            List<User> users = new ArrayList<User>(1);
            users.add(user);
            return new Message(getSession(), self.getName(), new ArrayList<Channel>(0), new ArrayList<Channel>(0), users, message);
        }

        @Override
        public Message sendChannelTextMessage(int channel, String message, boolean tree) throws RemoteException {
            Mumble.TextMessage.Builder tmb = Mumble.TextMessage.newBuilder();
            if(tree) tmb.addTreeId(channel);
            else tmb.addChannelId(channel);
            tmb.setMessage(message);
            mConnection.sendTCPMessage(tmb.build(), JumbleTCPMessageType.TextMessage);

            User self = mModelHandler.getUser(getSession());
            Channel targetChannel = mModelHandler.getChannel(channel);
            List<Channel> targetChannels = new ArrayList<Channel>();
            targetChannels.add(targetChannel);
            return new Message(getSession(), self.getName(), targetChannels, tree ? targetChannels : new ArrayList<Channel>(0), new ArrayList<User>(0), message);
        }

        @Override
        public void setUserComment(int session, String comment) throws RemoteException {
            Mumble.UserState.Builder usb = Mumble.UserState.newBuilder();
            usb.setSession(session);
            usb.setComment(comment);
            mConnection.sendTCPMessage(usb.build(), JumbleTCPMessageType.UserState);
        }

        @Override
        public void setPrioritySpeaker(int session, boolean priority) throws RemoteException {
            Mumble.UserState.Builder usb = Mumble.UserState.newBuilder();
            usb.setSession(session);
            usb.setPrioritySpeaker(priority);
            mConnection.sendTCPMessage(usb.build(), JumbleTCPMessageType.UserState);
        }

        @Override
        public void removeChannel(int channel) throws RemoteException {
            Mumble.ChannelRemove.Builder crb = Mumble.ChannelRemove.newBuilder();
            crb.setChannelId(channel);
            mConnection.sendTCPMessage(crb.build(), JumbleTCPMessageType.ChannelRemove);
        }

        @Override
        public void setMuteDeafState(int session, boolean mute, boolean deaf) throws RemoteException {
            Mumble.UserState.Builder usb = Mumble.UserState.newBuilder();
            usb.setSession(session);
            usb.setMute(mute);
            usb.setDeaf(deaf);
            if (!mute) usb.setSuppress(false);
            mConnection.sendTCPMessage(usb.build(), JumbleTCPMessageType.UserState);
        }

        @Override
        public void setSelfMuteDeafState(boolean mute, boolean deaf) throws RemoteException {
            Mumble.UserState.Builder usb = Mumble.UserState.newBuilder();
            usb.setSelfMute(mute);
            usb.setSelfDeaf(deaf);
            mConnection.sendTCPMessage(usb.build(), JumbleTCPMessageType.UserState);
        }

        @Override
        public void registerObserver(IJumbleObserver observer) throws RemoteException {
            mCallbacks.registerObserver(observer);
        }

        @Override
        public void unregisterObserver(IJumbleObserver observer) throws RemoteException {
            mCallbacks.unregisterObserver(observer);
        }

        @Override
        public boolean reconfigure(Bundle extras) throws RemoteException {
            try {
                return configureExtras(extras);
            } catch (AudioException e) {
                e.printStackTrace();
                // TODO
                return true;
            }
        }
    }
}
