package com.yuan.protocol;

import android.os.SystemClock;
import android.util.Log;

import com.yuan.lcmemulator.CharLcmView;

import java.net.DatagramSocket;

/**
 * Top-level facade for the LCD UDP device (Android = device side).
 *
 * <p>Owns the UDP socket, {@link UdpReceiver}, {@link UdpSender},
 * {@link ProtocolProcessor}, and the heartbeat send/monitor thread.
 *
 * <h3>Usage:</h3>
 * <pre>
 * LcdUdpServer server = new LcdUdpServer(lcmView, 2400);
 * server.setConnectionListener(myListener);
 * server.start();
 * // ... later ...
 * server.stop();
 * </pre>
 *
 * <h3>Heartbeat behaviour (§12, device side):</h3>
 * <ul>
 *   <li>Every {@code HB_INTERVAL_MS} sends CMD_HEARTBEAT (ROLE=0x02).</li>
 *   <li>Monitors incoming PC heartbeats; if {@code HB_MISS_MAX} consecutive
 *       misses → declares disconnected.</li>
 *   <li>When a PC heartbeat arrives and currently disconnected → declares connected.</li>
 * </ul>
 */
public class LcdUdpServer {

    private static final String TAG = "LCDEM";

    /* Heartbeat protocol constants — must match udp.h / §12.2 */
    private static final int HB_INTERVAL_MS = 3000;
    private static final int HB_MISS_MAX    = 3;
    private static final int HB_ROLE_PC     = 0x01;

    /**
     * Connection state listener. All callbacks are invoked on a background
     * thread — use {@code View.post()} or {@code Handler} to update UI.
     */
    public interface ConnectionListener {
        /** PC heartbeats are being received. */
        void onConnected();

        /** PC heartbeats stopped (HB_MISS_MAX consecutive misses). */
        void onDisconnected();
    }

    private final CharLcmView mView;
    private final int mListenPort;

    private ConnectionListener mListener;
    private ProtocolProcessor.OnDeInitListener mDeInitListener;

    private DatagramSocket mSocket;
    private UdpSender   mSender;
    private UdpReceiver mReceiver;
    private ProtocolProcessor mProcessor;

    private Thread mRecvThread;
    private Thread mHbThread;

    private volatile boolean mRunning;
    private volatile boolean mConnected;

    /* Heartbeat miss counter — accessed only from heartbeat thread +
     * the onPcHeartbeat callback (both guarded by mHbLock). */
    private final Object mHbLock = new Object();
    private int mMissCount;
    private int mHbSeq;

    /* Boot timestamp for UPTIME field */
    private long mStartTimeMs;

    public LcdUdpServer(CharLcmView view, int listenPort) {
        this.mView = view;
        this.mListenPort = listenPort;
    }

    public void setConnectionListener(ConnectionListener listener) {
        this.mListener = listener;
    }

    public void setOnDeInitListener(ProtocolProcessor.OnDeInitListener l) {
        this.mDeInitListener = l;
    }

    /** Returns true if currently connected (receiving PC heartbeats). */
    public boolean isConnected() {
        return mConnected;
    }

    /**
     * Start listening for UDP packets and begin the heartbeat cycle.
     *
     * @throws IllegalStateException if already running
     */
    public void start() {
        if (mRunning) throw new IllegalStateException("Already running");

        mRunning = true;
        mConnected = false;
        mMissCount = 0;
        mHbSeq = 0;
        mStartTimeMs = SystemClock.elapsedRealtime();

        try {
            mSocket = new DatagramSocket(mListenPort);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create socket on port " + mListenPort, e);
            mRunning = false;
            return;
        }

        mSender    = new UdpSender(mSocket);
        mProcessor = new ProtocolProcessor(mView);
        if (mDeInitListener != null) {
            mProcessor.setOnDeInitListener(mDeInitListener);
        }
        mReceiver  = new UdpReceiver(mSocket, mProcessor, mSender);

        /* Route heartbeat packets from UdpReceiver to our state machine */
        mReceiver.setHeartbeatCallback(new UdpReceiver.HeartbeatCallback() {
            @Override
            public void onHeartbeatReceived(int role, int hbSeq, int uptime) {
                if (role == HB_ROLE_PC) {
                    onPcHeartbeat(hbSeq, uptime);
                }
            }
        });

        /* Any valid packet from the PC is a liveness signal —
         * if we're receiving commands, the link is obviously up. */
        mReceiver.setPacketCallback(new UdpReceiver.PacketCallback() {
            @Override
            public void onValidPacketReceived() {
                onPcActivity();
            }
        });

        /* Receive thread */
        mRecvThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "Receive thread started, port=" + mListenPort);
                mReceiver.startReceiving();
                Log.i(TAG, "Receive thread exiting");
            }
        }, "LcdUdp-Recv");
        mRecvThread.setDaemon(true);
        mRecvThread.start();

        /* Heartbeat send thread */
        mHbThread = new Thread(new Runnable() {
            @Override
            public void run() {
                heartbeatLoop();
            }
        }, "LcdUdp-HB");
        mHbThread.setDaemon(true);
        mHbThread.start();

        Log.i(TAG, "LcdUdpServer started on port " + mListenPort);
    }

    /**
     * Stop the server: terminate threads and close the socket.
     * Safe to call multiple times.
     */
    public void stop() {
        if (!mRunning) return;
        mRunning = false;

        /* Stop receiver (will break out of blocking receive) */
        if (mReceiver != null) mReceiver.stop();
        if (mSocket != null && !mSocket.isClosed()) mSocket.close();

        /* Wait for threads */
        interruptAndJoin(mHbThread, "HB");
        interruptAndJoin(mRecvThread, "Recv");

        mConnected = false;
        Log.i(TAG, "LcdUdpServer stopped");
    }

    private void interruptAndJoin(Thread t, String name) {
        if (t == null) return;
        t.interrupt();
        try {
            t.join(3000);
        } catch (InterruptedException e) {
            Log.w(TAG, name + " thread join interrupted");
        }
    }

    /**
     * Heartbeat send loop (§12.4 device side).
     *
     * Every HB_INTERVAL_MS:
     *   1. Send CMD_HEARTBEAT (ROLE=0x02).
     *   2. Increment miss counter.
     *   3. If miss >= HB_MISS_MAX → declare disconnected.
     *
     * The miss counter is reset to 0 by onPcHeartbeat() when a PC
     * heartbeat arrives on the receiver thread.
     */
    private void heartbeatLoop() {
        Log.i(TAG, "Heartbeat thread started");

        while (mRunning) {
            try {
                Thread.sleep(HB_INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            }

            if (!mRunning) break;

            /* Send our heartbeat (only if we know the PC address) */
            if (mSender != null && mSender.hasRemote()) {
                int uptimeSecs = (int) ((SystemClock.elapsedRealtime() - mStartTimeMs) / 1000);
                int seq;
                synchronized (mHbLock) {
                    seq = mHbSeq;
                    mHbSeq = (mHbSeq + 1) & 0xFF;
                }
                mSender.sendHeartbeat(seq, uptimeSecs);
                Log.d(TAG, "HB sent: seq=" + seq + " uptime=" + uptimeSecs + "s");
            }

            /* Check miss counter */
            synchronized (mHbLock) {
                mMissCount++;
                if (mMissCount >= HB_MISS_MAX && mConnected) {
                    mConnected = false;
                    Log.w(TAG, "HB: PC heartbeat timeout, declaring DISCONNECTED");
                    notifyDisconnected();
                }
            }
        }

        Log.i(TAG, "Heartbeat thread exiting");
    }

    /**
     * Called by UdpReceiver (on the recv thread) when ANY valid packet
     * (CRC OK, correct version) arrives from the PC.  Treats all traffic
     * as a liveness signal — if we're receiving commands, the link is up.
     */
    private void onPcActivity() {
        synchronized (mHbLock) {
            mMissCount = 0;

            if (!mConnected) {
                mConnected = true;
                Log.i(TAG, "PC activity detected, declaring CONNECTED");
                notifyConnected();
            }
        }
    }

    /**
     * Called by UdpReceiver (on the recv thread) when a PC heartbeat arrives.
     * Simple logic: reset miss counter, if not connected → mark connected.
     */
    private void onPcHeartbeat(int hbSeq, int pcUptime) {
        Log.d(TAG, "HB recv: PC seq=" + hbSeq + " uptime=" + pcUptime + "s");

        synchronized (mHbLock) {
            mMissCount = 0;

            if (!mConnected) {
                mConnected = true;
                Log.i(TAG, "HB: PC connected");
                notifyConnected();
            }
        }
    }

    private void notifyConnected() {
        ConnectionListener l = mListener;
        if (l != null) {
            try { l.onConnected(); }
            catch (Exception e) { Log.e(TAG, "Listener error", e); }
        }
    }

    private void notifyDisconnected() {
        ConnectionListener l = mListener;
        if (l != null) {
            try { l.onDisconnected(); }
            catch (Exception e) { Log.e(TAG, "Listener error", e); }
        }
    }
}
