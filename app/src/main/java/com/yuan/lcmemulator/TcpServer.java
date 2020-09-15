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
    private CharLcmView mLcmEmView;
    private ServerSocket server;

    public boolean isRunListen() {
        return mRunListen;
    }

    public void setRunListen(boolean mRunListen) {
        this.mRunListen = mRunListen;
    }

    // MyHandler myHandler;
    private boolean mRunListen;

    public TcpServer(final int port, CharLcmView lcmEmView) {

        mLcmEmView = lcmEmView;
        mRunListen = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    server = new ServerSocket(port);
                    while (mRunListen)
                        beginListen();
                } catch (IOException e) {
                    Close();
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

        // new Thread(new Runnable() {
        //     public void run() {
        try {
            Log.d("LCDEM", "Start listening...");
            Socket socket = server.accept();
            socket.setTcpNoDelay(true);
            Log.d("LCDEM", "Socket accepted.");

            InputStream input = socket.getInputStream();
            ReceiveFifo fifo = new ReceiveFifo(input);
            ProtocolProcessor protocolProcessor = new ProtocolProcessor(mLcmEmView);
            FrameProcessor frameProcessor = new FrameProcessor(protocolProcessor, fifo);
            while ((!socket.isClosed()) && mRunListen) {
                if (input != null) {
                    frameProcessor.ParseEventFrameStream();
                }
            }
            socket.close();
            Log.d("LCDEM", "Socket closed.");
        } catch (IOException e) {
            e.printStackTrace();
        }
        // }
        // }).start();

    }
}