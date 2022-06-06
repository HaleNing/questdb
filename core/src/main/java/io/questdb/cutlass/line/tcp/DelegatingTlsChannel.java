/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cutlass.line.tcp;

import io.questdb.cutlass.line.LineChannel;
import io.questdb.network.NetworkError;
import io.questdb.std.Misc;
import io.questdb.std.Unsafe;
import io.questdb.std.Vect;
import org.jetbrains.annotations.NotNull;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

public final class DelegatingTlsChannel implements LineChannel {
    private static final int INITIAL_BUFFER_CAPACITY = 64 * 1024;
    private static final long ADDRESS_FIELD_OFFSET;
    private static final long LIMIT_FIELD_OFFSET;
    private static final long CAPACITY_FIELD_OFFSET;

    private static final int INITIAL_STATE = 0;
    private static final int AFTER_HANDSHAKE = 1;
    private static final int CLOSING = 2;
    private static final int CLOSED = 3;


    private final LineChannel upstream;
    private final SSLEngine sslEngine;

    private final ByteBuffer wrapInputBuffer;
    private ByteBuffer wrapOutputBuffer;
    private ByteBuffer unwrapInputBuffer;
    private ByteBuffer unwrapOutputBuffer;
    private final ByteBuffer dummyBuffer;

    private long wrapOutputBufferPtr;
    private long unwrapInputBufferPtr;
    private long unwrapOutputBufferPtr;

    private int state = INITIAL_STATE;

    private static final TrustManager ALLOW_ALL_TRUSTMANAGER = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {

        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {

        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    static {
        Field addressField;
        Field limitField;
        Field capacityField;
        try {
            // todo: is reflection a good idea?
            addressField = Buffer.class.getDeclaredField("address");
            limitField = Buffer.class.getDeclaredField("limit");
            capacityField = Buffer.class.getDeclaredField("capacity");
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
        ADDRESS_FIELD_OFFSET = Unsafe.getUnsafe().objectFieldOffset(addressField);
        LIMIT_FIELD_OFFSET = Unsafe.getUnsafe().objectFieldOffset(limitField);
        CAPACITY_FIELD_OFFSET = Unsafe.getUnsafe().objectFieldOffset(capacityField);
    }

    public DelegatingTlsChannel(LineChannel upstream) {
        this(upstream, null, null);
    }

    public DelegatingTlsChannel(LineChannel upstream, String trustStorePath, char[] password) {
        this.upstream = upstream;
        this.sslEngine = createSslEngine(trustStorePath, password);

        // wrapInputBuffer is just a placeholder, we set the internal address, capacity and limit in send()
        this.wrapInputBuffer = ByteBuffer.allocateDirect(0);

        this.wrapOutputBuffer = ByteBuffer.allocateDirect(INITIAL_BUFFER_CAPACITY);
        this.unwrapInputBuffer = ByteBuffer.allocateDirect(INITIAL_BUFFER_CAPACITY);
        this.unwrapOutputBuffer = ByteBuffer.allocateDirect(INITIAL_BUFFER_CAPACITY);
        this.wrapOutputBufferPtr = Unsafe.getUnsafe().getLong(wrapOutputBuffer, ADDRESS_FIELD_OFFSET);
        this.unwrapInputBufferPtr = Unsafe.getUnsafe().getLong(unwrapInputBuffer, ADDRESS_FIELD_OFFSET);
        this.unwrapOutputBufferPtr = Unsafe.getUnsafe().getLong(unwrapOutputBuffer, ADDRESS_FIELD_OFFSET);
        this.dummyBuffer = ByteBuffer.allocate(0);
    }

    private static SSLEngine createSslEngine(String trustStorePath, char[] trustStorePassword) {
        try {
            SSLContext sslContext;
            // intentionally not exposed to end user as an option
            // it's used for testing, but dangerous in prod
            if (trustStorePath != null) {
                sslContext = SSLContext.getInstance("SSL");
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                KeyStore jks = KeyStore.getInstance("JKS");

                InputStream trustStoreStream = null;
                try {
                    if (trustStorePath.startsWith("classpath:")) {
                        String adjustedPath = trustStorePath.substring("classpath:".length());
                        trustStoreStream = DelegatingTlsChannel.class.getResourceAsStream(adjustedPath);
                        if (trustStoreStream == null) {
                            throw new IllegalStateException("Configured trust at classpath:" + trustStorePath + " is unavailable on a classpath");
                        }
                    } else {
                        trustStoreStream = new BufferedInputStream(new FileInputStream(trustStorePath));
                    }
                    jks.load(trustStoreStream, trustStorePassword);
                } finally {
                    if (trustStoreStream != null) {
                        trustStoreStream.close();
                    }
                }
                tmf.init(jks);
                TrustManager[] trustManagers = tmf.getTrustManagers();
                sslContext.init(null, trustManagers, new SecureRandom());
            } else {
                sslContext = SSLContext.getDefault();
            }
            SSLEngine sslEngine = sslContext.createSSLEngine();
            sslEngine.setUseClientMode(true);
            return sslEngine;
        } catch (NoSuchAlgorithmException | KeyManagementException | CertificateException | KeyStoreException |
                 IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void send(long ptr, int len) {
        try {
            handshakeIfNeeded();
            setBufferToPointer(wrapInputBuffer, ptr, len);
            wrapLoop(wrapInputBuffer);
            assert !wrapInputBuffer.hasRemaining();
            writeToUpstreamAndClear();
        } catch (SSLException e) {
            throw new RuntimeException(e);
        }
    }

    private static void setBufferToPointer(ByteBuffer buffer, long ptr, int len) {
        assert buffer.isDirect();
        Unsafe.getUnsafe().putLong(buffer, ADDRESS_FIELD_OFFSET, ptr);
        Unsafe.getUnsafe().putLong(buffer, LIMIT_FIELD_OFFSET, len);
        Unsafe.getUnsafe().putLong(buffer, CAPACITY_FIELD_OFFSET, len);
        buffer.position(0);
    }

    private void handshakeIfNeeded() throws SSLException {
        if (state != INITIAL_STATE) {
            return;
        }

        // trigger handshaking - otherwise the initial state is NOT_HANDSHAKING
        sslEngine.beginHandshake();
        for (;;) {
            SSLEngineResult.HandshakeStatus status = sslEngine.getHandshakeStatus();
            switch (status) {
                case NOT_HANDSHAKING:
                    state = AFTER_HANDSHAKE;
                    return;
                case FINISHED:
                    throw new IllegalStateException("getHandshakeStatus() returns FINISHED. This is not possible.");
                case NEED_TASK:
                    sslEngine.getDelegatedTask().run();
                    break;
                case NEED_WRAP:
                    wrapLoop(dummyBuffer);
                    writeToUpstreamAndClear();
                    break;
                case NEED_UNWRAP:
                    unwrapLoop();
                    break;
                case NEED_UNWRAP_AGAIN:
                    // fall-through
                default:
                    throw new UnsupportedOperationException(status + "not implemented yet");
            }
        }
    }

    private void growWrapOutputBuffer() {
        wrapOutputBuffer = expandBuffer(wrapOutputBuffer);
        wrapOutputBufferPtr = Unsafe.getUnsafe().getLong(wrapOutputBuffer, ADDRESS_FIELD_OFFSET);
    }

    private void growUnwrapOutputBuffer() {
        unwrapOutputBuffer = expandBuffer(unwrapOutputBuffer);
        unwrapOutputBufferPtr = Unsafe.getUnsafe().getLong(unwrapOutputBuffer, ADDRESS_FIELD_OFFSET);
    }

    private void growUnwrapInputBuffer() {
        unwrapInputBuffer = expandBuffer(unwrapInputBuffer);
        unwrapInputBufferPtr = Unsafe.getUnsafe().getLong(unwrapInputBuffer, ADDRESS_FIELD_OFFSET);
    }

    @NotNull
    private static ByteBuffer expandBuffer(ByteBuffer buffer) {
        // do we need a cap on max size?
        ByteBuffer newBuffer = ByteBuffer.allocateDirect(buffer.capacity() * 2);
        buffer.flip();
        return newBuffer.put(buffer);
    }

    private void wrapLoop(ByteBuffer src) throws SSLException {
        for (;;) {
            SSLEngineResult result = sslEngine.wrap(src, wrapOutputBuffer);
            switch (result.getStatus()) {
                case BUFFER_UNDERFLOW:
                    throw new IllegalStateException("should not happen");
                case BUFFER_OVERFLOW:
                    growWrapOutputBuffer();
                    break;
                case OK:
                    assert !src.hasRemaining();
                    return;
                case CLOSED:
                    if (state != CLOSING) {
                        throw new IllegalStateException("Connection closed");
                    }
                    return;
            }
        }
    }

    private void unwrapLoop() throws SSLException {
        for (;;) {
            if (unwrapOutputBuffer.position() != 0) {
                // we have some decoded data ready to be read
                // no need to unwrap more
                return;
            }

            readFromUpstream(false);
            unwrapInputBuffer.flip();
            SSLEngineResult result = sslEngine.unwrap(unwrapInputBuffer, unwrapOutputBuffer);
            unwrapInputBuffer.compact();
            switch (result.getStatus()) {
                case BUFFER_UNDERFLOW:
                    // we need more input no matter what. so let's force reading from the upstream channel
                    readFromUpstream(true);
                    break;
                case BUFFER_OVERFLOW:
                    if (unwrapOutputBuffer.position() != 0) {
                        // we have at least something, that's enough
                        // if it's not enough then it's up to the caller to call us again
                        return;
                    }

                    // there was overflow and we have nothing
                    // apparently the output buffer cannot fit even a single TLS record. let's grow it!
                    growUnwrapOutputBuffer();
                    break;
                case OK:
                    return;
                case CLOSED:
                    throw new IllegalStateException("connection closed");
            }
        }
    }

    private void writeToUpstreamAndClear() {
        assert wrapOutputBuffer.limit() == wrapOutputBuffer.capacity();

        // we don't flip the wrapOutputBuffer before reading from it
        // hence the writer position is the actual length to be sent to the upstream channel
        int len = wrapOutputBuffer.position();

        assert Unsafe.getUnsafe().getLong(wrapOutputBuffer, ADDRESS_FIELD_OFFSET) == wrapOutputBufferPtr;
        upstream.send(wrapOutputBufferPtr, len);

        // we know limit == capacity
        // thus setting the position to 0 is equivalent to clearing
        wrapOutputBuffer.position(0);
    }

    private void readFromUpstream(boolean force) {
        if (unwrapInputBuffer.position() != 0 && !force) {
            // we don't want to block on receive() if there are still data to be processed
            // unless we are forced to do so
            return;
        }

        assert unwrapInputBuffer.limit() == unwrapInputBuffer.capacity();
        int remainingLen = unwrapInputBuffer.remaining();
        if (remainingLen == 0) {
            growUnwrapInputBuffer();
            remainingLen = unwrapInputBuffer.remaining();
        }
        assert Unsafe.getUnsafe().getLong(unwrapInputBuffer, ADDRESS_FIELD_OFFSET) == unwrapInputBufferPtr;
        long adjustedPtr = unwrapInputBufferPtr + unwrapInputBuffer.position();

        int receive = upstream.receive(adjustedPtr, remainingLen);
        if (receive < 0) {
            throw new IllegalStateException("connection closed");
        }
        unwrapInputBuffer.position(unwrapInputBuffer.position() + receive);
    }

    @Override
    public int receive(long ptr, int len) {
        try {
            handshakeIfNeeded();

            unwrapLoop();
            unwrapOutputBuffer.flip();
            int i = unwrapOutputBufferToPtr(ptr, len);
            unwrapOutputBuffer.compact();
            return i;
        } catch (SSLException e) {
            throw new RuntimeException(e);
        }
    }

    private int unwrapOutputBufferToPtr(long dstPtr, int dstLen) {
        int oldPosition = unwrapOutputBuffer.position();

        assert Unsafe.getUnsafe().getLong(unwrapOutputBufferPtr, ADDRESS_FIELD_OFFSET) == unwrapOutputBufferPtr;
        long srcPtr = unwrapOutputBufferPtr + oldPosition;
        int srcLen = unwrapOutputBuffer.remaining();
        int len = Math.min(dstLen, srcLen);
        Vect.memcpy(dstPtr, srcPtr, len);
        unwrapOutputBuffer.position(oldPosition + len);
        return len;
    }

    @Override
    public int errno() {
        // for now, we throw exception eagerly so this is not really useful
        return 0;
    }

    @Override
    public void close() throws IOException {
        state = CLOSING;
        sslEngine.closeOutbound();
        wrapLoop(dummyBuffer);
        try {
            writeToUpstreamAndClear();
        } catch (NetworkError e) {
            // best effort TLS close
        }
        Misc.free(upstream);
        state = CLOSED;
    }
}
