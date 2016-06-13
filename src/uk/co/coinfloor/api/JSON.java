package uk.co.coinfloor.api;

import java.io.EOFException;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.StreamCorruptedException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class JSON {

	/**
	 * Not instantiable.
	 */
	private JSON() {
	}

	public static void format(Writer writer, Object value) throws IOException {
		if (value == null) {
			writer.write("null");
		}
		else if (value instanceof Map<?, ?>) {
			formatObject(writer, (Map<?, ?>) value);
		}
		else if (value instanceof List<?>) {
			formatArray(writer, (List<?>) value);
		}
		else if (value instanceof String) {
			formatString(writer, (String) value);
		}
		else if (value instanceof Number || value instanceof Boolean) {
			writer.write(value.toString());
		}
		else {
			throw new IllegalArgumentException("expected Map, List, String, Number, Boolean, or null: " + value);
		}
	}

	private static void formatObject(Writer writer, Map<?, ?> object) throws IOException {
		writer.write('{');
		if (!object.isEmpty()) {
			for (Iterator<? extends Map.Entry<?, ?>> it = object.entrySet().iterator();;) {
				Map.Entry<?, ?> entry = it.next();
				formatString(writer, entry.getKey().toString());
				writer.write(':');
				format(writer, entry.getValue());
				if (!it.hasNext()) {
					break;
				}
				writer.write(',');
			}
		}
		writer.write('}');
	}

	private static void formatArray(Writer writer, List<?> array) throws IOException {
		writer.write('[');
		if (!array.isEmpty()) {
			for (Iterator<?> it = array.iterator();;) {
				format(writer, it.next());
				if (!it.hasNext()) {
					break;
				}
				writer.write(',');
			}
		}
		writer.write(']');
	}

	private static void formatString(Writer writer, String string) throws IOException {
		writer.write('"');
		for (int i = 0, n = string.length(); i < n; ++i) {
			char cu = string.charAt(i);
			switch (cu) {
				case '\b': // backspace (U+0008)
					writer.write("\\b");
					break;
				case '\t': // character tabulation (U+0009)
					writer.write("\\t");
					break;
				case '\n': // line feed (U+000A)
					writer.write("\\n");
					break;
				case '\f': // form feed (U+000C)
					writer.write("\\f");
					break;
				case '\r': // carriage return (U+000D)
					writer.write("\\r");
					break;
				case '"': // quotation mark (U+0022)
					writer.write("\\\"");
					break;
				case '\\': // reverse solidus (U+005C)
					writer.write("\\\\");
					break;
				default:
					if (cu <= 0x1F) {
						writer.write("\\u00");
						writer.write(Character.forDigit(cu >> 4, 16));
						writer.write(Character.toUpperCase(Character.forDigit(cu & (1 << 4) - 1, 16)));
					}
					else {
						writer.write(cu);
					}
					break;
			}
		}
		writer.write('"');
	}

	public static Object parse(PushbackReader reader) throws IOException {
		Object value = parseValue(reader);
		if (!(value instanceof Map<?, ?>) && !(value instanceof List<?>)) {
			throw new StreamCorruptedException("expected object or array");
		}
		return value;
	}

	private static Object parseValue(PushbackReader reader) throws IOException {
		int c = IO.readSkipWhitespace(reader);
		if (c < 0) {
			throw new EOFException("expected object, array, string, number, boolean, or null");
		}
		switch (c) {
			case '{':
				return parseObject(reader);
			case '[':
				return parseArray(reader);
			case '"':
				return parseString(reader);
			case '-':
			case '0':
			case '1':
			case '2':
			case '3':
			case '4':
			case '5':
			case '6':
			case '7':
			case '8':
			case '9':
				reader.unread(c);
				return parseNumber(reader);
			case 't':
				if (reader.read() == 'r' && reader.read() == 'u' && reader.read() == 'e') {
					return Boolean.TRUE;
				}
				break;
			case 'f':
				if (reader.read() == 'a' && reader.read() == 'l' && reader.read() == 's' && reader.read() == 'e') {
					return Boolean.FALSE;
				}
				break;
			case 'n':
				if (reader.read() == 'u' && reader.read() == 'l' && reader.read() == 'l') {
					return null;
				}
				break;
		}
		throw new StreamCorruptedException("expected object, array, string, number, boolean, or null" + ": " + (char) c);
	}

	private static Map<?, ?> parseObject(PushbackReader reader) throws IOException {
		LinkedHashMap<Object, Object> map = new LinkedHashMap<Object, Object>();
		for (int c; (c = IO.readSkipWhitespace(reader)) >= 0;) {
			if (c == '}') {
				return map;
			}
			if (map.isEmpty()) {
				if (c != '"') {
					throw new StreamCorruptedException("expected string or closing brace: " + (char) c);
				}
			}
			else if (c != ',') {
				throw new StreamCorruptedException("expected comma or closing brace: " + (char) c);
			}
			else {
				if ((c = IO.readSkipWhitespace(reader)) < 0) {
					throw new EOFException("expected string");
				}
				if (c != '"') {
					throw new StreamCorruptedException("expected string" + ": " + (char) c);
				}
			}
			String key = parseString(reader);
			if ((c = IO.readSkipWhitespace(reader)) < 0) {
				throw new EOFException("expected colon");
			}
			if (c != ':') {
				throw new StreamCorruptedException("expected colon" + ": " + (char) c);
			}
			map.put(key, parseValue(reader));
		}
		throw new EOFException("unterminated object");
	}

	private static List<?> parseArray(PushbackReader reader) throws IOException {
		ArrayList<Object> list = new ArrayList<Object>();
		for (int c; (c = IO.readSkipWhitespace(reader)) >= 0;) {
			if (c == ']') {
				return list;
			}
			if (list.isEmpty()) {
				reader.unread(c);
			}
			else if (c != ',') {
				throw new StreamCorruptedException("expected comma or closing bracket: " + (char) c);
			}
			list.add(parseValue(reader));
		}
		throw new EOFException("unterminated array");
	}

	private static Number parseNumber(PushbackReader reader) throws IOException {
		StringBuilder sb = new StringBuilder();
		int c = IO.readSkipWhitespace(reader);
		if (c == '-') {
			sb.append('-');
			c = reader.read();
		}
		if (c < 0) {
			throw new EOFException("expected number");
		}
		if (c < '0' || c > '9') {
			throw new StreamCorruptedException("expected number" + ": " + (char) c);
		}
		sb.append((char) c);
		if (c == '0') {
			c = reader.read();
		}
		else {
			while ((c = reader.read()) >= '0' && c <= '9') {
				sb.append((char) c);
			}
		}
		boolean fp = false;
		if (c == '.') {
			fp = true;
			do {
				sb.append((char) c);
			} while ((c = reader.read()) >= '0' && c <= '9');
		}
		if (c == 'E' || c == 'e') {
			fp = true;
			sb.append((char) c);
			if ((c = reader.read()) == '-' || c == '+') {
				sb.append((char) c);
				c = reader.read();
			}
			while (c >= '0' && c <= '9') {
				sb.append((char) c);
				c = reader.read();
			}
		}
		reader.unread(c);
		String str = sb.toString();
		return fp ? (Number) Double.valueOf(str) : (Number) Long.valueOf(str);
	}

	private static String parseString(PushbackReader reader) throws IOException {
		StringBuilder sb = new StringBuilder();
		for (int c; (c = reader.read()) != '"';) {
			if (c < 0) {
				throw new EOFException("unterminated string");
			}
			if (c == '\\') {
				switch (c = reader.read()) {
					case 'b': // backspace (U+0008)
						sb.append('\b');
						break;
					case 't': // character tabulation (U+0009)
						sb.append('\t');
						break;
					case 'n': // line feed (U+000A)
						sb.append('\n');
						break;
					case 'f': // form feed (U+000C)
						sb.append('\f');
						break;
					case 'r': // carriage return (U+000D)
						sb.append('\r');
						break;
					case '"': // quotation mark (U+0022)
						sb.append('"');
						break;
					case '/': // solidus (U+002F)
						sb.append('/');
						break;
					case '\\': // reverse solidus (U+005C)
						sb.append('\\');
						break;
					case 'u': {
						int cu;
						if ((cu = Character.digit(c = reader.read(), 16)) < 0 || (cu = cu << 4 | Character.digit(c = reader.read(), 16)) < 0 || (cu = cu << 4 | Character.digit(c = reader.read(), 16)) < 0 || (cu = cu << 4 | Character.digit(c = reader.read(), 16)) < 0) {
							throw new StreamCorruptedException("invalid hex digit: " + (char) c);
						}
						sb.append((char) cu);
						break;
					}
					default:
						throw new StreamCorruptedException("invalid escape sequence: \\" + (char) c);
				}
			}
			else {
				sb.append((char) c);
			}
		}
		return sb.toString();
	}

}
