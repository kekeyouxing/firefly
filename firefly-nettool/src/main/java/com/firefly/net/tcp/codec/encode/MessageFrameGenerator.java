package com.firefly.net.tcp.codec.encode;

import com.firefly.net.tcp.codec.Generator;
import com.firefly.net.tcp.codec.exception.ProtocolException;
import com.firefly.net.tcp.codec.protocol.Frame;
import com.firefly.net.tcp.codec.protocol.MessageFrame;

import java.nio.ByteBuffer;

import static com.firefly.net.tcp.codec.encode.FrameGenerator.headerGenerator;

/**
 * @author Pengtao Qiu
 */
public class MessageFrameGenerator implements Generator {

    @Override
    public ByteBuffer generate(Object object) {
        MessageFrame messageFrame = (MessageFrame) object;
        if (messageFrame.getData().length > Frame.MAX_PAYLOAD_LENGTH) {
            throw new ProtocolException("The payload length must be not greater than " + Frame.MAX_PAYLOAD_LENGTH);
        }

        ByteBuffer header = headerGenerator.generate(messageFrame);

        int length = Frame.FRAME_HEADER_LENGTH + MessageFrame.MESSAGE_FRAME_HEADER_LENGTH + messageFrame.getData().length;
        ByteBuffer buffer = ByteBuffer.allocate(length);
        buffer.put(header);

        if (messageFrame.isEndStream()) {
            buffer.putInt(Frame.addEndFlag(messageFrame.getStreamId()));
        } else {
            buffer.putInt(Frame.removeEndFlag(messageFrame.getStreamId()));
        }

        if (messageFrame.isEndFrame()) {
            buffer.putShort(Frame.addEndFlag((short) messageFrame.getData().length));
        } else {
            buffer.putShort(Frame.removeEndFlag((short) messageFrame.getData().length));
        }
        buffer.put(messageFrame.getData());
        buffer.flip();
        return buffer;
    }
}
