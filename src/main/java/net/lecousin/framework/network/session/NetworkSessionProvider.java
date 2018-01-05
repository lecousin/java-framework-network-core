package net.lecousin.framework.network.session;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Random;

import net.lecousin.framework.collections.ArrayUtil;
import net.lecousin.framework.network.TCPRemote;
import net.lecousin.framework.network.security.NetworkSecurity;
import net.lecousin.framework.util.StringUtil;

/**
 * Adds some security checks for sessions:
 * It creates ids with a timestamp and a random value.
 * It stores the client IP address.
 */
public class NetworkSessionProvider implements SessionProvider<TCPRemote> {

	/** Constructor. */
	public NetworkSessionProvider(SessionStorage storage, long expiration, String securityApplication) {
		this.storage = storage;
		this.expiration = expiration;
		this.securityApplication = securityApplication;
	}
	
	private SessionStorage storage;
	private long expiration;
	private String securityApplication;
	private Random rand = new Random();
	
	@Override
	public void close() throws IOException {
		storage.close();
	}
	
	@Override
	public Session create(TCPRemote client) {
		String sid = storage.allocateId();
		long timestamp = System.currentTimeMillis();
		long r = rand.nextLong();
		String id = StringUtil.encodeHexaPadding(timestamp) + sid + StringUtil.encodeHexaPadding(r);
		Session s = new Session(id);
		SocketAddress ip;
		try { ip = client.getRemoteAddress(); }
		catch (IOException e) { return null; }
		if (!(ip instanceof InetSocketAddress)) return null;
		s.putData("_nsip", ((InetSocketAddress)ip).getAddress().getAddress());
		s.putData("_nsts", Long.valueOf(timestamp));
		s.putData("_nsrd", Long.valueOf(r));
		return s;
	}
	
	@Override
	public Session get(String id, TCPRemote client) {
		if (id == null) return null;
		if (id.length() != 3 * 16) {
			NetworkSecurity.possibleBruteForceAttack(client, securityApplication, "Session", id);
			return null;
		}
		String ts = id.substring(0,16);
		String r = id.substring(id.length() - 16);
		String sid = id.substring(16, id.length() - 16);
		Session s = storage.load(sid);
		if (s == null)
			return null;
		if (StringUtil.decodeHexaLong(r) != ((Long)s.getData("_nsrd")).longValue()) {
			NetworkSecurity.possibleBruteForceAttack(client, securityApplication, "Session", id);
			return null;
		}
		if (StringUtil.decodeHexaLong(ts) != ((Long)s.getData("_nsts")).longValue()) {
			NetworkSecurity.possibleBruteForceAttack(client, securityApplication, "Session", id);
			return null;
		}
		byte[] sip = (byte[])s.getData("_nsip");
		SocketAddress ip;
		try { ip = client.getRemoteAddress(); }
		catch (IOException e) { return null; }
		if (!(ip instanceof InetSocketAddress)) return null;
		byte[] cip = ((InetSocketAddress)ip).getAddress().getAddress();
		if (!ArrayUtil.equals(sip, cip)) {
			NetworkSecurity.possibleBruteForceAttack(client, securityApplication, "Session", id);
			return null;
		}
		return s;
	}
	
	@Override
	public void destroy(String id) {
		String sid = id.substring(16, id.length() - 16);
		storage.freeId(sid);
	}
	
	@Override
	public long getExpiration() {
		return expiration;
	}
}
