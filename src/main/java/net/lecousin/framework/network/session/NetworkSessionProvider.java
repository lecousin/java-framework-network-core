package net.lecousin.framework.network.session;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Random;

import net.lecousin.framework.application.Application;
import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.collections.ArrayUtil;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.encoding.EncodingException;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.network.TCPRemote;
import net.lecousin.framework.network.security.BruteForceAttempt;
import net.lecousin.framework.network.security.NetworkSecurity;
import net.lecousin.framework.text.StringUtil;

/**
 * Adds some security checks for sessions:
 * It creates ids with a timestamp and a random value.
 * It stores the client IP address.
 */
public class NetworkSessionProvider implements SessionProvider<TCPRemote> {
	
	public static final String BRUTE_FORCE_FUNCTIONALITY = "Session";

	/** Constructor. */
	public NetworkSessionProvider(SessionStorage storage, Application app) {
		this.storage = storage;
		this.app = app;
	}
	
	private SessionStorage storage;
	private Application app;
	private Random rand = new Random();
	
	@Override
	public void close() throws IOException {
		storage.close();
	}
	
	@Override
	public Session create(TCPRemote client) {
		String sid;
		try { sid = storage.allocateId(); }
		catch (Exception t) {
			LCCore.getApplication().getDefaultLogger().error("Unable to create session", t);
			return null;
		}
		long timestamp = System.currentTimeMillis();
		long r = rand.nextLong();
		String id = StringUtil.encodeHexaPadding(timestamp) + sid + StringUtil.encodeHexaPadding(r);
		Session s = new Session(id);
		SocketAddress ip;
		try { ip = client.getRemoteAddress(); }
		catch (IOException e) {
			storage.remove(sid);
			return null;
		}
		if (!(ip instanceof InetSocketAddress)) {
			storage.remove(sid);
			return null;
		}
		s.putData("_nsip", ((InetSocketAddress)ip).getAddress().getAddress());
		s.putData("_nsts", Long.valueOf(timestamp));
		s.putData("_nsrd", Long.valueOf(r));
		return s;
	}
	
	@Override
	public AsyncSupplier<Session, NoException> get(String id, TCPRemote client) {
		if (id == null) return null;
		if (id.length() != 3 * 16) {
			NetworkSecurity.get(app).getFeature(BruteForceAttempt.class).attempt(client, BRUTE_FORCE_FUNCTIONALITY, id);
			return new AsyncSupplier<>(null, null);
		}
		Session session = new Session(id);
		String sid = id.substring(16, id.length() - 16);
		AsyncSupplier<Boolean, SessionStorageException> loadSession = storage.load(sid, session);
		String ts = id.substring(0,16);
		String r = id.substring(id.length() - 16);
		AsyncSupplier<Session, NoException> result = new AsyncSupplier<>();
		Runnable check = () -> {
			if (loadSession.hasError()) {
				LCCore.getApplication().getDefaultLogger().error("Error loading session " + sid, loadSession.getError());
				result.unblockSuccess(null);
				return;
			}
			if (!loadSession.getResult().booleanValue())
				result.unblockSuccess(null);
			else
				result.unblockSuccess(checkSession(session, client, ts, r, sid));
		};
		if (loadSession.isDone()) {
			check.run();
		} else {
			loadSession.thenStart("Check newtork session", Task.Priority.NORMAL, check, true);
		}
		return result;
	}
	
	private Session checkSession(Session s, TCPRemote client, String ts, String r, String sid) {
		try {
			if (StringUtil.decodeHexaLong(r) != ((Long)s.getData("_nsrd")).longValue()) {
				NetworkSecurity.get(app).getFeature(BruteForceAttempt.class).attempt(client, BRUTE_FORCE_FUNCTIONALITY, sid);
				return null;
			}
			if (StringUtil.decodeHexaLong(ts) != ((Long)s.getData("_nsts")).longValue()) {
				NetworkSecurity.get(app).getFeature(BruteForceAttempt.class).attempt(client, BRUTE_FORCE_FUNCTIONALITY, sid);
				return null;
			}
		} catch (EncodingException e) {
			NetworkSecurity.get(app).getFeature(BruteForceAttempt.class).attempt(client, BRUTE_FORCE_FUNCTIONALITY, sid);
			return null;
		}
		byte[] sip = (byte[])s.getData("_nsip");
		SocketAddress ip;
		try { ip = client.getRemoteAddress(); }
		catch (IOException e) { return null; }
		if (!(ip instanceof InetSocketAddress)) return null;
		byte[] cip = ((InetSocketAddress)ip).getAddress().getAddress();
		if (!ArrayUtil.equals(sip, cip)) {
			NetworkSecurity.get(app).getFeature(BruteForceAttempt.class).attempt(client, BRUTE_FORCE_FUNCTIONALITY, sid);
			return null;
		}
		return s;
	}
	
	@Override
	public void save(Session session, TCPRemote client) {
		String id = session.getId();
		String sid = id.substring(16, id.length() - 16);
		storage.save(sid, session);
	}
	
	@Override
	public void destroy(Session session) {
		String id = session.getId();
		String sid = id.substring(16, id.length() - 16);
		storage.release(sid);
		storage.remove(sid);
	}
	
	@Override
	public SessionStorage getStorage() {
		return storage;
	}
}
