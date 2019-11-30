package net.lecousin.framework.network.session;

/** Error while storing session data. */
public class SessionStorageException extends Exception {

	private static final long serialVersionUID = 1L;

	/** Constructor. */
	public SessionStorageException(String message) {
		super(message);
	}
	
	/** Constructor. */
	public SessionStorageException(String message, Throwable cause) {
		super(message, cause);
	}
	
}
