package net.lecousin.framework.network.security;

import net.lecousin.framework.application.Application;
import net.lecousin.framework.plugins.Plugin;

/** Plug-in for network security. */
public interface NetworkSecurityPlugin extends Plugin {

	/** Get the serializable class for loading and saving configuration. */
	Class<?> getConfigurationClass();
	
	/** Create an instance for the given application. */
	NetworkSecurityFeature newInstance(Application app, Object configuration);
	
}
