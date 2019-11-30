package net.lecousin.framework.network.session;

import java.io.Closeable;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;

/**
 * A SessionStorage is able to save and load sessions.
 * When a session is loaded, it must be released or saved before to load it again.
 */
public interface SessionStorage extends Closeable {

	/** Allocate an identifier, but nothing is stored until it is saved.
	 * However if the session is not used, the identifier must be freed by calling the remove method. */
	String allocateId() throws SessionStorageException;
	
	/** Load a session, returns false if the session does not exist or expired. */
	AsyncSupplier<Boolean, SessionStorageException> load(String id, ISession session);
	
	/** Remove a session, which must not be loaded. */
	void remove(String id);
	
	/** Release a session without saving. */
	void release(String id);
	
	/** Save a session. */
	IAsync<SessionStorageException> save(String id, ISession session);
	
	/** Return the time in milliseconds after a session expires, 0 or negative value means never. */
	long getExpiration();
	
}
