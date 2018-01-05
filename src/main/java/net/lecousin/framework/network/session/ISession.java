package net.lecousin.framework.network.session;

public interface ISession {

	Object getData(String key);
	
	void putData(String key, Object data);
	
}
