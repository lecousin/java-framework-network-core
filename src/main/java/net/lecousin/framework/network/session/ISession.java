package net.lecousin.framework.network.session;

import java.io.Serializable;
import java.util.Set;

/** Base interface to represent a Session, where we can store data. */
public interface ISession {

	/** Retrieve a stored data. */
	Serializable getData(String key);
	
	/** Store a data. */
	void putData(String key, Serializable data);
	
	/** Remove a data. */
	void removeData(String key);
	
	/** Return the keys of data stored in this session. */
	Set<String> getKeys();
	
}
