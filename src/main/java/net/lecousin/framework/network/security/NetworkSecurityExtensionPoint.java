package net.lecousin.framework.network.security;

import java.util.ArrayList;
import java.util.Collection;

import net.lecousin.framework.plugins.ExtensionPoint;

/** Network security extension point. */
public class NetworkSecurityExtensionPoint implements ExtensionPoint<NetworkSecurityPlugin> {

	private ArrayList<NetworkSecurityPlugin> plugins = new ArrayList<>();
	
	/** Constructor. */
	public NetworkSecurityExtensionPoint() {
		addPlugin(new IPBlackList.Plugin());
		addPlugin(new BruteForceAttempt.Plugin());
	}
	
	@Override
	public Class<NetworkSecurityPlugin> getPluginClass() { return NetworkSecurityPlugin.class; }
	
	@Override
	public void addPlugin(NetworkSecurityPlugin plugin) {
		plugins.add(plugin);
	}
	
	@Override
	public Collection<NetworkSecurityPlugin> getPlugins() {
		return plugins;
	}
	
	@Override
	public void allPluginsLoaded() {
		plugins.trimToSize();
	}

}
