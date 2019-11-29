package net.lecousin.framework.network.session;

import java.io.Closeable;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.exception.NoException;

/**
 * Provides sessions for a specific type of client.
 * @param <T> type of client
 */
public interface SessionProvider<T> extends Closeable {

	/** Retrieve a session with an id and the client. */
	AsyncSupplier<Session, NoException> get(String id, T client);
	
	/** Create a new session for the client. */
	Session create(T client);
	
	/** Save the session. */
	void save(Session session, T client);
	
	/** Destroy a session. */
	void destroy(Session session);
	
	/** Return the storage. */
	SessionStorage getStorage();
	
}
