package com.yuan.protocol;

import static com.yuan.protocol.FrameProcessor.EVENT_FRAME_PARSER_STATUS.IDLE;
import static com.yuan.protocol.FrameProcessor.EVENT_FRAME_PARSER_STATUS.RECV_CMD_LEN;
import static com.yuan.protocol.FrameProcessor.EVENT_FRAME_PARSER_STATUS.SOF_HI;
import static com.yuan.protocol.FrameProcessor.EVENT_FRAME_PARSER_STATUS.SOF_LO;

import java.io.IOException;

public class FrameProcessor {
    public FrameProcessor(ProtocolProcessor protocolProcessor, ReceiveFifo client) {
        this.protocolProcessor = protocolProcessor;
        this.client = client;
    }

    enum EVENT_FRAME_PARSER_STATUS {
        IDLE,
        SOF_LO,
        SOF_HI,
        RECV_CMD_LEN,
    }

    final int EVENT_FRAME_FLAG = 0x776E; //ASCII:"wn"

    EVENT_FRAME_PARSER_STATUS frameParseStatus = IDLE;
    byte cmdRetBuf[] = new byte[256];
    byte cmdBuf[] = new byte[256];
    ProtocolProcessor protocolProcessor;
    ReceiveFifo client;
    byte cmdLen = 0;

    public void ParseEventFrameStream() throws IOException {
        byte streamByte;
        switch (frameParseStatus) {
            case IDLE: {
                //if (client.available() > 0) {
                    streamByte = client.read();
                    if (streamByte == ((byte) (0xFF & EVENT_FRAME_FLAG))) {
                        frameParseStatus = SOF_LO;
                    }
                //}
            }
            break;
            case SOF_LO: {
                //if (client.available() > 0) {
                    streamByte = client.read();
                    if (streamByte == ((byte) (0xFF & (EVENT_FRAME_FLAG >> 8)))) {
                        frameParseStatus = SOF_HI;
                    }
                //}
            }
            break;
            case SOF_HI: {
                //if (client.available() > 0) {
                    streamByte = client.read();
                    cmdLen = streamByte;
                    frameParseStatus = RECV_CMD_LEN;
                //}
            }
            break;

            case RECV_CMD_LEN: {
                //if (client.available() >= cmdLen) {
                    client.read(cmdBuf, cmdLen);
                    int retByteNum = 0;
                    protocolProcessor.process(cmdBuf);
                    if (retByteNum > 0) {
                        cmdRetBuf[0] = (byte) (0xFF & EVENT_FRAME_FLAG);
                        cmdRetBuf[1] = (byte) (0xFF & (EVENT_FRAME_FLAG >> 8));
                        cmdRetBuf[2] = (byte) (retByteNum & 0xFF);
                        retByteNum += 3;
                        client.write(cmdRetBuf, retByteNum);
                    }

                    frameParseStatus = IDLE;
                    cmdLen = 0;
                //}
            }
            break;

            default:
                break;
        }
    }
}
