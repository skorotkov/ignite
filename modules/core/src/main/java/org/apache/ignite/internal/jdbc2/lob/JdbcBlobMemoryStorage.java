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

import java.util.ArrayList;
import java.util.List;
import org.apache.ignite.internal.util.typedef.internal.U;

/**
 * Memory-based implementation of {@link JdbcBlobStorage}.
 */
public class JdbcBlobMemoryStorage implements JdbcBlobStorage {
    /** The list of buffers. */
    private List<byte[]> buffers = new ArrayList<>();

    /** The total number of bytes in all buffers. */
    private long totalCnt;

    /**
     * Creates a new empty buffer.
     */
    public JdbcBlobMemoryStorage() {
        // No-op
    }

    /**
     * Creates a new buffer enclosing data from the existing byte array.
     *
     * @param arr The byte array.
     */
    public JdbcBlobMemoryStorage(byte[] arr) {
        if (arr.length > 0) {
            buffers.add(arr);

            totalCnt = arr.length;
        }
    }

    /** {@inheritDoc} */
    @Override public long totalCnt() {
        return totalCnt;
    }

    /** {@inheritDoc} */
    @Override public JdbcBlobBufferPointer createPointer() {
        return new JdbcBlobBufferPointer().setContext(new Context(0, 0));
    }

    /** {@inheritDoc} */
    @Override public int read(JdbcBlobBufferPointer pos) {
        if (pos.getPos() >= totalCnt)
            return -1;

        byte[] buf = getBuf(pos);

        int res = buf[getBufPos(pos)] & 0xff;

        advance(pos, 1);

        return res;
    }

    /** {@inheritDoc} */
    @Override public int read(JdbcBlobBufferPointer pos, byte[] res, int off, int cnt) {
        if (pos.getPos() >= totalCnt)
            return -1;

        byte[] buf = getBuf(pos);

        int remaining = cnt;

        while (remaining > 0 && pos.getPos() < totalCnt) {
            int toCopy = Math.min(remaining, buf.length - getBufPos(pos));

            if (toCopy > totalCnt - pos.getPos())
                toCopy = (int) (totalCnt - pos.getPos());

            U.arrayCopy(buf, getBufPos(pos), res, off + (cnt - remaining), toCopy);

            remaining -= toCopy;

            advance(pos, toCopy);
            buf = getBuf(pos);
        }

        return cnt - remaining;
    }

    /** {@inheritDoc} */
    @Override public void write(JdbcBlobBufferPointer pos, int b) {
        if (getBuf(pos) == null)
            addNewBuffer(1);

        getBuf(pos)[getBufPos(pos)] = (byte) (b & 0xff);

        advance(pos, 1);

        totalCnt = Math.max(pos.getPos(), totalCnt);
    }

    /** {@inheritDoc} */
    @Override public void write(JdbcBlobBufferPointer pos, byte[] bytes, int off, int len) {
        int remaining = len;

        byte[] buf;

        while (remaining > 0 && (buf = getBuf(pos)) != null) {
            int toCopy = Math.min(remaining, buf.length - getBufPos(pos));

            U.arrayCopy(bytes, off + len - remaining, buf, getBufPos(pos), toCopy);

            remaining -= toCopy;

            advance(pos, toCopy);
        }

        if (remaining > 0) {
            addNewBuffer(remaining);

            U.arrayCopy(bytes, off + len - remaining, getBuf(pos), 0, remaining);

            advance(pos, remaining);
        }

        totalCnt = Math.max(pos.getPos(), totalCnt);
    }

    /** {@inheritDoc} */
    @Override public void advance(JdbcBlobBufferPointer pos, long step) {
        int inBufPos = getBufPos(pos);
        int idx = getBufIdx(pos);
        long remain = step;

        while (remain > 0) {
            if (remain >= buffers.get(idx).length - inBufPos) {
                remain -= buffers.get(idx).length - inBufPos;

                inBufPos = 0;

                idx++;
            } else {
                inBufPos += Math.toIntExact(remain);

                remain = 0;
            }
        }

        pos.setPos(pos.getPos() + step);

        ((Context)pos.getContext()).set(idx, inBufPos);
    }

    /** {@inheritDoc} */
    @Override public void truncate(long len) {
        JdbcBlobBufferPointer pos = createPointer();

        advance(pos, len);

        if (buffers.size() > getBufIdx(pos) + 1)
            buffers.subList(getBufIdx(pos) + 1, buffers.size()).clear();

        totalCnt = len;
    }

    /** {@inheritDoc} */
    @Override public void close() {
        buffers.clear();
        buffers = null;
    }

    /**
     * Makes a new buffer available
     *
     * @param newCount the new size of the Blob
     */
    private void addNewBuffer(final int newCount) {
        final int newBufSize;

        if (buffers.isEmpty()) {
            newBufSize = newCount;
        } else {
            newBufSize = Math.max(
                    buffers.get(buffers.size() - 1).length << 1,
                    (newCount));
        }

        buffers.add(new byte[newBufSize]);
    }

    /** */
    private byte[] getBuf(JdbcBlobBufferPointer pos) {
        return getBufIdx(pos) < buffers.size() ? buffers.get(getBufIdx(pos)) : null;
    }

    /** */
    private int getBufPos(JdbcBlobBufferPointer pos) {
        return ((Context)pos.getContext()).inBufPos;
    }

    /** */
    private int getBufIdx(JdbcBlobBufferPointer pos) {
        return ((Context)pos.getContext()).idx;
    }

    /**
     * Keeps track of the current position in the current buffer.
     * <p>
     * Saved in the {@link JdbcBlobBufferPointer}.
     */
    private static class Context implements JdbcBlobStorageContext {
        /** Index of the current buffer. */
        private int idx;

        /** Current position in the current buffer. */
        private int inBufPos;

        /**
         * @param idx Index of the current buffer.
         * @param inBufPos Current position in the current buffer.
         */
        public Context(int idx, int inBufPos) {
            set(idx, inBufPos);
        }

        /**
         * @param idx Index of the current buffer.
         * @param inBufPos Current position in the current buffer.
         */
        public void set(int idx, int inBufPos) {
            this.idx = idx;
            this.inBufPos = inBufPos;
        }

        /** {@inheritDoc} */
        @Override public JdbcBlobStorageContext copy() {
            return new Context(idx, inBufPos);
        }
    }
}
