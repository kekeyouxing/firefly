package com.firefly.codec.http2.hpack;

import com.firefly.codec.http2.encode.HttpFieldPreEncoder;
import com.firefly.codec.http2.model.HttpHeader;
import com.firefly.codec.http2.model.HttpVersion;
import com.firefly.utils.io.BufferUtils;

import java.nio.ByteBuffer;

public class HpackFieldPreEncoder implements HttpFieldPreEncoder {

    @Override
    public HttpVersion getHttpVersion() {
        return HttpVersion.HTTP_2;
    }

    @Override
    public byte[] getEncodedField(HttpHeader header, String name, String value) {
        boolean not_indexed = HpackEncoder.__DO_NOT_INDEX.contains(header);

        ByteBuffer buffer = BufferUtils.allocate(name.length() + value.length() + 10);
        BufferUtils.clearToFill(buffer);
        boolean huffman;
        int bits;

        if (not_indexed) {
            // Non indexed field
            boolean never_index = HpackEncoder.__NEVER_INDEX.contains(header);
            huffman = !HpackEncoder.__DO_NOT_HUFFMAN.contains(header);
            buffer.put(never_index ? (byte) 0x10 : (byte) 0x00);
            bits = 4;
        } else if (header == HttpHeader.CONTENT_LENGTH && value.length() > 1) {
            // Non indexed content length for 2 digits or more
            buffer.put((byte) 0x00);
            huffman = true;
            bits = 4;
        } else {
            // indexed
            buffer.put((byte) 0x40);
            huffman = !HpackEncoder.__DO_NOT_HUFFMAN.contains(header);
            bits = 6;
        }

        int name_idx = HpackContext.staticIndex(header);
        if (name_idx > 0)
            NBitInteger.encode(buffer, bits, name_idx);
        else {
            buffer.put((byte) 0x80);
            NBitInteger.encode(buffer, 7, Huffman.octetsNeededLC(name));
            Huffman.encodeLC(buffer, name);
        }

        HpackEncoder.encodeValue(buffer, huffman, value);

        BufferUtils.flipToFlush(buffer, 0);
        return BufferUtils.toArray(buffer);
    }
}
