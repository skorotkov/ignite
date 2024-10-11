/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.jdbc2.lob;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Buffer storing the binary data either in memory or temporary file on disk depending
 * on the data size.
 *
 * <p>Initially data is stored in memory. Once the size exceeds the {@code maxMemoryBufferBytes} limit
 * it's saved to temporary file.
 *
 * <p>Once switched to the file mode buffer doesn't return to the in-memory even if it's truncated
 * to size lower than limit.
 *
 * <p>If buffer initially wraps the existing data which exceeds the limit it still starts in
 * the in-memory mode. And switches to the file mode only if any write operation increases the
 * buffer size even more.
 *
 * <p>Buffer can start working in read-only mode if created wrapping the existing byte array which
 * can not be modified. Any write operation switches it lazyly to the read-write mode. This allows
 * to prevent the unnecessary data copying.
 *
 * <p>Data is read via the InputStream API and modified via the OutputStream one. Changes done via
 * OutputStream are visible via the InputStream even if InputStream is created before changes done.
 *
 * <p>InputStream and OutputStream created remain valid even if the underlying data storage changed from
 * in-memory to file based.
 *
 * <p>Note however that implementation is not thread-safe.
 */
public class JdbcBlobBuffer {
    /** The underlying data storage. */
    private JdbcBlobStorage storage;

    /** Maximum data size to be stored in-memory. */
    private long maxMemoryBufferBytes;

    /**
     * Create empty buffer.
     *
     * @param maxMemoryBufferBytes Maximum data size to be stored in-memory.
     */
    public JdbcBlobBuffer(long maxMemoryBufferBytes) {
        storage = new JdbcBlobMemoryStorage();

        this.maxMemoryBufferBytes = maxMemoryBufferBytes;
    }

    /**
     * Create buffer which wraps the existing byte array and starts working in the read-only mode.
     *
     * @param maxMemoryBufferBytes Maximum data size to be stored in-memory.
     * @param arr The byte array to be wrapped.
     * @param off The offset to the first byte to be wrapped.
     * @param len The length in bytes of the data to be wrapped.
     */
    public JdbcBlobBuffer(long maxMemoryBufferBytes, byte[] arr, int off, int len) {
        storage = new JdbcBlobReadOnlyMemoryStorage(arr, off, len);

        this.maxMemoryBufferBytes = maxMemoryBufferBytes;
    }

    /**
     * Create buffer which wraps the existing byte array and starts working in the read-write mode.
     *
     * @param maxMemoryBufferBytes Maximum data size to be stored in-memory.
     * @param arr The byte array to be wrapped.
     */
    public JdbcBlobBuffer(long maxMemoryBufferBytes, byte[] arr) {
        storage = new JdbcBlobMemoryStorage(arr);

        this.maxMemoryBufferBytes = maxMemoryBufferBytes;
    }

    /**
     * Create buffer from the another one.
     *
     * <p>Sharing of the underlying storage is intended.
     *
     * @param other Other buffer.
     */
    public JdbcBlobBuffer(JdbcBlobBuffer other) {
        storage = other.storage;

        maxMemoryBufferBytes = other.maxMemoryBufferBytes;
    }

    /**
     * @return Total number of bytes in the buffer.
     */
    public long totalCnt() {
        return storage.totalCnt();
    }

    /**
     * Provide OutputStream through which the data can be written to buffer starting from
     * the (zero-based) position.
     *
     * @param pos The zero-based offset to the first byte to be written. Must not be negative
     *            or greater than total count of bytes in buffer.
     *
     * @return OutputStream instance.
     */
    public OutputStream getOutputStream(long pos) {
        long totalCnt = storage.totalCnt();

        if (pos < 0 || pos > totalCnt)
            throw new IndexOutOfBoundsException("Position cannot be negative or greater than total count of bytes in buffer " +
                    "[pos=" + pos + ", totalCnt=" + totalCnt + "]");

        return new BufferOutputStream(pos);
    }

    /**
     * Provide InputStream through which the data can be read starting from the
     * begining.
     *
     * <p>Stream is not limited meaning that it would return any new data
     * written to the buffer after stream creation.
     *
     * @return InputStream instance.
     */
    public InputStream getInputStream() {
        return new BufferInputStream();
    }

    /**
     * Provide InputStream through which the no more than {@code len} bytes can be read
     * from buffer starting from the specified zero-based position {@code pos}.
     *
     * @param pos The zero-based offset to the first byte to be retrieved. Must not be negative
     *            or greater than total count of bytes in buffer.
     * @param len The length in bytes of the data to be retrieved. Must not be negative.
     * @return InputStream instance.
     */
    public InputStream getInputStream(long pos, long len) {
        if (len < 0)
            throw new IndexOutOfBoundsException("Length cannot be negative [len=" + len + "]");

        long totalCnt = storage.totalCnt();

        if (pos < 0 || pos >= totalCnt)
            throw new IndexOutOfBoundsException("Position cannot be negative and should be less than total count of bytes in buffer " +
                    "[pos=" + pos + ", totalCnt=" + totalCnt + "]");

        return new BufferInputStream(pos, len);
    }

    /**
     * Truncate the buffer to the specified length.
     *
     * @param len The length in bytes to be truncated. Must not be negative
     *            or greater than total count of bytes in buffer.
     */
    public void truncate(long len) throws IOException {
        long totalCnt = storage.totalCnt();

        if (len < 0 || len > totalCnt)
            throw new IndexOutOfBoundsException("Length cannot be negative or greater than total count of bytes in buffer " +
                    "[len=" + len + ", totalCnt=" + totalCnt + "]");

        try {
            storage.truncate(len);
        }
        catch (UnsupportedOperationException e) {
            switchToReadWriteMemoryStorage();

            storage.truncate(len);
        }
    }

    /**
     * Close the buffer and free any resources.
     */
    public void close() {
        storage.close();
    }

    /**
     * Get copy of the buffer data.
     *
     * @return Byte array containing buffer data.
     */
    public byte[] getData() throws IOException {
        byte[] bytes = new byte[Math.toIntExact(totalCnt())];

        getInputStream().read(bytes);

        return bytes;
    }

    /**
     *
     */
    public void setMaxMemoryBufferBytes(long maxMemoryBufferBytes) {
        this.maxMemoryBufferBytes = maxMemoryBufferBytes;
    }

    /**
     * Switch buffer to RW in-memory mode.
     *
     * <p>Copies all data from the RO in-memory storage to the RW in-memory storage.
     */
    private void switchToReadWriteMemoryStorage() throws IOException {
        if (!(storage instanceof JdbcBlobReadOnlyMemoryStorage))
            return;

        byte[] data = new byte[Math.toIntExact(storage.totalCnt())];

        storage.read(storage.createPointer(), data, 0, data.length);

        JdbcBlobStorage newStorage = new JdbcBlobMemoryStorage(data);

        storage.close();

        storage = newStorage;
    }

    /**
     * Input stream to read data from buffer.
     */
    private class BufferInputStream extends InputStream {
        /** Current position in the buffer. */
        private final JdbcBlobBufferPointer curPointer;

        /** Stream starting position. */
        private final long start;

        /** Stream length limit. May be null which means no limit. */
        private final Long limit;

        /** Remembered buffer position at the moment the {@link InputStream#mark} is called. */
        private final JdbcBlobBufferPointer markedPointer;

        /**
         * Create unlimited stream to read all data from the buffer starting from the beginning.
         */
        private BufferInputStream() {
            this(0, null);
        }

        /**
         * Create stream to read data from the buffer starting from the specified {@code start}
         * zere-based position.
         *
         * @param start The zero-based offset to the first byte to be retrieved.
         * @param limit The maximim length in bytes of the data to be retrieved. Unlimited if null.
         */
        private BufferInputStream(long start, Long limit) {
            this.start = start;

            this.limit = limit;

            curPointer = storage.createPointer();

            if (start > 0)
                storage.advance(curPointer, start);

            markedPointer = storage.createPointer().set(curPointer);
        }

        /** {@inheritDoc} */
        @Override public int read() throws IOException {
            if (limit != null && curPointer.getPos() >= start + limit)
                return -1;

            return storage.read(curPointer);
        }

        /** {@inheritDoc} */
        @Override public int read(byte[] res, int off, int cnt) throws IOException {
            int toRead = cnt;

            if (limit != null) {
                if (curPointer.getPos() >= start + limit)
                    return -1;

                long availableBytes = start + limit - curPointer.getPos();

                if (cnt > availableBytes)
                    toRead = (int)availableBytes;
            }

            return storage.read(curPointer, res, off, toRead);
        }

        /** {@inheritDoc} */
        @Override public boolean markSupported() {
            return true;
        }

        /** {@inheritDoc} */
        @Override public synchronized void reset() {
            curPointer.set(markedPointer);
        }

        /** {@inheritDoc} */
        @Override public synchronized void mark(int readlimit) {
            markedPointer.set(curPointer);
        }

        /** {@inheritDoc} */
        @Override public long skip(long n) {
            long toSkip = Math.min(n, storage.totalCnt() - curPointer.getPos());

            if (toSkip > 0)
                storage.advance(curPointer, toSkip);

            return toSkip;
        }
    }

    /**
     * Output stream to write data to buffer.
     */
    private class BufferOutputStream extends OutputStream {
        /** Current position in the buffer. */
        private final JdbcBlobBufferPointer bufPos;

        /**
         * Create stream to write data to the buffer starting from the specified position {@code pos}.
         *
         * @param pos Starting position (zero-based).
         */
        private BufferOutputStream(long pos) {
            bufPos = storage.createPointer();

            if (pos > 0)
                storage.advance(bufPos, pos);
        }

        /** {@inheritDoc} */
        @Override public void write(int b) throws IOException {
            if (Math.max(bufPos.getPos() + 1, storage.totalCnt()) > maxMemoryBufferBytes)
                switchToFileStorage();

            try {
                storage.write(bufPos, b);
            }
            catch (UnsupportedOperationException ignored) {
                switchToReadWriteMemoryStorage();

                storage.write(bufPos, b);
            }
        }

        /** {@inheritDoc} */
        @Override public void write(byte[] bytes, int off, int len) throws IOException {
            if (Math.max(bufPos.getPos() + len, storage.totalCnt()) > maxMemoryBufferBytes)
                switchToFileStorage();

            try {
                storage.write(bufPos, bytes, off, len);
            }
            catch (UnsupportedOperationException ignored) {
                switchToReadWriteMemoryStorage();

                storage.write(bufPos, bytes, off, len);
            }
        }

        /**
         * Switch buffer to file mode.
         *
         * <p>Copies all data from the in-memory storage to the temporary file storage.
         */
        private void switchToFileStorage() throws IOException {
            if (storage instanceof JdbcBlobTmpFileStorage)
                return;

            JdbcBlobStorage newStorage = new JdbcBlobTmpFileStorage(getInputStream());

            storage.close();

            storage = newStorage;
        }
    }
}
