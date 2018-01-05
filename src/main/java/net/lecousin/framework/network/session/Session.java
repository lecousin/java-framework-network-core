package net.lecousin.framework.network.session;

import java.util.HashMap;

/**
 * A session is identified by a unique identifier, and stores a mapping from keys to values.
 */
public class Session implements ISession {

	/** Constructor. */
	public Session(String id) {
		this.id = id;
	}
	
	private String id;
	private HashMap<String,Object> data = new HashMap<>();
	
	public String getId() { return id; }
	
	@Override
	public Object getData(String key) { return data.get(key); }
	
	@Override
	public void putData(String key, Object data) { this.data.put(key, data); }
	
}
