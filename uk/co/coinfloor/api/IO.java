package uk.co.coinfloor.api;

import java.io.EOFException;
import java.io.IOException;
import java.io.Reader;

class IO {

	/**
	 * Not instantiable.
	 */
	private IO() {
	}

	static int readSkipWhitespace(Reader reader) throws IOException {
		int c;
		do {
			if ((c = reader.read()) < 0) {
				return -1;
			}
		} while (Character.isWhitespace((char) c));
		return c;
	}

	static String readUntil(Reader reader, char stop) throws IOException {
		return copyUntil(reader, new StringBuilder(), stop).toString();
	}

	static StringBuilder copyUntil(Reader reader, StringBuilder sb, char stop) throws IOException {
		for (int c; (c = reader.read()) >= 0;) {
			if (c == stop) {
				return sb;
			}
			sb.append((char) c);
		}
		throw new EOFException();
	}

	static void skipUntil(Reader reader, char stop) throws IOException {
		for (int c; (c = reader.read()) >= 0;) {
			if (c == stop) {
				return;
			}
		}
		throw new EOFException();
	}

	static String readRemaining(Reader reader) throws IOException {
		return copyRemaining(reader, new StringBuilder()).toString();
	}

	static StringBuilder copyRemaining(Reader reader, StringBuilder sb) throws IOException {
		for (int c; (c = reader.read()) >= 0;) {
			sb.append((char) c);
		}
		return sb;
	}

}
