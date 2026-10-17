package app.littlenest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class SyncFiles {
    static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() > limit - count) throw new IOException("File exceeds the allowed size");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
    private SyncFiles() {}
}
