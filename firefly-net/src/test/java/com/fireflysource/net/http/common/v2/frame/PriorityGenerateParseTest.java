package com.fireflysource.net.http.common.v2.frame;

import com.fireflysource.net.http.common.v2.decoder.Parser;
import com.fireflysource.net.http.common.v2.encoder.FrameBytes;
import com.fireflysource.net.http.common.v2.encoder.HeaderGenerator;
import com.fireflysource.net.http.common.v2.encoder.PriorityGenerator;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriorityGenerateParseTest {

    @Test
    void testGenerateParse() {
        PriorityGenerator generator = new PriorityGenerator(new HeaderGenerator());

        final List<PriorityFrame> frames = new ArrayList<>();
        Parser parser = new Parser(new Parser.Listener.Adapter() {
            @Override
            public void onPriority(PriorityFrame frame) {
                frames.add(frame);
            }
        }, 4096, 8192);
        parser.init(UnaryOperator.identity());

        int streamId = 13;
        int parentStreamId = 17;
        int weight = 256;
        boolean exclusive = true;

        // Iterate a few times to be sure generator and parser are properly reset.
        for (int i = 0; i < 2; ++i) {
            FrameBytes frameBytes = generator.generatePriority(streamId, parentStreamId, weight, exclusive);

            frames.clear();
            for (ByteBuffer buffer : frameBytes.getByteBuffers()) {
                while (buffer.hasRemaining()) {
                    parser.parse(buffer);
                }
            }
        }

        assertEquals(1, frames.size());
        PriorityFrame frame = frames.get(0);
        assertEquals(streamId, frame.getStreamId());
        assertEquals(parentStreamId, frame.getParentStreamId());
        assertEquals(weight, frame.getWeight());
        assertEquals(exclusive, frame.isExclusive());
    }

    @Test
    void testGenerateParseOneByteAtATime() {
        PriorityGenerator generator = new PriorityGenerator(new HeaderGenerator());

        final List<PriorityFrame> frames = new ArrayList<>();
        Parser parser = new Parser(new Parser.Listener.Adapter() {
            @Override
            public void onPriority(PriorityFrame frame) {
                frames.add(frame);
            }
        }, 4096, 8192);
        parser.init(UnaryOperator.identity());

        int streamId = 13;
        int parentStreamId = 17;
        int weight = 3;

        // Iterate a few times to be sure generator and parser are properly reset.
        for (int i = 0; i < 2; ++i) {
            FrameBytes frameBytes = generator.generatePriority(streamId, parentStreamId, weight, true);

            frames.clear();
            for (ByteBuffer buffer : frameBytes.getByteBuffers()) {
                while (buffer.hasRemaining()) {
                    parser.parse(ByteBuffer.wrap(new byte[]{buffer.get()}));
                }
            }

            assertEquals(1, frames.size());
            PriorityFrame frame = frames.get(0);
            assertEquals(streamId, frame.getStreamId());
            assertEquals(parentStreamId, frame.getParentStreamId());
            assertEquals(weight, frame.getWeight());
            assertTrue(frame.isExclusive());
        }
    }
}
