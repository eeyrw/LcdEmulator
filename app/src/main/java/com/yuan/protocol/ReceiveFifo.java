package com.yuan.protocol;

import android.widget.ArrayAdapter;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Objects;

public class ReceiveFifo {
    InputStream mInputStream;
    ArrayDeque<Byte> mQueue = new ArrayDeque<>();
    public ReceiveFifo(InputStream mInputStream) {
        this.mInputStream = mInputStream;
    }

    public int available() throws IOException {
        return mInputStream.available();
    }

    public byte read() throws IOException {
        return (byte)mInputStream.read();
    }

    public void write(byte[] cmdRetBuf, int retByteNum) {
    }

    public int read(byte[] cmdBuf, byte cmdLen) throws IOException{
        Objects.requireNonNull(cmdBuf);
        int off = 0;
        if (off < 0 || cmdLen < 0 || cmdLen > cmdBuf.length - off)
            throw new IndexOutOfBoundsException();
        int n = 0;
        while (n < cmdLen) {
            int count = mInputStream.read(cmdBuf, off + n, cmdLen - n);
            if (count < 0)
                break;
            n += count;
        }
        return n;
    }


    public void process()
    {

    }
}
