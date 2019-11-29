package net.lecousin.framework.network.security;

/** Network security plug-in for a specific application. */
public interface NetworkSecurityFeature {

	/** Return a serializable configuration to save or null if nothing changed since last call. */
	Object getConfigurationIfChanged();
	
	/** Clean data according to possible timeouts. */
	void clean();
	
}
