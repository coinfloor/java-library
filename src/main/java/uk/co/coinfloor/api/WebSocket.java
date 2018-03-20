package uk.co.coinfloor.api;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Random;

import javax.net.ssl.SSLSocketFactory;

class WebSocket implements Closeable {

	public static class MessageInputStream extends FilterInputStream {

		private int flagsAndOpcode;
		private byte[] maskingKey;

		private long length;
		private int position;

		MessageInputStream(InputStream in) throws IOException {
			super(in);
			nextFrame(false);
		}

		public int getFlags() {
			return flagsAndOpcode & ~((1 << 4) - 1);
		}

		public int getOpcode() {
			return flagsAndOpcode & (1 << 4) - 1;
		}

		@Override
		public int read() throws IOException {
			if (length <= 0 && !nextFrame(true)) {
				return -1;
			}
			int b = in.read();
			if (b < 0) {
				throw new EOFException("premature EOF");
			}
			--length;
			return maskingKey == null ? b : b ^ maskingKey[position++ & 3] & 0xFF;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if (length <= 0 && !nextFrame(true)) {
				return -1;
			}
			int n = in.read(b, off, (int) Math.min(len, length));
			if (n < 0) {
				throw new EOFException("premature EOF");
			}
			if (n > 0) {
				length -= n;
				byte[] maskingKey = this.maskingKey;
				if (maskingKey != null) {
					int position = this.position;
					for (int i = 0; i < n; ++i) {
						b[off + i] ^= maskingKey[position++ & 3] & 0xFF;
					}
					this.position = position;
				}
			}
			return n;
		}

		@Override
		public void close() throws IOException {
			do {
				while (length > 0) {
					length -= in.skip(length);
				}
			} while (nextFrame(true));
		}

		private boolean nextFrame(boolean continuation) throws IOException {
			while ((flagsAndOpcode & FLAG_FIN) == 0) {
				DataInputStream dis = new DataInputStream(in);
				flagsAndOpcode = dis.readUnsignedByte();
				if (getOpcode() == OP_CONTINUATION != continuation) {
					throw new ProtocolException("frame has unexpected opcode");
				}
				long length = dis.readUnsignedByte();
				boolean mask = (length & 1 << 7) != 0;
				length &= (1 << 7) - 1;
				if (length == 126) {
					length = dis.readUnsignedShort();
				}
				else if (length == 127 && (length = dis.readLong()) < 0) {
					throw new ProtocolException("frame payload length is too large");
				}
				this.length = length;
				if (mask) {
					dis.readFully(maskingKey == null ? maskingKey = new byte[4] : maskingKey);
					position = 0;
				}
				else {
					maskingKey = null;
				}
				if (length > 0) {
					return true;
				}
			}
			return false;
		}

	}

	public static class MessageOutputStream extends BufferedOutputStream {

		static final int PAYLOAD_OFFSET = 4;

		final byte flagsAndOpcode;

		boolean continuation;

		MessageOutputStream(OutputStream out, int flags, int opcode) {
			super(out, 8192);
			if ((flags & ~((1 << 3) - 1 << 4)) != 0) {
				throw new IllegalArgumentException("flags");
			}
			if (opcode == 0 || (opcode & ~((1 << 4) - 1)) != 0) {
				throw new IllegalArgumentException("opcode");
			}
			flagsAndOpcode = (byte) (flags | opcode);
			count = PAYLOAD_OFFSET;
		}

		@Override
		public void write(int b) throws IOException {
			byte[] buf = this.buf;
			if (buf == null) {
				throw new IOException("closed");
			}
			if (count >= buf.length) {
				writeBufferedFragment(false, count - PAYLOAD_OFFSET);
			}
			buf[count++] = (byte) b;
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			byte[] buf = this.buf;
			if (buf == null) {
				throw new IOException("closed");
			}
			int maxBufferedPayloadSize = buf.length - PAYLOAD_OFFSET;
			if (len > maxBufferedPayloadSize) {
				writeUnbufferedFragment(b, off, len);
			}
			else {
				for (int n; len > (n = buf.length - count); off += n, len -= n) {
					System.arraycopy(b, off, buf, count, n);
					count += n;
					writeBufferedFragment(false, maxBufferedPayloadSize);
				}
				System.arraycopy(b, off, buf, count, len);
				count += len;
			}
		}

		@Override
		public void flush() throws IOException {
			// no-op
		}

		@Override
		public void close() throws IOException {
			if (out != null) {
				writeBufferedFragment(true, count - PAYLOAD_OFFSET);
				out.flush();
				out = null;
				buf = null;
			}
		}

		private void writeBufferedFragment(boolean fin, int payloadSize) throws IOException {
			byte[] buf = this.buf;
			int headerOffset = putHeader(buf, PAYLOAD_OFFSET, fin, payloadSize);
			out.write(buf, headerOffset, count - headerOffset);
			count = PAYLOAD_OFFSET;
		}

		private void writeUnbufferedFragment(byte[] b, int off, int len) throws IOException {
			int payloadSize = count - PAYLOAD_OFFSET;
			if (payloadSize + len <= 0xFFFF) {
				writeBufferedFragment(false, payloadSize + len);
			}
			else {
				int headerSize = len < 126 ? 2 : len <= 0xFFFF ? 4 : 10;
				if (count + headerSize > buf.length) {
					writeBufferedFragment(false, payloadSize);
				}
				else if (payloadSize > 0) {
					putHeader(buf, count += headerSize, false, len);
					writeBufferedFragment(false, payloadSize);
					out.write(b, off, len);
					return;
				}
				putHeader(buf, headerSize, false, len);
				out.write(buf, 0, headerSize);
			}
			out.write(b, off, len);
		}

		private int putHeader(byte[] header, int off, boolean fin, int payloadSize) {
			if (payloadSize < 126) {
				header[--off] = (byte) payloadSize;
			}
			else if (payloadSize <= 0xFFFF) {
				header[off -= 3] = 126;
				header[off + 1] = (byte) (payloadSize >> 8);
				header[off + 2] = (byte) payloadSize;
			}
			else {
				header[off -= 9] = 127;
				header[off + 1] = 0;
				header[off + 2] = 0;
				header[off + 3] = 0;
				header[off + 4] = 0;
				header[off + 5] = (byte) (payloadSize >> 24);
				header[off + 6] = (byte) (payloadSize >> 16);
				header[off + 7] = (byte) (payloadSize >> 8);
				header[off + 8] = (byte) payloadSize;
			}
			int flagsAndOpcode = this.flagsAndOpcode;
			if (continuation) {
				flagsAndOpcode &= ~((1 << 4) - 1);
			}
			else {
				continuation = true;
			}
			header[--off] = (byte) (fin ? flagsAndOpcode | FLAG_FIN : flagsAndOpcode);
			return off;
		}

	}

	private static class MaskedMessageOutputStream extends MessageOutputStream {

		static final int MASK_OFFSET = MessageOutputStream.PAYLOAD_OFFSET, PAYLOAD_OFFSET = MASK_OFFSET + 4;

		private final Random maskingRandom;

		MaskedMessageOutputStream(OutputStream out, int flags, int opcode, Random maskingRandom) {
			super(out, flags, opcode);
			putInt(buf, MASK_OFFSET, (this.maskingRandom = maskingRandom).nextInt());
			count = PAYLOAD_OFFSET;
		}

		@Override
		public void write(int b) throws IOException {
			byte[] buf = this.buf;
			if (buf == null) {
				throw new IOException("closed");
			}
			if (count >= buf.length) {
				writeBufferedFragment(false, count - PAYLOAD_OFFSET);
			}
			buf[count] = (byte) (b ^ buf[MASK_OFFSET + (count & 3)]);
			++count;
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			byte[] buf = this.buf;
			if (buf == null) {
				throw new IOException("closed");
			}
			int maxBufferedPayloadSize = buf.length - PAYLOAD_OFFSET;
			for (int n; len > (n = buf.length - count); off += n, len -= n) {
				buffer(b, off, n);
				writeBufferedFragment(false, maxBufferedPayloadSize);
			}
			buffer(b, off, len);
		}

		@Override
		public void close() throws IOException {
			if (out != null) {
				writeBufferedFragment(true, count - PAYLOAD_OFFSET);
				out.flush();
				out = null;
				buf = null;
			}
		}

		private void buffer(byte[] in, int off, int len) {
			int count = this.count;
			for (byte[] buf = this.buf; len > 0; ++count, ++off, --len) {
				buf[count] = (byte) (in[off] ^ buf[MASK_OFFSET + (count & 3)]);
			}
			this.count = count;
		}

		private void writeBufferedFragment(boolean fin, int payloadSize) throws IOException {
			byte[] buf = this.buf;
			int headerOffset = putHeader(buf, MASK_OFFSET, fin, payloadSize);
			out.write(buf, headerOffset, count - headerOffset);
			putInt(buf, MASK_OFFSET, maskingRandom.nextInt());
			count = PAYLOAD_OFFSET;
		}

		private int putHeader(byte[] header, int off, boolean fin, int payloadSize) {
			if (payloadSize < 126) {
				header[--off] = (byte) (payloadSize | 1 << 7);
			}
			else if (payloadSize <= 0xFFFF) {
				header[off -= 3] = (byte) (126 | 1 << 7);
				header[off + 1] = (byte) (payloadSize >> 8);
				header[off + 2] = (byte) payloadSize;
			}
			else {
				header[off -= 9] = (byte) (127 | 1 << 7);
				header[off + 1] = 0;
				header[off + 2] = 0;
				header[off + 3] = 0;
				header[off + 4] = 0;
				header[off + 5] = (byte) (payloadSize >> 24);
				header[off + 6] = (byte) (payloadSize >> 16);
				header[off + 7] = (byte) (payloadSize >> 8);
				header[off + 8] = (byte) payloadSize;
			}
			int flagsAndOpcode = this.flagsAndOpcode;
			if (continuation) {
				flagsAndOpcode &= ~((1 << 4) - 1);
			}
			else {
				continuation = true;
			}
			header[--off] = (byte) (fin ? flagsAndOpcode | FLAG_FIN : flagsAndOpcode);
			return off;
		}

		private static void putInt(byte[] buf, int off, int v) {
			buf[off] = (byte) (v >> 24);
			buf[off + 1] = (byte) (v >> 16);
			buf[off + 2] = (byte) (v >> 8);
			buf[off + 3] = (byte) v;
		}

	}

	private static class DelimitedInputStream extends FilterInputStream {

		private final byte[] delimiter;
		private final int delimiterOffset, delimiterLength;

		private int delimiterPosition;

		DelimitedInputStream(InputStream in, byte[] delimiter) {
			this(in, delimiter, 0, delimiter.length);
		}

		DelimitedInputStream(InputStream in, byte[] delimiter, int delimiterOffset, int delimiterLength) {
			super(in);
			this.delimiter = delimiter;
			this.delimiterOffset = delimiterOffset;
			this.delimiterLength = delimiterLength;
		}

		@Override
		public int read() throws IOException {
			if (delimiterPosition == delimiterOffset + delimiterLength) {
				return -1;
			}
			int b;
			if ((b = super.read()) < 0) {
				return b;
			}
			delimiterPosition = b == (delimiter[delimiterPosition] & 0xFF) ? delimiterPosition + 1 : b == (delimiter[delimiterOffset] & 0xFF) ? delimiterOffset + 1 : delimiterOffset;
			return b;
		}

		@Override
		public int read(byte[] buf, int off, int len) throws IOException {
			int r = 0;
			while (len > 0) {
				int d;
				if ((d = delimiterOffset + delimiterLength - delimiterPosition) <= 0) {
					return r == 0 ? -1 : r;
				}
				int s;
				if ((s = super.read(buf, off, Math.min(d, len))) <= 0) {
					return r == 0 ? s : r;
				}
				for (int i = 0; i < s; ++i) {
					byte b = buf[off++];
					delimiterPosition = b == delimiter[delimiterPosition] ? delimiterPosition + 1 : b == delimiter[delimiterOffset] ? delimiterOffset + 1 : delimiterOffset;
				}
				len -= s;
				r += s;
			}
			return r;
		}

	}

	public static final String SCHEME_WS = "ws";
	public static final String SCHEME_WSS = "wss";
	public static final int DEFAULT_PORT_WS = 80;
	public static final int DEFAULT_PORT_WSS = 443;

	public static final int FLAG_FIN = 1 << 7;
	public static final int FLAG_RSV1 = 1 << 6;
	public static final int FLAG_RSV2 = 1 << 5;
	public static final int FLAG_RSV3 = 1 << 4;

	public static final int OP_CONTINUATION = 0x0;
	public static final int OP_TEXT = 0x1;
	public static final int OP_BINARY = 0x2;
	public static final int OP_CLOSE = 0x8;
	public static final int OP_PING = 0x9;
	public static final int OP_PONG = 0xA;

	private final SecureRandom secureRandom = new SecureRandom();
	private final Socket socket;
	private final BufferedInputStream in;
	private final OutputStream out;

	public WebSocket(URI uri) throws UnknownHostException, IOException {
		this(uri, 0, 0);
	}

	public WebSocket(URI uri, int connectTimeout, int receiveTimeout) throws UnknownHostException, IOException {
		Socket socket = new Socket();
		try {
			socket.setTcpNoDelay(true);
			socket.setSoTimeout(receiveTimeout);
			String host = uri.getHost();
			int port = uri.getPort();
			String scheme = uri.getScheme();
			if (SCHEME_WS.equals(scheme)) {
				socket.connect(new InetSocketAddress(host, port < 0 ? DEFAULT_PORT_WS : port), connectTimeout);
			}
			else if (SCHEME_WSS.equals(scheme)) {
				socket.connect(new InetSocketAddress(host, port < 0 ? DEFAULT_PORT_WSS : port), connectTimeout);
				socket = ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(socket, host, port < 0 ? DEFAULT_PORT_WSS : port, true);
			}
			else {
				throw new IllegalArgumentException("unsupported scheme: " + scheme);
			}
			OutputStreamWriter writer = new OutputStreamWriter(new BufferedOutputStream(out = socket.getOutputStream()), "US-ASCII");
			writer.write("GET ");
			writer.write(uri.getRawPath());
			String query = uri.getRawQuery();
			if (query != null) {
				writer.write('?');
				writer.write(query);
			}
			writer.write(" HTTP/1.1\r\nHost: ");
			writer.write(host);
			if (port >= 0) {
				writer.write(':');
				writer.write(String.valueOf(port));
			}
			writer.write("\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: ");
			byte[] nonce = new byte[16];
			secureRandom.nextBytes(nonce);
			String nonceStr = Base64.encode(nonce);
			writer.write(nonceStr);
			writer.write("\r\nSec-WebSocket-Version: 13\r\n\r\n");
			writer.flush();
			try {
				nonceStr = Base64.encode(MessageDigest.getInstance("SHA-1").digest((nonceStr + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("US-ASCII")));
			}
			catch (NoSuchAlgorithmException e) {
				throw new RuntimeException(e);
			}
			InputStreamReader reader = new InputStreamReader(new DelimitedInputStream(in = new BufferedInputStream(socket.getInputStream()), new byte[] { 13, 10, 13, 10 }), "US-ASCII");
			String protocol = IO.readUntil(reader, ' ');
			if (!protocol.startsWith("HTTP/1.")) {
				throw new IOException("server is using incompatible protocol: " + protocol);
			}
			int statusCode = Integer.parseInt(IO.readUntil(reader, ' '));
			if (statusCode != 101) {
				throw new IOException("server returned status code " + statusCode);
			}
			IO.skipUntil(reader, '\n');
			boolean upgradeWebsocket = false, connectionUpgrade = false, secWebSocketAccept = false;
			for (String[] header : readHeaders(reader)) {
				String name = header[0];
				if ("Upgrade".equalsIgnoreCase(name)) {
					if (!"websocket".equalsIgnoreCase(header[1])) {
						throw new IOException("server is using incompatible upgrade protocol: " + header[1]);
					}
					upgradeWebsocket = true;
				}
				else if ("Connection".equalsIgnoreCase(name)) {
					if (!"Upgrade".equalsIgnoreCase(header[1])) {
						throw new IOException("server is using incompatible connection: " + header[1]);
					}
					connectionUpgrade = true;
				}
				else if ("Sec-WebSocket-Accept".equalsIgnoreCase(name)) {
					if (!nonceStr.equals(header[1])) {
						throw new IOException("server returned incorrect nonce");
					}
					secWebSocketAccept = true;
				}
			}
			if (!upgradeWebsocket) {
				throw new IOException("server omitted required Upgrade header");
			}
			if (!connectionUpgrade) {
				throw new IOException("server omitted required Connection header");
			}
			if (!secWebSocketAccept) {
				throw new IOException("server omitted required Sec-WebSocket-Accept header");
			}
			this.socket = socket;
			socket = null;
		}
		finally {
			if (socket != null) {
				socket.close();
			}
		}
	}

	public MessageInputStream getInputStream() throws IOException {
		return getInputStream(0, 0);
	}

	@Deprecated
	public MessageInputStream getInputStream(int initialTimeout) throws IOException {
		return getInputStream(initialTimeout, 0);
	}

	public MessageInputStream getInputStream(int initialTimeout, int subsequentTimeout) throws IOException {
		if (initialTimeout != subsequentTimeout) {
			socket.setSoTimeout(initialTimeout);
			in.mark(1);
			try {
				in.read();
			}
			catch (SocketTimeoutException e) {
				return null;
			}
			in.reset();
		}
		socket.setSoTimeout(subsequentTimeout);
		return new MessageInputStream(in);
	}

	public MessageOutputStream getOutputStream(int flags, int opcode, boolean mask) {
		return mask ? new MaskedMessageOutputStream(out, flags, opcode, secureRandom) : new MessageOutputStream(out, flags, opcode);
	}

	@Override
	public void close() {
		// TODO send proper disconnection message
		try {
			socket.close();
		}
		catch (IOException ignored) {
		}
	}

	private static String[][] readHeaders(Reader reader) throws IOException {
		ArrayList<String[]> headers = new ArrayList<String[]>();
		for (;;) {
			String header = IO.readUntil(reader, '\n');
			int length = header.length();
			if (length > 0 && header.charAt(length - 1) == '\r') {
				header = header.substring(0, --length);
			}
			if (length == 0) {
				return headers.toArray(new String[headers.size()][]);
			}
			if (Character.isWhitespace(header.charAt(0))) {
				String[] lastHeader = headers.get(headers.size() - 1);
				lastHeader[1] += header.substring(1);
			}
			else {
				int colon = header.indexOf(':');
				if (colon < 0) {
					throw new IOException("malformed response header: " + header);
				}
				headers.add(new String[] { header.substring(0, colon), header.substring(++colon < length && Character.isWhitespace(header.charAt(colon)) ? colon + 1 : colon) });
			}
		}
	}

}
