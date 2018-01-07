package net.lecousin.framework.network.session;

/** Base interface to represent a Session, where we can store data. */
public interface ISession {

	/** Retrieve a stored data. */
	Object getData(String key);
	
	/** Store a data. */
	void putData(String key, Object data);
	
}
