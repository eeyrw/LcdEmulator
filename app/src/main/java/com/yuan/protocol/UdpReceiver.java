package com.yuan.protocol;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * UDP receiver: packet parsing, CRC-16 validation, fragment reassembly,
 * ACK responses, and heartbeat routing.
 *
 * <p>CMD_HEARTBEAT packets are NOT dispatched to {@link ProtocolProcessor};
 * instead they are routed to a {@link HeartbeatCallback} so that
 * {@link LcdUdpServer} can manage the heartbeat state machine.
 */
public class UdpReceiver {

    private static final String TAG = "LCDEM";

    private static final int HEADER_SIZE = 9;
    private static final int CRC_SIZE    = 2;
    private static final int MAX_UDP_SIZE = 1472;

    private static final int OFFSET_VER        = 0;
    private static final int OFFSET_SEQ_LO     = 1;
    private static final int OFFSET_SEQ_HI     = 2;
    private static final int OFFSET_FLAGS      = 3;
    private static final int OFFSET_CMD        = 4;
    private static final int OFFSET_FRAG_IDX   = 5;
    private static final int OFFSET_FRAG_TOTAL = 6;
    private static final int OFFSET_LEN_LO     = 7;
    private static final int OFFSET_LEN_HI     = 8;

    private static final int FLAG_ACK_REQ  = 0x01;
    private static final int FLAG_FRAG     = 0x02;
    private static final int CMD_ACK       = 0xFF;
    private static final int CMD_HEARTBEAT = 0x0C;
    private static final byte PROTOCOL_VERSION = 0x01;
    private static final byte ACK_STATUS_OK    = 0x00;
    private static final byte ACK_STATUS_FAIL  = 0x01;

    /**
     * Callback for heartbeat packets, invoked on the receiver thread.
     */
    public interface HeartbeatCallback {
        /**
         * Called when a CMD_HEARTBEAT from the PC is received.
         *
         * @param role   sender role (0x01 = PC)
         * @param hbSeq  heartbeat sequence number
         * @param uptime sender uptime in seconds
         */
        void onHeartbeatReceived(int role, int hbSeq, int uptime);
    }

    /**
     * Callback for any valid packet from the PC (CRC OK, correct version).
     * Used by {@link LcdUdpServer} to treat all traffic as a liveness signal.
     */
    public interface PacketCallback {
        void onValidPacketReceived();
    }

    private final Map<Integer, FragmentBuffer> mFragments = new HashMap<>();
    private final DatagramSocket mSocket;
    private final ProtocolProcessor mProcessor;
    private final UdpSender mSender;
    private HeartbeatCallback mHeartbeatCallback;
    private PacketCallback mPacketCallback;
    private volatile boolean mRunning;

    /**
     * Last SEQ of a successfully dispatched CMD_LCD_FULLFRAME.
     * Used to discard out-of-order (stale) full-frame packets:
     * if a FULLFRAME arrives with SEQ older than this, it is dropped.
     *
     * <p>SEQ is a 16-bit wrapping counter.  We use modular comparison
     * (signed distance) to handle wrap-around correctly.
     */
    private int mLastFullFrameSeq = -1;  /* -1 = no frame received yet */
    private static final int CMD_FULLFRAME = 0x0D;
    private static final int CMD_LCD_INIT  = 0x01;

    public UdpReceiver(DatagramSocket socket, ProtocolProcessor processor,
                       UdpSender sender) {
        this.mSocket    = socket;
        this.mProcessor = processor;
        this.mSender    = sender;
    }

    public void setHeartbeatCallback(HeartbeatCallback cb) {
        this.mHeartbeatCallback = cb;
    }

    public void setPacketCallback(PacketCallback cb) {
        this.mPacketCallback = cb;
    }

    /**
     * Start the blocking receive loop. Call from a background thread.
     */
    public void startReceiving() {
        mRunning = true;
        byte[] recvBuf = new byte[MAX_UDP_SIZE];

        while (mRunning && !mSocket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length);
                mSocket.receive(packet);
                handlePacket(packet);
            } catch (IOException e) {
                if (mRunning) {
                    Log.e(TAG, "UDP receive error", e);
                }
            }
        }
    }

    /**
     * Stop the receive loop.
     */
    public void stop() {
        mRunning = false;
    }

    private void handlePacket(DatagramPacket datagram) {
        byte[] raw = datagram.getData();
        int len = datagram.getLength();

        if (len < HEADER_SIZE + CRC_SIZE) {
            Log.w(TAG, "Packet too short: " + len);
            return;
        }

        /* Remember remote address for heartbeat sending */
        mSender.setRemote(datagram.getAddress(), datagram.getPort());

        int ver        = raw[OFFSET_VER] & 0xFF;
        int seq        = (raw[OFFSET_SEQ_LO] & 0xFF) | ((raw[OFFSET_SEQ_HI] & 0xFF) << 8);
        int flags      = raw[OFFSET_FLAGS] & 0xFF;
        int cmd        = raw[OFFSET_CMD] & 0xFF;
        int fragIdx    = raw[OFFSET_FRAG_IDX] & 0xFF;
        int fragTotal  = raw[OFFSET_FRAG_TOTAL] & 0xFF;
        int payloadLen = (raw[OFFSET_LEN_LO] & 0xFF) | ((raw[OFFSET_LEN_HI] & 0xFF) << 8);

        if (ver != PROTOCOL_VERSION) {
            Log.w(TAG, "Unknown protocol version: " + ver);
            return;
        }

        if (HEADER_SIZE + payloadLen + CRC_SIZE > len) {
            Log.w(TAG, "Payload length mismatch");
            return;
        }

        /* CRC validation */
        int crcOffset = HEADER_SIZE + payloadLen;
        int receivedCrc  = (raw[crcOffset] & 0xFF) | ((raw[crcOffset + 1] & 0xFF) << 8);
        int calculatedCrc = Crc16Ccitt.calculate(raw, 0, crcOffset);

        if (receivedCrc != calculatedCrc) {
            Log.w(TAG, "CRC mismatch");
            sendAckIfRequested(datagram, seq, flags, ACK_STATUS_FAIL);
            return;
        }

        byte[] payload = Arrays.copyOfRange(raw, HEADER_SIZE, HEADER_SIZE + payloadLen);

        /* Any valid packet from the PC is a liveness signal */
        if (mPacketCallback != null) {
            mPacketCallback.onValidPacketReceived();
        }

        /* Route CMD_HEARTBEAT to the heartbeat callback, not to ProtocolProcessor */
        if (cmd == CMD_HEARTBEAT) {
            if (mHeartbeatCallback != null && payloadLen >= 4) {
                int role   = payload[0] & 0xFF;
                int hbSeq  = payload[1] & 0xFF;
                int uptime = (payload[2] & 0xFF) | ((payload[3] & 0xFF) << 8);
                mHeartbeatCallback.onHeartbeatReceived(role, hbSeq, uptime);
            }
            /* Heartbeat does not use ACK (§12.1) — skip ACK even if flag set */
            return;
        }

        /* CMD_LCD_INIT signals a new PC session — the PC-side SEQ counter
         * resets to 0 in UdpInit().  We must reset our FULLFRAME sequence
         * tracker, otherwise all subsequent FULLFRAMEs (with small SEQ
         * values) would be incorrectly dropped as stale. */
        if (cmd == CMD_LCD_INIT) {
            mLastFullFrameSeq = -1;
        }

        /* Drop stale (out-of-order) FULLFRAME packets.
         * On a WiFi LAN, UDP reordering is rare but possible.  If frame N+1
         * arrives before frame N, rendering N after N+1 would cause a visible
         * one-frame rollback.  We use 16-bit modular comparison to handle
         * SEQ wrap-around: a frame is "newer" if (seq - lastSeq) mod 65536
         * is in the range [1, 32767]. */
        if (cmd == CMD_FULLFRAME && mLastFullFrameSeq >= 0) {
            int diff = (seq - mLastFullFrameSeq) & 0xFFFF;
            if (diff == 0 || diff > 0x7FFF) {
                Log.d(TAG, "Dropping stale FULLFRAME: seq=" + seq
                        + " last=" + mLastFullFrameSeq);
                sendAckIfRequested(datagram, seq, flags, ACK_STATUS_OK);
                return;
            }
        }

        /* Fragment reassembly */
        boolean isFrag = (flags & FLAG_FRAG) != 0;
        if (isFrag) {
            byte[] assembled = reassemble(seq, fragIdx, fragTotal, payload);
            if (assembled != null) {
                dispatchCommand(cmd, assembled);
                if (cmd == CMD_FULLFRAME) mLastFullFrameSeq = seq;
            }
        } else {
            dispatchCommand(cmd, payload);
            if (cmd == CMD_FULLFRAME) mLastFullFrameSeq = seq;
        }

        sendAckIfRequested(datagram, seq, flags, ACK_STATUS_OK);
    }

    private void dispatchCommand(int cmd, byte[] payload) {
        try {
            mProcessor.process(cmd, payload);
        } catch (Exception e) {
            Log.e(TAG, "Error processing cmd=0x" + Integer.toHexString(cmd), e);
        }
    }

    private byte[] reassemble(int seq, int fragIdx, int fragTotal, byte[] payload) {
        FragmentBuffer fb;
        synchronized (mFragments) {
            fb = mFragments.get(seq);
            if (fb == null) {
                fb = new FragmentBuffer(fragTotal);
                mFragments.put(seq, fb);
            }
        }
        fb.put(fragIdx, payload);
        if (fb.isComplete()) {
            synchronized (mFragments) {
                mFragments.remove(seq);
            }
            return fb.assemble();
        }
        return null;
    }

    private void sendAckIfRequested(DatagramPacket source, int seq,
                                     int flags, byte status) {
        if ((flags & FLAG_ACK_REQ) == 0) return;
        mSender.sendAck(seq, status, source.getAddress(), source.getPort());
    }

    private static class FragmentBuffer {
        private final byte[][] fragments;
        private int received;

        FragmentBuffer(int total) {
            fragments = new byte[total][];
            received = 0;
        }

        void put(int index, byte[] data) {
            if (index < 0 || index >= fragments.length) return;
            if (fragments[index] == null) {
                received++;
            }
            fragments[index] = data;
        }

        boolean isComplete() {
            return received == fragments.length;
        }

        byte[] assemble() {
            int totalLen = 0;
            for (byte[] frag : fragments) totalLen += frag.length;
            byte[] result = new byte[totalLen];
            int offset = 0;
            for (byte[] frag : fragments) {
                System.arraycopy(frag, 0, result, offset, frag.length);
                offset += frag.length;
            }
            return result;
        }
    }
}
