package com.yuan.lcmemulator;

import android.util.Log;

import com.yuan.protocol.FrameProcessor;
import com.yuan.protocol.ProtocolProcessor;
import com.yuan.protocol.ReceiveFifo;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;


public class TcpServer {
    private ConnectionListener connectionListener;

    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }

    private void listenClient() {
        try {
            Log.d(TAG, "Waiting for client...");
            Socket socket = serverSocket.accept();
            socket.setTcpNoDelay(true);

            Log.d(TAG, "Client connected.");

            if (connectionListener != null) {
                connectionListener.onClientConnected();
            }

            handleClient(socket);

        } catch (IOException e) {
            if (running) {
                Log.e(TAG, "Accept error", e);
            }
        }
    }

    private static final String TAG = "LCDEM";

    private final CharLcmView lcdView;

    private ServerSocket serverSocket;
    private Thread serverThread;

    private volatile boolean running = false;
    private int port;

    public TcpServer(int port, CharLcmView lcdView) {
        this.port = port;
        this.lcdView = lcdView;
    }

    /* ==========================
       生命周期控制
       ========================== */

    public synchronized void start() {
        if (running) return;

        running = true;

        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                Log.d(TAG, "Server started on port: " + port);

                while (running) {
                    listenClient();
                }

            } catch (IOException e) {
                Log.e(TAG, "Server error", e);
            } finally {
                closeServerSocket();
            }
        });

        serverThread.start();
    }

    public synchronized void stop() {
        running = false;
        closeServerSocket();

        if (serverThread != null) {
            serverThread.interrupt();
            serverThread = null;
        }

        Log.d(TAG, "Server stopped.");
    }

    public synchronized void updatePort(int newPort) {
        if (this.port == newPort) return;

        stop();
        this.port = newPort;
        start();
    }

    public boolean isRunning() {
        return running;
    }

    /* ==========================
       监听客户端
       ========================== */

    private void handleClient(Socket socket) {
        try {
            InputStream input = socket.getInputStream();

            ReceiveFifo fifo = new ReceiveFifo(input);
            ProtocolProcessor protocolProcessor =
                    new ProtocolProcessor(lcdView, socket);
            FrameProcessor frameProcessor =
                    new FrameProcessor(protocolProcessor, fifo);

            while (running && !socket.isClosed()) {
                frameProcessor.ParseEventFrameStream();
            }

        } catch (Exception e) {
            Log.e(TAG, "Client error", e);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }

            Log.d(TAG, "Client disconnected.");
            if (connectionListener != null) {
                connectionListener.onClientDisconnected();
            }
        }
    }

    public interface ConnectionListener {
        void onClientConnected();

        void onClientDisconnected();
    }

    private void closeServerSocket() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
    }
}
