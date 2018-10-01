package net.lecousin.framework.network.session;

import java.io.Closeable;

import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;

/**
 * A SessionStorage is able to save and load sessions.
 * When a session is loaded, it must be released or saved before to load it again.
 */
public interface SessionStorage extends Closeable {

	/** Allocate an identifier, but nothing is stored until it is saved.
	 * However if the session is not used, the identifier must be freed by calling the remove method. */
	public String allocateId() throws Exception;
	
	/** Load a session, returns false if the session does not exist or expired. */
	public AsyncWork<Boolean, Exception> load(String id, ISession session);
	
	/** Remove a session, which must not be loaded. */
	public void remove(String id);
	
	/** Release a session without saving. */
	public void release(String id);
	
	/** Save a session. */
	public ISynchronizationPoint<Exception> save(String id, ISession session);
	
	/** Return the time in milliseconds after a session expires, 0 or negative value means never. */
	public long getExpiration();
	
}
