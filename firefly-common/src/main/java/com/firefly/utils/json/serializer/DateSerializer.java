package com.firefly.utils.json.serializer;

import com.firefly.utils.json.JsonWriter;
import com.firefly.utils.json.Serializer;
import com.firefly.utils.time.SafeSimpleDateFormat;

import java.io.IOException;
import java.util.Date;

import static com.firefly.utils.json.JsonStringSymbol.QUOTE;

public class DateSerializer implements Serializer {

    private SafeSimpleDateFormat safeSimpleDateFormat = SafeSimpleDateFormat.defaultDateFormat;

    public DateSerializer() {
    }

    public DateSerializer(String datePattern) {
        this.safeSimpleDateFormat = new SafeSimpleDateFormat(datePattern);
    }

    @Override
    public void convertTo(JsonWriter writer, Object obj) throws IOException {
        writer.write(QUOTE + safeSimpleDateFormat.format((Date) obj) + QUOTE);
    }

}
