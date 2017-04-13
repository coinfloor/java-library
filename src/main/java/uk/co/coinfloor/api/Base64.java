package uk.co.coinfloor.api;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;

class Base64 {

	private static final char[] base64enc = { 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '+', '/' };
	private static final byte[] base64dec = { 62, -1, -1, -1, 63, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, -1, -1, -1, -1, -1, -1, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, -1, -1, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51 };

	public static String encode(byte[] array) {
		return encode(array, 0, array.length);
	}

	public static String encode(byte[] array, int offset, int length) {
		StringBuilder sb = new StringBuilder((array.length + 2) / 3 * 4);
		try {
			encode(sb, array, offset, length);
		}
		catch (IOException impossible) {
			throw new RuntimeException(impossible);
		}
		return sb.toString();
	}

	public static <A extends Appendable> A encode(A out, byte[] array) throws IOException {
		return encode(out, array, 0, array.length);
	}

	public static <A extends Appendable> A encode(A out, byte[] array, int offset, int length) throws IOException {
		return encode(out, new ByteArrayInputStream(array, offset, length));
	}

	public static <A extends Appendable> A encode(A out, InputStream in) throws IOException {
		int b0, b1;
		while ((b0 = in.read()) >= 0) {
			out.append(base64enc[b0 >> 2]);
			if ((b1 = in.read()) >= 0) {
				out.append(base64enc[b0 << 4 & 0x30 | b1 >> 4]);
				if ((b0 = in.read()) >= 0) {
					out.append(base64enc[b1 << 2 & 0x3C | b0 >> 6]);
					out.append(base64enc[b0 & 0x3F]);
					continue;
				}
				out.append(base64enc[b1 << 2 & 0x3C]);
			}
			else {
				out.append(base64enc[b0 << 4 & 0x30]);
				out.append('=');
			}
			out.append('=');
			break;
		}
		return out;
	}

	public static byte[] decode(String string) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream(string.length() / 4 * 3);
		decode(baos, string);
		return baos.toByteArray();
	}

	public static <S extends OutputStream> S decode(S out, String string) throws IOException {
		return decode(out, new StringReader(string));
	}

	public static <S extends OutputStream> S decode(S out, Reader in) throws IOException {
		int c, b0, b1;
		for (;;) {
			if ((c = IO.readSkipWhitespace(in)) < 0) {
				return out;
			}
			if (c < '+' || c > 'z' || (b0 = base64dec[c - '+']) < 0) {
				break;
			}
			if ((c = IO.readSkipWhitespace(in)) < 0) {
				throw new EOFException();
			}
			if (c < '+' || c > 'z' || (b1 = base64dec[c - '+']) < 0) {
				break;
			}
			out.write(b0 << 2 | b1 >> 4);
			if ((c = in.read()) < 0) {
				throw new EOFException();
			}
			if (c < '+' || c > 'z' || (b0 = base64dec[c - '+']) < 0) {
				if (c == '=') {
					if ((c = IO.readSkipWhitespace(in)) < 0) {
						throw new EOFException();
					}
					if (c == '=') {
						return out;
					}
				}
				break;
			}
			out.write(b1 << 4 | b0 >> 2);
			if ((c = IO.readSkipWhitespace(in)) < 0) {
				throw new EOFException();
			}
			if (c < '+' || c > 'z' || (b1 = base64dec[c - '+']) < 0) {
				if (c == '=') {
					return out;
				}
				break;
			}
			out.write(b0 << 6 | b1);
		}
		throw new IOException("invalid character: " + Integer.toHexString((short) c));
	}

}
