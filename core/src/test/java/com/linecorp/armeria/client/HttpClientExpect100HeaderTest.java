/*
 * Copyright 2024 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client;

import static com.linecorp.armeria.client.Http2ClientSettingsTest.payloadLength;
import static com.linecorp.armeria.client.Http2ClientSettingsTest.readBytes;
import static io.netty.handler.codec.http2.Http2CodecUtil.FRAME_HEADER_LENGTH;
import static io.netty.handler.codec.http2.Http2CodecUtil.connectionPrefaceBuf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import org.apache.hc.core5.http2.hpack.HPackEncoder;
import org.apache.hc.core5.util.ByteArrayBuffer;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.stream.AbortedStreamException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http2.DefaultHttp2HeadersDecoder;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Flags;
import io.netty.handler.codec.http2.Http2FrameTypes;
import io.netty.handler.codec.http2.Http2Headers;

/**
 * This test is to check the behavior of the HttpClient when the 'Expect: 100-continue' header is set.
 */
final class HttpClientExpect100HeaderTest {

    @Nested
    class AggregatedHttpRequestHandlerTest {
        @Test
        void continueToSendHttp1Request() throws Exception {
            try (ServerSocket ss = new ServerSocket(0)) {
                final int port = ss.getLocalPort();
                final WebClient client = WebClient.of("h1c://127.0.0.1:" + port);
                client.prepare()
                      .post("/")
                      .content("foo")
                      .header(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE)
                      .execute()
                      .aggregate();

                try (Socket s = ss.accept()) {
                    final BufferedReader in = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                    final OutputStream out = s.getOutputStream();
                    assertThat(in.readLine()).isEqualTo("POST / HTTP/1.1");
                    assertThat(in.readLine()).startsWith("host: 127.0.0.1:");
                    assertThat(in.readLine()).isEqualTo("content-type: text/plain; charset=utf-8");
                    assertThat(in.readLine()).isEqualTo("expect: 100-continue");
                    assertThat(in.readLine()).isEqualTo("content-length: 3");
                    assertThat(in.readLine()).startsWith("user-agent: armeria/");
                    assertThat(in.readLine()).isEmpty();

                    out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));

                    assertThat(in.readLine()).isEqualTo("foo");

                    out.write(("HTTP/1.1 201 Created\r\n" +
                               "Connection: close\r\n" +
                               "Content-Length: 0\r\n" +
                               "\r\n").getBytes(StandardCharsets.US_ASCII));

                    assertThat(in.readLine()).isNull();
                }
            }
        }

        @Test
        void failToSendHttp1Request() throws Exception {
            try (ServerSocket ss = new ServerSocket(0)) {
                final int port = ss.getLocalPort();

                final WebClient client = WebClient.of("h1c://127.0.0.1:" + port);
                client.prepare()
                      .post("/")
                      .content("foo")
                      .header(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE)
                      .execute()
                      .aggregate();

                try (Socket s = ss.accept()) {
                    final BufferedReader in = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                    final OutputStream out = s.getOutputStream();
                    assertThat(in.readLine()).isEqualTo("POST / HTTP/1.1");
                    assertThat(in.readLine()).startsWith("host: 127.0.0.1:");
                    assertThat(in.readLine()).isEqualTo("content-type: text/plain; charset=utf-8");
                    assertThat(in.readLine()).isEqualTo("expect: 100-continue");
                    assertThat(in.readLine()).isEqualTo("content-length: 3");
                    assertThat(in.readLine()).startsWith("user-agent: armeria/");
                    assertThat(in.readLine()).isEmpty();

                    out.write(("HTTP/1.1 417 Expectation Failed\r\n" +
                               "Connection: close\r\n" +
                               "\r\n").getBytes(StandardCharsets.US_ASCII));

                    assertThat(in.readLine()).isNull();
                }
            }
        }

        @Test
        void continueToSendHttp2Request() throws Exception {
            try (ServerSocket ss = new ServerSocket(0);
                 ClientFactory clientFactory =
                         ClientFactory.builder()
                                      .useHttp2Preface(true)
                                      .http2InitialConnectionWindowSize(Http2CodecUtil.DEFAULT_WINDOW_SIZE)
                                      .http2InitialStreamWindowSize(Http2CodecUtil.DEFAULT_WINDOW_SIZE)
                                      .build()) {
                final int port = ss.getLocalPort();
                final WebClient client = WebClient.builder("http://127.0.0.1:" + port)
                                                  .factory(clientFactory)
                                                  .build();
                final CompletableFuture<AggregatedHttpResponse> future =
                        client.prepare()
                              .post("/")
                              .content("foo")
                              .header(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE)
                              .execute()
                              .aggregate();

                try (Socket s = ss.accept()) {
                    final InputStream in = s.getInputStream();
                    final BufferedOutputStream bos = new BufferedOutputStream(s.getOutputStream());

                    // Read the connection preface and discard it.
                    readBytes(in, connectionPrefaceBuf().capacity());

                    // Read a SETTINGS frame and validate it.
                    readSettingsFrame(in);
                    sendEmptySettingsAndAckFrame(bos);

                    readBytes(in, 9); // Read a SETTINGS_ACK frame and discard it.

                    // Read a HEADERS frame and validate it.
                    readHeadersFrame(in);
                    // Send a CONTINUE response.
                    sendFrameHeaders(bos, HttpStatus.CONTINUE, false);

                    // Read a DATA frame.
                    readDataFrame(in);
                    // Send a response.
                    sendFrameHeaders(bos, HttpStatus.CREATED, true);

                    final AggregatedHttpResponse res = future.join();
                    assertThat(res.status()).isEqualTo(HttpStatus.CREATED);
                }
            }
        }

        @Test
        void failToSendHttp2Request() throws Exception {
            try (ServerSocket ss = new ServerSocket(0);
                 ClientFactory clientFactory =
                         ClientFactory.builder()
                                      .useHttp2Preface(true)
                                      .http2InitialConnectionWindowSize(Http2CodecUtil.DEFAULT_WINDOW_SIZE)
                                      .http2InitialStreamWindowSize(Http2CodecUtil.DEFAULT_WINDOW_SIZE)
                                      .build()) {
                final int port = ss.getLocalPort();
                final WebClient client = WebClient.builder("http://127.0.0.1:" + port)
                                                  .factory(clientFactory)
                                                  .build();
                final CompletableFuture<AggregatedHttpResponse> future =
                        client.prepare()
                              .post("/")
                              .content("foo")
                              .header(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE)
                              .execute()
                              .aggregate();

                try (Socket s = ss.accept()) {
                    final InputStream in = s.getInputStream();
                    final BufferedOutputStream bos = new BufferedOutputStream(s.getOutputStream());

                    // Read the connection preface and discard it.
                    readBytes(in, connectionPrefaceBuf().capacity());

                    // Read a SETTINGS frame and validate it.
                    readSettingsFrame(in);
                    sendEmptySettingsAndAckFrame(bos);

                    readBytes(in, 9); // Read a SETTINGS_ACK frame and discard it.

                    // Read a HEADERS frame and validate it.
                    readHeadersFrame(in);
                    // Send a EXPECTATION_FAILED response.
                    sendFrameHeaders(bos, HttpStatus.EXPECTATION_FAILED, true);

                    final AggregatedHttpResponse res = future.join();
                    assertThat(res.status()).isEqualTo(HttpStatus.EXPECTATION_FAILED);
                }
            }
        }
    }

    @Nested
    class HttpRequestHandlerSubscriberTest {
        @Test
        void continueToSendHttp1StreamingRequest() throws Exception {
            try (ServerSocket ss = new ServerSocket(0)) {
                final int port = ss.getLocalPort();
                final WebClient client = WebClient.of("h1c://127.0.0.1:" + port);
                final RequestHeaders headers =
                        RequestHeaders.builder(HttpMethod.POST, "/")
                                      .contentType(MediaType.PLAIN_TEXT_UTF_8)
                                      .add(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE.toString())
                                      .build();
                final HttpRequestWriter req = HttpRequest.streaming(headers);

                final CompletableFuture<AggregatedHttpResponse> future = client.execute(req).aggregate();

                req.write(HttpData.ofUtf8("foo"));
                req.close();

                try (Socket s = ss.accept()) {
                    final BufferedReader in = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                    final OutputStream out = s.getOutputStream();
                    assertThat(in.readLine()).isEqualTo("POST / HTTP/1.1");
                    assertThat(in.readLine()).startsWith("host: 127.0.0.1:");
                    assertThat(in.readLine()).isEqualTo("content-type: text/plain; charset=utf-8");
                    assertThat(in.readLine()).isEqualTo("expect: 100-continue");
                    assertThat(in.readLine()).startsWith("user-agent: armeria/");
                    assertThat(in.readLine()).isEqualTo("transfer-encoding: chunked");
                    assertThat(in.readLine()).isEmpty();

                    out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));

                    assertThat(in.readLine()).isEqualTo("3");
                    assertThat(in.readLine()).isEqualTo("foo");

                    out.write(("HTTP/1.1 201 Created\r\n" +
                               "Connection: close\r\n" +
                               "Content-Length: 0\r\n" +
                               "\r\n").getBytes(StandardCharsets.US_ASCII));

                    final AggregatedHttpResponse res = future.join();
                    assertThat(res.status()).isEqualTo(HttpStatus.CREATED);
                }
            }
        }

        @Test
        void failToSendHttp1StreamingRequest() throws Exception {
            try (ServerSocket ss = new ServerSocket(0)) {
                final int port = ss.getLocalPort();
                final WebClient client = WebClient.of("h1c://127.0.0.1:" + port);
                final RequestHeaders headers =
                        RequestHeaders.builder(HttpMethod.POST, "/")
                                      .contentType(MediaType.PLAIN_TEXT_UTF_8)
                                      .add(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE.toString())
                                      .build();
                final HttpRequestWriter req = HttpRequest.streaming(headers);

                final CompletableFuture<AggregatedHttpResponse> future = client.execute(req).aggregate();

                req.write(HttpData.ofUtf8("foo"));

                try (Socket s = ss.accept()) {
                    final BufferedReader in = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                    final OutputStream out = s.getOutputStream();
                    assertThat(in.readLine()).isEqualTo("POST / HTTP/1.1");
                    assertThat(in.readLine()).startsWith("host: 127.0.0.1:");
                    assertThat(in.readLine()).isEqualTo("content-type: text/plain; charset=utf-8");
                    assertThat(in.readLine()).isEqualTo("expect: 100-continue");
                    assertThat(in.readLine()).startsWith("user-agent: armeria/");
                    assertThat(in.readLine()).isEqualTo("transfer-encoding: chunked");
                    assertThat(in.readLine()).isEmpty();

                    out.write("HTTP/1.1 417 Expectation Failed\r\n\r\n".getBytes(StandardCharsets.US_ASCII));

                    assertThatThrownBy(future::join)
                            .hasCauseInstanceOf(AbortedStreamException.class);
                }
            }
        }

        @Test
        void continueToSendHttp2StreamRequest() throws Exception {
            try (ServerSocket ss = new ServerSocket(0);
                 ClientFactory clientFactory =
                         ClientFactory.builder()
                                      .useHttp2Preface(true)
                                      .http2InitialConnectionWindowSize(Http2CodecUtil.DEFAULT_WINDOW_SIZE)
                                      .http2InitialStreamWindowSize(Http2CodecUtil.DEFAULT_WINDOW_SIZE)
                                      .build()) {
                final int port = ss.getLocalPort();
                final WebClient client = WebClient.builder("http://127.0.0.1:" + port)
                                                  .factory(clientFactory)
                                                  .build();
                final RequestHeaders headers =
                        RequestHeaders.builder(HttpMethod.POST, "/")
                                      .contentType(MediaType.PLAIN_TEXT_UTF_8)
                                      .add(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE.toString())
                                      .build();
                final HttpRequestWriter req = HttpRequest.streaming(headers);

                final CompletableFuture<AggregatedHttpResponse> future = client.execute(req).aggregate();

                req.write(HttpData.ofUtf8("foo"));

                try (Socket s = ss.accept()) {
                    final InputStream in = s.getInputStream();
                    final BufferedOutputStream bos = new BufferedOutputStream(s.getOutputStream());

                    // Read the connection preface and discard it.
                    readBytes(in, connectionPrefaceBuf().capacity());

                    // Read a SETTINGS frame and validate it.
                    readSettingsFrame(in);
                    sendEmptySettingsAndAckFrame(bos);

                    readBytes(in, 9); // Read a SETTINGS_ACK frame and discard it.

                    // Read a HEADERS frame and validate it.
                    readHeadersFrame(in);
                    // Send a CONTINUE response.
                    sendFrameHeaders(bos, HttpStatus.CONTINUE, false);

                    // Read a DATA frame.
                    readDataFrame(in);
                    // Send a response.
                    sendFrameHeaders(bos, HttpStatus.CREATED, true);

                    final AggregatedHttpResponse res = future.join();
                    assertThat(res.status()).isEqualTo(HttpStatus.CREATED);
                }
            }
        }

        @Test
        void failToSendHttp2StreamRequest() throws Exception {
            try (ServerSocket ss = new ServerSocket(0);
                 ClientFactory clientFactory =
                         ClientFactory.builder()
                                      .useHttp2Preface(true)
                                      .http2InitialConnectionWindowSize(Http2CodecUtil.DEFAULT_WINDOW_SIZE)
                                      .http2InitialStreamWindowSize(Http2CodecUtil.DEFAULT_WINDOW_SIZE)
                                      .build()) {
                final int port = ss.getLocalPort();
                final WebClient client = WebClient.builder("http://127.0.0.1:" + port)
                                                  .factory(clientFactory)
                                                  .build();
                final RequestHeaders headers =
                        RequestHeaders.builder(HttpMethod.POST, "/")
                                      .contentType(MediaType.PLAIN_TEXT_UTF_8)
                                      .add(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE.toString())
                                      .build();
                final HttpRequestWriter req = HttpRequest.streaming(headers);

                final CompletableFuture<AggregatedHttpResponse> future = client.execute(req).aggregate();

                req.write(HttpData.ofUtf8("foo"));

                try (Socket s = ss.accept()) {
                    final InputStream in = s.getInputStream();
                    final BufferedOutputStream bos = new BufferedOutputStream(s.getOutputStream());

                    // Read the connection preface and discard it.
                    readBytes(in, connectionPrefaceBuf().capacity());

                    // Read a SETTINGS frame and validate it.
                    readSettingsFrame(in);
                    sendEmptySettingsAndAckFrame(bos);

                    readBytes(in, 9); // Read a SETTINGS_ACK frame and discard it.

                    // Read a HEADERS frame and validate it.
                    readHeadersFrame(in);
                    // Send a EXPECTATION_FAILED response.
                    sendFrameHeaders(bos, HttpStatus.EXPECTATION_FAILED, true);

                    final AggregatedHttpResponse res = future.join();
                    assertThat(res.status()).isEqualTo(HttpStatus.EXPECTATION_FAILED);
                }
            }
        }
    }

    private static void readSettingsFrame(InputStream in) throws Exception {
        final byte[] expected = {
                0x00, 0x00, 0x0c, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x02, 0x00, 0x00, 0x00, 0x00, // SETTINGS_ENABLE_PUSH = 0 (disabled)
                0x00, 0x06, 0x00, 0x00, 0x20, 0x00 // MAX_HEADER_LIST_SIZE = 8192
        };
        assertThat(readBytes(in, expected.length)).containsExactly(expected);
    }

    private static void sendEmptySettingsAndAckFrame(BufferedOutputStream bos) throws IOException {
        // Send an empty SETTINGS frame.
        bos.write(new byte[] { 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00 });
        // Send a SETTINGS_ACK frame.
        bos.write(new byte[] { 0x00, 0x00, 0x00, 0x04, 0x01, 0x00, 0x00, 0x00, 0x00 });
        bos.flush();
    }

    private static void readHeadersFrame(InputStream in) throws Exception {
        final byte[] frameHeader = readBytes(in, 9);
        final int payloadLength = payloadLength(frameHeader);
        final byte[] headersPayload = readBytes(in, payloadLength);

        final DefaultHttp2HeadersDecoder headersDecoder = new DefaultHttp2HeadersDecoder();
        final ByteBuf payloadBuf = Unpooled.wrappedBuffer(headersPayload);
        final Http2Headers headers = headersDecoder.decodeHeaders(0, payloadBuf);

        assertThat(get(headers, HttpHeaderNames.METHOD)).isEqualTo("POST");
        assertThat(get(headers, HttpHeaderNames.PATH)).isEqualTo("/");
        assertThat(get(headers, HttpHeaderNames.SCHEME)).isEqualTo("http");
        assertThat(get(headers, HttpHeaderNames.AUTHORITY)).startsWith("127.0.0.1");
        assertThat(get(headers, HttpHeaderNames.USER_AGENT)).startsWith("armeria/");
        assertThat(get(headers, HttpHeaderNames.CONTENT_TYPE)).isEqualTo("text/plain; charset=utf-8");
        assertThat(get(headers, HttpHeaderNames.EXPECT)).isEqualTo(HttpHeaderValues.CONTINUE.toString());
    }

    @Nullable
    private static String get(Http2Headers headers, CharSequence name) {
        final CharSequence value = headers.get(name);
        return value != null ? value.toString() : null;
    }

    private static void sendFrameHeaders(BufferedOutputStream bos,
                                         HttpStatus status,
                                         boolean endOfStream) throws Exception {
        final HPackEncoder encoder = new HPackEncoder(StandardCharsets.UTF_8);
        final ByteArrayBuffer buffer = new ByteArrayBuffer(1024);
        encoder.encodeHeader(buffer, ":status", status.codeAsText(), false);
        final byte[] headersPayload = buffer.toByteArray();

        final ByteBuf buf = Unpooled.buffer(FRAME_HEADER_LENGTH + headersPayload.length);
        buf.writeMedium(headersPayload.length);
        buf.writeByte(Http2FrameTypes.HEADERS);
        buf.writeByte(new Http2Flags().endOfHeaders(true).endOfStream(endOfStream).value());
        buf.writeInt(3);
        buf.writeBytes(headersPayload);

        bos.write(buf.array());
        bos.flush();
    }

    private static void readDataFrame(InputStream in) throws Exception {
        final byte[] frameHeader = readBytes(in, 9);
        final int payloadLength = payloadLength(frameHeader);
        final byte[] payloadBuf = readBytes(in, payloadLength);

        assertThat(payloadBuf).containsExactly("foo".getBytes(StandardCharsets.UTF_8));
    }

    private HttpClientExpect100HeaderTest() {}
}
