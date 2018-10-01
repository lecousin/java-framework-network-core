package net.lecousin.framework.network.session;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Set;

/**
 * A session is identified by a unique identifier, and stores a mapping from keys to values.
 */
public class Session implements ISession {

	/** Constructor. */
	public Session(String id) {
		this.id = id;
	}
	
	private String id;
	private HashMap<String, Serializable> data = new HashMap<>();
	
	public String getId() { return id; }
	
	@Override
	public Serializable getData(String key) { return data.get(key); }
	
	@Override
	public void putData(String key, Serializable data) { this.data.put(key, data); }
	
	@Override
	public void removeData(String key) { this.data.remove(key); }
	
	@Override
	public Set<String> getKeys() {
		return data.keySet();
	}
	
}
