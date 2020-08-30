package com.yuan.lcmemulator;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

import com.yuan.lcdemulatorview.LcmEmulatorView;
import com.yuan.protocol.FrameProcessor;
import com.yuan.protocol.ProtocolProcessor;
import com.yuan.protocol.ReceiveFifo;

import android.graphics.Point;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

public class SocketServer {
    private LcmEmulatorView mLcmEmView;
    private ServerSocket sever;
    private ProtocolProcessor ps;

    MyHandler myHandler;

    public SocketServer(final int port, LcmEmulatorView lcmEmView) {

        mLcmEmView = lcmEmView;
        ps = new ProtocolProcessor(mLcmEmView);
        myHandler = new MyHandler();
        new Thread(new Runnable() {
            public void run() {
                try {
                    sever = new ServerSocket(port);
                    beginListen();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    /**
     * 接受消息,处理消息 ,此Handler会与当前主线程一块运行
     */

    class MyHandler extends Handler {
        public MyHandler() {
        }

        public MyHandler(Looper L) {
            super(L);
        }

        // 子类必须重写此方法,接受数据
        @Override
        public void handleMessage(Message msg) {
            Log.d("MyHandler", "handleMessage......");
            super.handleMessage(msg);
            // 此处可以更新UI
            Bundle b = msg.getData();
            byte[] arr = b.getByteArray("AAA");
            ps.Process(arr);
            //String rec =b.getString("BBB");
            Log.d("MyHandler", arr.toString());

            // mLcmEmView.setCursor(new Point(0,0));
            // mLcmEmView.writeStr(rec);


        }
    }

    public void beginListen() {

        new Thread(new Runnable() {
            public void run() {
                BufferedReader in;
                try {
                    Socket socket = sever.accept();
                    socket.setTcpNoDelay(true);


                    //读取
                    //字节流的形式读取
                    // 优缺点分析，弱点：受byte[]大小的限制  ，优点：不受回车符（\r）和换行符（\n）限制
                    InputStream input = socket.getInputStream();
                    ReceiveFifo fifo = new ReceiveFifo(input);
                    ProtocolProcessor protocolProcessor = new ProtocolProcessor(mLcmEmView);
                    FrameProcessor frameProcessor = new FrameProcessor(protocolProcessor,fifo);
                    byte[] buf = new byte[64];
//					PrintWriter out = new PrintWriter(
//							socket.getOutputStream());
                    while (!socket.isClosed()) {
                        if (input != null) {
                            fifo.process();
                            frameProcessor.ParseEventFrameStream();
                            // int len = input.read(buf);

                            // Message msg = new Message();
                            // Bundle b = new Bundle();// 存放数据
                            // b.putByteArray("AAA", buf);
                            // b.putString("BBB", new String(buf,0,len));
                            // msg.setData(b);

                            // myHandler.sendMessage(msg); // 向Handler发送消息,更新UI

                            // Log.i("LCDEM", "Recv:" + len);
                        }

                    }
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).start();

    }
}