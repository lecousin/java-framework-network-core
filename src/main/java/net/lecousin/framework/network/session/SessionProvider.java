package net.lecousin.framework.network.session;

import java.io.Closeable;

/**
 * Provides sessions for a specific type of client.
 * @param <T> type of client
 */
public interface SessionProvider<T> extends Closeable {

	/** Retrieve a session with an id and the client. */
	public Session get(String id, T client);
	
	/** Create a new session for the client. */
	public Session create(T client);
	
	/** Save the session. */
	public void save(Session session, T client);
	
	/** Destroy a session. */
	public void destroy(String id);
	
	/** Return the expiration time in milliseconds of a session. */
	public long getExpiration();
	
}
