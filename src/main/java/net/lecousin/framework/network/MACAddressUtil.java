package net.lecousin.framework.network;

import net.lecousin.framework.encoding.EncodingException;
import net.lecousin.framework.encoding.HexaDecimalEncoding;

/** Utilities for MAC addresses. */
public final class MACAddressUtil {
	
	private MACAddressUtil() {
		// no instance
	}
	
	private static final String INVALID = "Invalid MAC address: ";
	
	/** Convert a string containing a MAC address into an array of bytes.<br/>
	 * Supported formats are:<ul>
	 * <li>groups of two hexadecimal digits separated by a non hexadecimal character, in transmission order.
	 * Common standards use hypens (-) or colons (:) as separator.
	 * </li>
	 * <li>groups of four hexadecimal digits separated by a non hexadecimal character, in transmission order.
	 * Common standards use dots (.) as separator</li>
	 * </ul>
	 * @throws EncodingException if the string is not a valid MAC address. */
	public static byte[] fromString(CharSequence str) throws EncodingException {
		int len = str.length();
		if (len < 14) throw new EncodingException(INVALID + str);
		
		// if the third character is hexadecimal, it uses format with groups of four digits
		char sep = str.charAt(2);
		if (HexaDecimalEncoding.isHexaDigit(sep)) {
			sep = str.charAt(4);
			int nbGroups = (len + 1) / 5;
			if (len + 1 != nbGroups * 5) throw new EncodingException(INVALID + str);
			byte[] mac = new byte[nbGroups * 2];
			for (int i = 0; i < nbGroups; ++i) {
				mac[i * 2] = (byte)(
					(HexaDecimalEncoding.decodeChar(str.charAt(i * 5)) << 4)
					| HexaDecimalEncoding.decodeChar(str.charAt(i * 5 + 1)));
				mac[i * 2 + 1] = (byte)((
					HexaDecimalEncoding.decodeChar(str.charAt(i * 5 + 2)) << 4)
					| HexaDecimalEncoding.decodeChar(str.charAt(i * 5 + 3)));
				if (i < nbGroups - 1 && str.charAt(i * 5 + 4) != sep)
					throw new EncodingException(INVALID + str);
			}
			return mac;
		}
		
		// groups of 2 digits
		int nbGroups = (len + 1) / 3;
		if (len + 1 != nbGroups * 3) throw new EncodingException(INVALID + str);
		byte[] mac = new byte[nbGroups];
		for (int i = 0; i < nbGroups; ++i) {
			mac[i] = (byte)(
				(HexaDecimalEncoding.decodeChar(str.charAt(i * 3)) << 4)
				| HexaDecimalEncoding.decodeChar(str.charAt(i * 3 + 1)));
			if (i < nbGroups - 1 && str.charAt(i * 3 + 2) != sep)
				throw new EncodingException(INVALID + str);
		}
		return mac;
	}

	/** Create a text representation of the given MAC address using groups of 2 digits separated by colons. */
	public static char[] toString(byte[] mac) {
		return toStringGroups2Digits(mac, ':');
	}

	/** Create a text representation of the given MAC address. */
	public static char[] toStringGroups2Digits(byte[] mac, char separator) {
		char[] chars = new char[mac.length * 3 - 1];
		for (int i = 0; i < mac.length; ++i) {
			chars[i * 3] = HexaDecimalEncoding.encodeDigit((mac[i] & 0xF0) >> 4);
			chars[i * 3 + 1] = HexaDecimalEncoding.encodeDigit(mac[i] & 0x0F);
			if (i < mac.length - 1) chars[i * 3 + 2] = separator;
		}
		return chars;
	}

}
