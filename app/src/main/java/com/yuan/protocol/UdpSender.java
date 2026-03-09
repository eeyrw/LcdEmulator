package com.yuan.protocol;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * Builds and sends UDP protocol packets (heartbeats, ACKs, etc.).
 *
 * <p>Thread-safe: all public methods synchronize on the socket.
 *
 * <p>Packet layout (little-endian):
 * <pre>
 * VER(1) | SEQ(2) | FLAGS(1) | CMD(1) | FRAG_IDX(1) | FRAG_TOTAL(1) | LEN(2) | PAYLOAD(0~N) | CRC16(2)
 * </pre>
 */
public class UdpSender {

    private static final String TAG = "LCDEM";

    private static final byte PROTOCOL_VERSION = 0x01;
    private static final int HEADER_SIZE = 9;
    private static final int CRC_SIZE    = 2;
    private static final int CMD_ACK     = 0xFF;
    private static final int CMD_HEARTBEAT = 0x0C;
    private static final int FLAG_ACK_REQ  = 0x01;

    /* Heartbeat payload constants (§12) */
    private static final byte HB_ROLE_DEVICE = 0x02;
    private static final int  HB_PAYLOAD_SIZE = 4;

    private final DatagramSocket mSocket;
    private final Object mLock = new Object();

    /* Sequence counter shared across all sends from this side */
    private int mSeq = 0;

    /* Target address (set when we receive the first packet from PC) */
    private volatile InetAddress mRemoteAddr;
    private volatile int mRemotePort;

    public UdpSender(DatagramSocket socket) {
        this.mSocket = socket;
    }

    /**
     * Set the remote PC address. Called by UdpReceiver when the first
     * packet arrives so we know where to send heartbeats and ACKs.
     */
    public void setRemote(InetAddress addr, int port) {
        mRemoteAddr = addr;
        mRemotePort = port;
    }

    public InetAddress getRemoteAddr() { return mRemoteAddr; }
    public int getRemotePort() { return mRemotePort; }
    public boolean hasRemote() { return mRemoteAddr != null; }

    private int nextSeq() {
        synchronized (mLock) {
            int s = mSeq;
            mSeq = (mSeq + 1) & 0xFFFF;
            return s;
        }
    }

    /**
     * Build a complete protocol packet (header + payload + CRC).
     * Returns the total packet length written into {@code buf}.
     */
    private int buildPacket(byte[] buf, int cmd, byte[] payload, int payloadLen,
                            int flags, int fragIdx, int fragTotal, int seq) {
        int totalLen = HEADER_SIZE + payloadLen + CRC_SIZE;
        if (totalLen > buf.length) return 0;

        buf[0] = PROTOCOL_VERSION;
        buf[1] = (byte) (seq & 0xFF);
        buf[2] = (byte) ((seq >> 8) & 0xFF);
        buf[3] = (byte) (flags & 0xFF);
        buf[4] = (byte) (cmd & 0xFF);
        buf[5] = (byte) (fragIdx & 0xFF);
        buf[6] = (byte) (fragTotal & 0xFF);
        buf[7] = (byte) (payloadLen & 0xFF);
        buf[8] = (byte) ((payloadLen >> 8) & 0xFF);

        if (payloadLen > 0 && payload != null) {
            System.arraycopy(payload, 0, buf, HEADER_SIZE, payloadLen);
        }

        int crcLen = HEADER_SIZE + payloadLen;
        int crc = Crc16Ccitt.calculate(buf, 0, crcLen);
        buf[crcLen]     = (byte) (crc & 0xFF);
        buf[crcLen + 1] = (byte) ((crc >> 8) & 0xFF);

        return totalLen;
    }

    /**
     * Send a raw byte array to the current remote address.
     */
    private boolean sendRaw(byte[] data, int length) {
        InetAddress addr = mRemoteAddr;
        int port = mRemotePort;
        if (addr == null || mSocket == null || mSocket.isClosed()) return false;

        try {
            DatagramPacket pkt = new DatagramPacket(data, length, addr, port);
            mSocket.send(pkt);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "sendRaw failed", e);
            return false;
        }
    }

    /**
     * Send an ACK packet for the given sequence number.
     *
     * @param seq    the SEQ of the packet being acknowledged
     * @param status 0x00 = success, 0x01 = failure
     * @param addr   destination address
     * @param port   destination port
     */
    public void sendAck(int seq, byte status, InetAddress addr, int port) {
        byte[] ack = new byte[7]; // VER(1)+SEQ(2)+CMD(1)+STATUS(1)+CRC16(2)
        ack[0] = PROTOCOL_VERSION;
        ack[1] = (byte) (seq & 0xFF);
        ack[2] = (byte) ((seq >> 8) & 0xFF);
        ack[3] = (byte) CMD_ACK;
        ack[4] = status;

        int crc = Crc16Ccitt.calculate(ack, 0, 5);
        ack[5] = (byte) (crc & 0xFF);
        ack[6] = (byte) ((crc >> 8) & 0xFF);

        try {
            DatagramPacket pkt = new DatagramPacket(ack, ack.length, addr, port);
            mSocket.send(pkt);
        } catch (IOException e) {
            Log.e(TAG, "Failed to send ACK", e);
        }
    }

    /**
     * Send a device-side heartbeat (ROLE=0x02, §12.3).
     *
     * @param hbSeq      heartbeat sequence number (0-255, wrapping)
     * @param uptimeSecs device uptime in seconds
     * @return true if sent successfully
     */
    public boolean sendHeartbeat(int hbSeq, int uptimeSecs) {
        if (!hasRemote()) return false;

        byte[] payload = new byte[HB_PAYLOAD_SIZE];
        payload[0] = HB_ROLE_DEVICE;
        payload[1] = (byte) (hbSeq & 0xFF);
        payload[2] = (byte) (uptimeSecs & 0xFF);
        payload[3] = (byte) ((uptimeSecs >> 8) & 0xFF);

        byte[] buf = new byte[HEADER_SIZE + HB_PAYLOAD_SIZE + CRC_SIZE];
        int seq = nextSeq();
        int len = buildPacket(buf, CMD_HEARTBEAT, payload, HB_PAYLOAD_SIZE,
                              0 /* no ACK_REQ */, 0, 1, seq);
        if (len == 0) return false;

        return sendRaw(buf, len);
    }
}
