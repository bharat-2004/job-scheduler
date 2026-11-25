package com.jobscheduler.worker.docker;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Streams a container's stdout/stderr to a file, keeping at most {@code capBytes};
 * anything beyond is dropped and a marker line is appended. A noisy script cannot fill the disk.
 */
class CappedLogCollector extends ResultCallback.Adapter<Frame> {

    private final OutputStream out;
    private final long capBytes;
    private long written;
    private boolean truncated;

    CappedLogCollector(Path file, long capBytes) {
        try {
            this.out = Files.newOutputStream(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.capBytes = capBytes;
    }

    @Override
    public void onNext(Frame frame) {
        byte[] payload = frame.getPayload();
        if (payload == null || truncated) {
            return;
        }
        try {
            long room = capBytes - written;
            if (payload.length <= room) {
                out.write(payload);
                written += payload.length;
            } else {
                out.write(payload, 0, (int) room);
                written += room;
                truncated = true;
                out.write(("\n[log truncated at " + capBytes + " bytes]\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    boolean isTruncated() {
        return truncated;
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            out.close();
        }
    }
}
