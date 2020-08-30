package com.yuan.lcmemulator;

import java.io.*;
import java.net.*;

import com.yuan.lcdemulatorview.LcmEmulatorView;
import com.yuan.protocol.FrameProcessor;
import com.yuan.protocol.ProtocolProcessor;
import com.yuan.protocol.ReceiveFifo;

import android.util.Log;

public class SocketServer {
    private LcmEmulatorView mLcmEmView;
    private ServerSocket server;
    // MyHandler myHandler;

    public SocketServer(final int port, LcmEmulatorView lcmEmView) {

        mLcmEmView = lcmEmView;
        // myHandler = new MyHandler();
        new Thread(new Runnable() {
            public void run() {
                try {
                    server = new ServerSocket(port);
                    beginListen();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    public void Close() {
        try {
            if (server != null)
                server.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 接受消息,处理消息 ,此Handler会与当前主线程一块运行
     */

//    class MyHandler extends Handler {
//        public MyHandler() {
//        }
//
//        public MyHandler(Looper L) {
//            super(L);
//        }
//
//        // 子类必须重写此方法,接受数据
//        @Override
//        public void handleMessage(Message msg) {
//            Log.d("MyHandler", "handleMessage......");
//            super.handleMessage(msg);
//            // 此处可以更新UI
//            Bundle b = msg.getData();
//            byte[] arr = b.getByteArray("AAA");
//            ps.Process(arr);
//            Log.d("MyHandler", arr.toString());
//        }
//    }
    public void beginListen() {

        new Thread(new Runnable() {
            public void run() {
                try {
                    Log.d("LCDEM", "Start listening...");
                    Socket socket = server.accept();
                    socket.setTcpNoDelay(true);
                    Log.d("LCDEM", "Socket accepted.");

                    InputStream input = socket.getInputStream();
                    ReceiveFifo fifo = new ReceiveFifo(input);
                    ProtocolProcessor protocolProcessor = new ProtocolProcessor(mLcmEmView);
                    FrameProcessor frameProcessor = new FrameProcessor(protocolProcessor, fifo);
                    while (!socket.isClosed()) {
                        if (input != null) {
                            frameProcessor.ParseEventFrameStream();
                        }
                    }
                    socket.close();
                    Log.d("LCDEM", "Socket closed.");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).start();

    }
}