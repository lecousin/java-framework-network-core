package net.lecousin.framework.network;

import net.lecousin.framework.encoding.EncodingException;
import net.lecousin.framework.encoding.HexaDecimalEncoding;
import net.lecousin.framework.encoding.number.DecimalNumber;
import net.lecousin.framework.encoding.number.HexadecimalNumber;
import net.lecousin.framework.io.util.DataUtil;
import net.lecousin.framework.serialization.CustomSerializer;
import net.lecousin.framework.serialization.SerializationException;
import net.lecousin.framework.serialization.TypeDefinition;

/** Utilities for IP adresses. */
public final class IPAddressUtil {

	private IPAddressUtil() {
		// no instance
	}
	
	private static final String INVALID = "Invalid IP address: ";
	
	/** Create a 4-bytes array from a String representation of an IPv4 address. 
	 * @throws EncodingException if a value is not a valid number */
	public static byte[] fromStringV4(CharSequence str) throws EncodingException {
		byte[] ip = new byte[4];
		int pos = 0;
		int len = str.length();
		for (int i = 0; i < 4; ++i) {
			DecimalNumber n = new DecimalNumber();
			for (int j = 0; pos < len; ++j) {
				if (!n.addChar(str.charAt(pos++))) {
					if (j == 0) throw new EncodingException(INVALID + str);
					if (str.charAt(pos - 1) != '.' || i == 3) throw new EncodingException(INVALID + str);
					break;
				}
				if (j == 3) throw new EncodingException(INVALID + str);
			}
			if (pos == len && i < 3) throw new EncodingException(INVALID + str);
			if (n.getNumber() > 255) throw new EncodingException(INVALID + str);
			ip[i] = (byte)n.getNumber();
		}
		return ip;
	}

	/** Create a String representation of the given IPv4 address. */
	public static String toStringV4(byte[] ip) {
		StringBuilder s = new StringBuilder(15);
		for (int i = 0; i < ip.length; ++i) {
			if (i > 0) s.append('.');
			s.append(ip[i] & 0xFF);
		}
		return s.toString();
	}
	
	/** Create a 16-bytes array from a String representation of an IPv6 address. 
	 * @throws EncodingException if a character is not valid */
	public static byte[] fromStringV6(CharSequence str) throws EncodingException {
		byte[] ip = new byte[16];
		if (str.charAt(0) == ':') {
			if (str.charAt(1) != ':') throw new EncodingException(INVALID + str);
			decodeEndOfV6(ip, str, 2, str.length());
			return ip;
		}
		int len = str.length();
		int strPos = 0;
		for (int ipPos = 0; true; ipPos += 2) {
			if (strPos == len) throw new EncodingException(INVALID + str);
			if (strPos > 0 && str.charAt(strPos) == ':') {
				decodeEndOfV6(ip, str, strPos + 1, len);
				return ip;
			}
			HexadecimalNumber n = new HexadecimalNumber();
			if (!n.addChar(str.charAt(strPos++))) throw new EncodingException(INVALID + str);
			for (int i = 0; i < 3; ++i) {
				if (strPos == len) {
					if (ipPos != 14) throw new EncodingException(INVALID + str);
					DataUtil.Write16U.BE.write(ip, ipPos, (int)n.getNumber());
					return ip;
				}
				if (!n.addChar(str.charAt(strPos++))) {
					if (str.charAt(strPos - 1) != ':') throw new EncodingException(INVALID + str);
					DataUtil.Write16U.BE.write(ip, ipPos, (int)n.getNumber());
					n = null;
					break;
				}
			}
			if (n == null) continue;
			if (strPos == len) {
				if (ipPos != 14) throw new EncodingException(INVALID + str);
				DataUtil.Write16U.BE.write(ip, ipPos, (int)n.getNumber());
				return ip;
			}
			if (str.charAt(strPos++) != ':') throw new EncodingException(INVALID + str);
			DataUtil.Write16U.BE.write(ip, ipPos, (int)n.getNumber());
		}
	}
	
	private static void decodeEndOfV6(byte[] ip, CharSequence str, int start, int len) throws EncodingException {
		int strPos = len - 1;
		for (int ipPos = 14; true; ipPos -= 2) {
			if (strPos == start - 1)
				return;
			if (ipPos <= 0) throw new EncodingException(INVALID + str);
			long n = HexaDecimalEncoding.decodeChar(str.charAt(strPos--));
			char c;
			for (int i = 0; i < 3; ++i) {
				if (strPos == start - 1) {
					DataUtil.Write16U.BE.write(ip, ipPos, (int)n);
					return;
				}
				c = str.charAt(strPos--);
				if (c == ':') {
					DataUtil.Write16U.BE.write(ip, ipPos, (int)n);
					n = -1;
					break;
				}
				n |= HexaDecimalEncoding.decodeChar(c) << ((i + 1) * 4);
			}
			if (n == -1) continue;
			DataUtil.Write16U.BE.write(ip, ipPos, (int)n);
			if (strPos == start - 1)
				return;
			if (str.charAt(strPos--) != ':') throw new EncodingException(INVALID + str);
		}
	}
	
	/** Create a byte array from a String representation of a IPv4 or IPv6 address. 
	 * @throws EncodingException if a character is not valid*/
	public static byte[] fromString(CharSequence str) throws EncodingException {
		if (str.length() < 7)
			return fromStringV6(str);
		char c = str.charAt(0);
		if (c > '9')
			return fromStringV6(str);
		c = str.charAt(1);
		if (c == '.')
			return fromStringV4(str);
		if (c > '9')
			return fromStringV6(str);
		c = str.charAt(2);
		if (c == '.')
			return fromStringV4(str);
		if (c > '9')
			return fromStringV6(str);
		if (str.charAt(3) != '.')
			return fromStringV6(str);
		return fromStringV4(str);
	}

	/** Serialize and deserialize an IP address to and from a string. */
	public static class IPSerializer implements CustomSerializer {

		@Override
		public TypeDefinition sourceType() { return new TypeDefinition(byte[].class); }
		
		@Override
		public TypeDefinition targetType() { return new TypeDefinition(String.class); }

		@Override
		public Object serialize(Object source, Object containerInstance) {
			if (source == null) return null;
			byte[] src = (byte[])source;
			if (src.length == 4) {
				// IPv4
				return toStringV4(src);
			}
			if (src.length == 16) {
				StringBuilder s = new StringBuilder(39);
				for (int i = 0; i < 16; ++i) {
					if (i > 0 && (i % 2) == 0) s.append(':');
					s.append(HexaDecimalEncoding.encodeDigit((src[i] & 0xFF) >> 4));
					s.append(HexaDecimalEncoding.encodeDigit((src[i] & 0xFF) & 0xF));
				}
				return s.toString();
			}
			return null;
		}

		@Override
		@SuppressWarnings("squid:S2692") // indexOf > 0
		public Object deserialize(Object source, Object containerInstance) throws SerializationException {
			if (source == null) return null;
			String src = (String)source;
			src = src.trim();
			if (src.length() == 0) return null;
			try {
				return fromString(src);
			} catch (EncodingException e) {
				throw new SerializationException(e.getMessage(), e);
			}
		}
		
	}

}
