package net.lecousin.framework.network.name;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.plugins.ExtensionPoint;
import net.lecousin.framework.plugins.ExtensionPoints;

public class NameServiceRegistry implements ExtensionPoint<NameService> {
	
	public static NameServiceRegistry get() {
		return ExtensionPoints.getExtensionPoint(NameServiceRegistry.class);
	}

	private List<NameService> services = new LinkedList<>();
	
	public NameServiceRegistry() {
		services.add(new JdkNameService());
	}
	
	@Override
	public Class<NameService> getPluginClass() {
		return NameService.class;
	}

	@Override
	public void addPlugin(NameService plugin) {
		services.add(plugin);
	}

	@Override
	public void allPluginsLoaded() {
		services = new ArrayList<>(services);
	}

	@Override
	public List<NameService> getPlugins() {
		return services;
	}
	
}
