package net.lecousin.framework.network;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.collections.ArrayUtil;

// skip checkstyle: MethodName
/**
 * Utility methods for network.
 */
@SuppressWarnings("squid:S00100") // methods' name
public final class NetUtil {
	
	private NetUtil() { /* no instance */ }

	/** Return the NetworkInterface corresponding to the given MAC address. */
	public static NetworkInterface getInterfaceFromMAC(byte[] mac) throws SocketException {
		Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
		while (e.hasMoreElements()) {
			NetworkInterface i = e.nextElement();
			try {
				if (ArrayUtil.equals(i.getHardwareAddress(), mac))
					return i;
			} catch (Exception ex) { /* ignore */ }
		}
		return null;
	}
	
	/** Return the list of IP addresses for the network interface having the given MAC address. */
	public static List<InetAddress> getIPsFromMAC(byte[] mac) throws SocketException {
		NetworkInterface i = getInterfaceFromMAC(mac);
		if (i == null)
			return new ArrayList<>(0);
		return Collections.list(i.getInetAddresses());
	}
	
	/** Return the list of all IP addresses from all network interfaces. */
	public static List<InetAddress> getAllIPs() throws SocketException {
		Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
		LinkedList<InetAddress> ips = new LinkedList<>();
		while (e.hasMoreElements()) {
			NetworkInterface i = e.nextElement();
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
	
}
