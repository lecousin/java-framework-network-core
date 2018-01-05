package net.lecousin.framework.network.session;

import java.io.Closeable;

/**
 * A SessionStorage is able to save and load sessions.
 */
public interface SessionStorage extends Closeable {

	/** Allocate a unique storage identifier. */
	public String allocateId();
	
	/** Release a storage identifier. */
	public void freeId(String id);
	
	/** Save a session. */
	public void save(String id, Session session);
	
	/** Load a session. */
	public Session load(String id);
	
}
