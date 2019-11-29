package net.lecousin.framework.network.session;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.encoding.HexaDecimalEncoder;
import net.lecousin.framework.memory.IMemoryManageable;
import net.lecousin.framework.memory.MemoryManager;
import net.lecousin.framework.util.IDManagerString;
import net.lecousin.framework.util.IDManagerStringFromLong;
import net.lecousin.framework.util.Pair;
import net.lecousin.framework.util.RandomIDManagerLong;
import net.lecousin.framework.util.StringEncoding;

/**
 * Store sessions in memory.
 */
public class SessionInMemory implements SessionStorage, IMemoryManageable {

	/** Constructor. */
	public SessionInMemory(long expiration) {
		this(new IDManagerStringFromLong(new RandomIDManagerLong(), new StringEncoding.EncodedLong(new HexaDecimalEncoder())), expiration);
	}
	
	/** Constructor. */
	public SessionInMemory(IDManagerString idManager, long expiration) {
		this.idManager = idManager;
		this.expiration = expiration;
		if (expiration > 0) {
			checkExpirationTask = new Task.Cpu.FromRunnable("Check expired sessions", Task.PRIORITY_LOW, this::checkExpiredSessions)
				.executeEvery(30L * 60 * 1000, 60L * 60 * 1000);
			MemoryManager.register(this);
		}
	}
	
	private HashMap<String, Pair<Long,List<Pair<String, Serializable>>>> sessions = new HashMap<>(100);
	private IDManagerString idManager;
	private long expiration;
	private Task<Void, NoException> checkExpirationTask;
	
	@Override
	public void close() {
		if (expiration > 0) {
			MemoryManager.unregister(this);
			if (checkExpirationTask != null)
				checkExpirationTask.stopRepeat();
			checkExpirationTask = null;
		}
		sessions = null;
		idManager = null;
	}
	
	@Override
	public synchronized String allocateId() {
		return idManager.allocate();
	}
	
	@Override
	public AsyncSupplier<Boolean, Exception> load(String id, ISession session) {
		Pair<Long, List<Pair<String, Serializable>>> s;
		synchronized (this) { s = sessions.get(id); }
		if (s == null) return new AsyncSupplier<>(Boolean.FALSE, null);
		long now = System.currentTimeMillis();
		if (expiration > 0 && now - s.getValue1().longValue() >= expiration) {
			remove(id);
			return new AsyncSupplier<>(Boolean.FALSE, null);
		}
		for (Pair<String, Serializable> p : s.getValue2())
			session.putData(p.getValue1(), p.getValue2());
		return new AsyncSupplier<>(Boolean.TRUE, null);
	}
	
	@Override
	public synchronized void remove(String id) {
		idManager.free(id);
		sessions.remove(id);
	}
	
	@Override
	public void release(String id) {
	}
	
	@Override
	public IAsync<Exception> save(String id, ISession session) {
		Set<String> keys = session.getKeys();
		ArrayList<Pair<String, Serializable>> list = new ArrayList<>(keys.size());
		for (String key : keys)
			list.add(new Pair<>(key, session.getData(key)));
		synchronized (sessions) {
			sessions.put(id, new Pair<>(Long.valueOf(System.currentTimeMillis()), list));
		}
		return new Async<>(true);
	}
	
	@Override
	public long getExpiration() {
		return expiration;
	}
	
	private void checkExpiredSessions() {
		if (expiration <= 0) return;
		long now = System.currentTimeMillis();
		List<String> toRemove = new LinkedList<>();
		synchronized (this) {
			for (Map.Entry<String, Pair<Long, List<Pair<String, Serializable>>>> e : sessions.entrySet())
				if (now - e.getValue().getValue1().longValue() >= expiration)
					toRemove.add(e.getKey());
			for (String id : toRemove) remove(id);
		}
	}
	
	@Override
	public String getDescription() {
		return "Sessions";
	}
	
	@Override
	public List<String> getItemsDescription() {
		return Arrays.asList("Sessions in memory: " + sessions.size());
	}
	
	@Override
	public void freeMemory(FreeMemoryLevel level) {
		checkExpiredSessions();
	}
	
}
