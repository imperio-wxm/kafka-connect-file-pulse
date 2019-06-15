/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.streamthoughts.kafka.connect.filepulse.reader.internal;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

import io.streamthoughts.kafka.connect.filepulse.reader.ReaderException;
import org.apache.kafka.connect.errors.ConnectException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A BufferedReader wrapper to read lines in non-blocking way.
 */
public class NonBlockingBufferReader implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(NonBlockingBufferReader.class);

    public static final int DEFAULT_INITIAL_CAPACITY = 4096;

    private final InputStream stream;

    private final int initialCapacity;

    private final Charset charset;

    private BufferedReader reader;

    // The current bytes position.
    private Long offset = 0L;

    // The buffer used to read extract lines from the iterator file.
    private char[] buffer;

    // The current buffer positions.
    private int bufferOffset = 0;

    // Number of bytes read during last iteration.
    private int nread = -1;

    private boolean autoFlush = true;

    /**
     * Creates a new {@link NonBlockingBufferReader} instance.
     *
     * @param file         the input file pointer.
     * @param charset      the input file charset.
     */
    public NonBlockingBufferReader(final File file,
                                   final Charset charset) {
        this(file, DEFAULT_INITIAL_CAPACITY, charset);
    }

    /**
     * Creates a new {@link NonBlockingBufferReader} instance.
     *
     * @param file            the input file pointer.
     * @param initialCapacity the buffer initial capacity.
     * @param charset         the input file charset.
     */
    public NonBlockingBufferReader(final File file,
                                   final int initialCapacity,
                                   final Charset charset) {
        Objects.requireNonNull(file, "file can't be null");
        this.initialCapacity = initialCapacity;
        this.buffer = new char[initialCapacity];
        this.charset = charset;
        try {
            LOG.debug("Opening file {}", file);
            this.stream = new FileInputStream(file);
        } catch (FileNotFoundException e) {
            throw new ReaderException("Can't found source file : " + file);
        }
        this.reader = new BufferedReader(new InputStreamReader(stream, charset));
    }

    public Charset charset() {
        return charset;
    }

    public long position() {
        return offset;
    }

    /**
     * Enables auto-flush; The reader will automatically
     * flush all remaining buffered bytes as a single line when EOF is reached.
     */
    public void enableAutoFlush() {
        this.autoFlush = true;
    }

    /**
     * Disable auto-flush; Reader will not automatically
     * flush all remaining buffered bytes when EOF is reached.
     */
    public void disableAutoFlush() {
        this.autoFlush = false;
    }

    public List<TextBlock> readLines(int minRecords) throws IOException {
        // Unfortunately we can't just use readLine() because it blocks in an uninterruptible way.
        // Instead we have to manage splitting lines ourselves, using simple backoff when no new data
        // is available.
        final List<TextBlock> records = new LinkedList<>();
        nread = 0;
        while (reader.ready() && (records.isEmpty()|| records.size() < minRecords)) {
            nread = reader.read(buffer, bufferOffset, buffer.length - bufferOffset);
            if (nread > 0) {
                bufferOffset += nread;
                TextBlock line;
                do {
                    line = tryToExtractLine();
                    if (line != null) {
                        records.add(line);
                    }
                } while (line != null);

                if (records.isEmpty() && bufferOffset == buffer.length) {
                    char[] newbuf = new char[buffer.length * 2];
                    System.arraycopy(buffer, 0, newbuf, 0, buffer.length);
                    buffer = newbuf;
                }
            }
        }

        if (!hasNext() && remaining() && autoFlush) {
            LOG.info("End of file reached - flushing remaining bytes from reader buffer.");
            final String line = new String(buffer, 0, bufferOffset);
            records.add(new TextBlock(line, charset, offset, offset + bufferOffset, bufferOffset));
            offset+=bufferOffset;
            bufferOffset = 0;
        }
        return records;
    }

    /**
     * Checks whether there is still remaining bytes in the internal buffer.
     *
     * @return  {@code true} if there is bytes already read.
     */
    public boolean remaining() {
        return bufferOffset != 0;
    }

    public boolean hasNext() {
        try {
            return (nread > 0 || nread == -1) && reader.ready();
        } catch (IOException e) {
            LOG.error("Error while checking for remaining bytes to read", e.getLocalizedMessage());
            return false;
        }
    }

    public void seekTo(final Long offset) {
        if (offset != null && offset > 0) {
            buffer = new char[initialCapacity];
            LOG.debug("Trying to skip to file bufferOffset bytes {}", offset);
            long skipLeft = offset;
            while (skipLeft > 0) {
                try {
                    long skipped = reader.skip(skipLeft);
                    skipLeft -= skipped;
                } catch (IOException e) {
                    LOG.error("Error while trying to seek to previous bufferOffset bytes in file: ", e);
                    throw new ConnectException(e);
                }
            }
            LOG.debug("Skipped to bufferOffset bytes {}", offset);
            this.offset = offset;
        } else {
            this.offset = 0L;
        }
    }

    private TextBlock tryToExtractLine() {
        int until = -1, newStart = -1;
        for (int i = 0; i < bufferOffset; i++) {
            if (buffer[i] == '\n') {
                until = i;
                newStart = i + 1;
                break;
            } else if (buffer[i] == '\r') {
                // We need to check for \r\n, so we must skip this if we can't check the next char
                if (i + 1 >= bufferOffset) {
                    return null;
                }

                until = i;
                newStart = (buffer[i + 1] == '\n') ? i + 2 : i + 1;
                break;
            }
        }

        TextBlock result = null;
        if (until != -1) {
            final String line = new String(buffer, 0, until);
            result =  new TextBlock(line, charset,  offset, offset + newStart ,until);
            System.arraycopy(buffer, newStart, buffer, 0, buffer.length - newStart);
            bufferOffset = bufferOffset - newStart;
        }

        if (newStart != -1) {
            offset += newStart;
        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        try {
            if (stream != null && stream != System.in) {
                stream.close();
                LOG.trace("Closed input stream");
            }
        } catch (IOException e) {
            LOG.error("Failed to close NonBlockingBufferReader stream : ", e);
        }
    }
}