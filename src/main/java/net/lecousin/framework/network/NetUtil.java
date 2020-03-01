package net.lecousin.framework.network;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.collections.ArrayUtil;
import net.lecousin.framework.encoding.EncodingException;
import net.lecousin.framework.encoding.HexaDecimalEncoding;
import net.lecousin.framework.serialization.CustomSerializer;
import net.lecousin.framework.serialization.SerializationException;
import net.lecousin.framework.serialization.TypeDefinition;
import net.lecousin.framework.text.StringUtil;

// skip checkstyle: MethodName
/**
 * Utility methods for network.
 */
@SuppressWarnings("squid:S00100") // methods' name
public final class NetUtil {
	
	private NetUtil() { /* no instance */ }

	/** Convert a string containing a MAC address into an array of bytes. 
	 * @throws EncodingException if a character is not valid. */
	public static byte[] MACFromString(String str) throws EncodingException {
		String[] strs = str.split(":");
		byte[] mac = new byte[strs.length];
		for (int i = 0; i < strs.length; ++i)
			mac[i] = StringUtil.decodeHexaByte(strs[i]);
		return mac;
	}
	
	/** Create a text representation of the given MAC address. */
	public static String MACToString(byte[] mac) {
		StringBuilder s = new StringBuilder();
		for (int i = 0; i < mac.length; ++i) {
			if (i > 0) s.append(':');
			s.append(StringUtil.encodeHexa(mac[i]));
		}
		return s.toString();
	}
	
	/** Create a 4-bytes array from a String representation of an IPv4 address. 
	 * @throws EncodingException if a value is not a valid number */
	public static byte[] IPv4FromString(String str) throws EncodingException {
		String[] strs = str.split("\\.");
		byte[] ip = new byte[strs.length];
		for (int i = 0; i < strs.length; ++i)
			try { ip[i] = (byte)Integer.parseInt(strs[i]); }
			catch (NumberFormatException e) { throw new EncodingException("Invalid IP value " + strs[i]); }
		return ip;
	}

	/** Create a String representation of the given IPv4 address. */
	public static String IPv4ToString(byte[] ip) {
		StringBuilder s = new StringBuilder();
		for (int i = 0; i < ip.length; ++i) {
			if (i > 0) s.append('.');
			s.append(ip[i] & 0xFF);
		}
		return s.toString();
	}
	
	/** Create a 16-bytes array from a String representation of an IPv6 address. 
	 * @throws EncodingException if a character is not valid */
	public static byte[] IPv6FromString(String str) throws EncodingException {
		byte[] ip = new byte[16];
		String[] strs = str.split("\\:");
		if (str.startsWith("::")) {
			for (int j = strs.length - 1, pos = 7; j > 1; --j, --pos) {
				String s = strs[j];
				long l = StringUtil.decodeHexaLong(s);
				ip[pos * 2] = (byte)((l & 0xFF00) >> 8);
				ip[pos * 2 + 1] = (byte)(l & 0xFF);
			}
		} else {
			for (int i = 0; i < strs.length; ++i) {
				String s = strs[i];
				if (s.length() == 0) {
					for (int j = strs.length - 1, pos = 7; j > i; --j, --pos) {
						s = strs[j];
						long l = StringUtil.decodeHexaLong(s);
						ip[pos * 2] = (byte)((l & 0xFF00) >> 8);
						ip[pos * 2 + 1] = (byte)(l & 0xFF);
					}
					break;
				}
				long l = StringUtil.decodeHexaLong(s);
				ip[i * 2] = (byte)((l & 0xFF00) >> 8);
				ip[i * 2 + 1] = (byte)(l & 0xFF);
			}
		}
		return ip;
	}
	
	/** Create a byte array from a String representation of a IPv4 or IPv6 address. 
	 * @throws EncodingException if a character is not valid*/
	public static byte[] IPFromString(String str) throws EncodingException {
		if (str.indexOf(':') >= 0)
			return IPv6FromString(str);
		return IPv4FromString(str);
	}
	
	/** Return the list of IP addresses for the network interface having the given MAC address. */
	public static List<InetAddress> getIPsFromMAC(byte[] mac) throws SocketException {
		Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
		while (e.hasMoreElements()) {
			NetworkInterface i = e.nextElement();
			try {
				if (ArrayUtil.equals(i.getHardwareAddress(), mac))
					return Collections.list(i.getInetAddresses());
			} catch (Exception ex) { /* ignore */ }
		}
		return null;
	}
	
	/** Return the list of all IP addresses from all network interfaces. */
	public static List<InetAddress> getAllIPs() throws SocketException {
		Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
		LinkedList<InetAddress> ips = new LinkedList<>();
		while (e.hasMoreElements()) {
			NetworkInterface i = e.nextElement();
			/*
			if (i.isLoopback()) {
				ips.add(InetAddress.getLoopbackAddress());
				continue;
			}*/
			Enumeration<InetAddress> addresses = i.getInetAddresses();
			while (addresses.hasMoreElements()) {
				InetAddress a = addresses.nextElement();
				byte[] b = a.getAddress();
				boolean isZero = true;
				for (int j = 0; j < b.length && isZero; ++j)
					isZero &= b[j] == 0;
				if (!isZero)
					ips.add(a);
			}
		}
		return ips;
	}
	
	/** Return the IPv6 loopback address if it is enabled, or null if not supported. */
	public static Inet6Address getLoopbackIPv6Address() {
		try {
			Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
			while (e.hasMoreElements()) {
				NetworkInterface i = e.nextElement();
				if (!i.isLoopback()) continue;
				Enumeration<InetAddress> addresses = i.getInetAddresses();
				while (addresses.hasMoreElements()) {
					InetAddress a = addresses.nextElement();
					if (a instanceof Inet6Address)
						return (Inet6Address)a;
				}
			}
		} catch (Exception t) {
			// ignore
		}
		return null;
	}
	
	/** Serialize and descrialize an IP address to and from a string. */
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
				return IPv4ToString(src);
			}
			if (src.length == 16) {
				StringBuilder s = new StringBuilder();
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
				if (src.indexOf('.') > 0)
					return IPv4FromString(src);
				if (src.indexOf(':') > 0) {
					byte[] ip = new byte[16];
					for (int i = 0; i < 8; ++i) {
						ip[i * 2 + 0] = (byte) (
							 (HexaDecimalEncoding.decodeChar(src.charAt(i * 5 + 0)) << 4)
							| HexaDecimalEncoding.decodeChar(src.charAt(i * 5 + 1)));
						ip[i * 2 + 1] = (byte) (
							 (HexaDecimalEncoding.decodeChar(src.charAt(i * 5 + 2)) << 4)
							| HexaDecimalEncoding.decodeChar(src.charAt(i * 5 + 3)));
					}
					return ip;
				}
				return null;
			} catch (EncodingException e) {
				throw new SerializationException("Invalid IP value: " + src, e);
			}
		}
		
	}
	
}
