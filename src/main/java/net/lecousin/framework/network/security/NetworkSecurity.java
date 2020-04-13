package net.lecousin.framework.network.security;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import net.lecousin.framework.application.Application;
import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.CancelException;
import net.lecousin.framework.concurrent.Executable;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.async.JoinPoint;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.FileIO;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.plugins.ExtensionPoints;
import net.lecousin.framework.serialization.SerializationException;
import net.lecousin.framework.serialization.TypeDefinition;
import net.lecousin.framework.xml.serialization.XMLDeserializer;
import net.lecousin.framework.xml.serialization.XMLSerializer;

/**
 * Utility to manage security.
 */
public class NetworkSecurity {
	
	/** Get the instance for the given application. */
	public static synchronized NetworkSecurity get(Application app) {
		NetworkSecurity instance = app.getInstance(NetworkSecurity.class);
		if (instance == null) {
			instance = new NetworkSecurity();
			app.setInstance(NetworkSecurity.class, instance);
			instance.load(app);
		}
		return instance;
	}
	
	private File appCfgDir;
	private Logger logger;
	private JoinPoint<NoException> loaded = new JoinPoint<>();
	private Map<NetworkSecurityPlugin, NetworkSecurityFeature> plugins = new HashMap<>();
	private Map<String, NetworkSecurityFeature> instances = new HashMap<>();
	
	private NetworkSecurity() {
	}
	
	private void load(Application app) {
		logger = app.getLoggerFactory().getLogger(NetworkSecurity.class);
		logger.info("Loading security configuration for application " + app.getGroupId() + "-" + app.getArtifactId());
		appCfgDir = new File(app.getProperty(Application.PROPERTY_CONFIG_DIRECTORY));
		if (!appCfgDir.exists() && !appCfgDir.mkdirs())
			logger.error("Unable to create directory " + appCfgDir.getAbsolutePath());
		loadConfiguration(app, loaded);

		Task<Void, NoException> taskSave = Task.cpu("Save network security", Task.Priority.LOW, new Executable.FromRunnable(this::save));
		taskSave.executeEvery(2L * 60 * 1000, 30L * 1000);
		
		Task<Void, NoException> taskClean = Task.cpu("Cleaning network security", Task.Priority.LOW,
			new Executable.FromRunnable(this::clean));
		taskClean.executeEvery(5L * 60 * 1000, 10L * 60 * 1000);
		
		app.toClose(100, () -> {
			taskSave.cancel(new CancelException("Application stopping"));
			taskClean.cancel(new CancelException("Application stopping"));
			clean();
			save().block(0);
		});
		loaded.start();
		
		loaded.onDone(() -> logger.info("Security configuration loaded for application " + app.getGroupId() + "-" + app.getArtifactId()));
		loaded.thenStart(taskSave, true);
		loaded.thenStart(taskClean, true);
	}
	
	private void loadConfiguration(Application app, JoinPoint<NoException> loading) {
		for (NetworkSecurityPlugin plugin : ExtensionPoints.getExtensionPoint(NetworkSecurityExtensionPoint.class).getPlugins()) {
			File cfgFile = new File(appCfgDir, plugin.getClass().getName() + ".xml");
			if (cfgFile.exists()) {
				AsyncSupplier<Object, SerializationException> res = loadPluginConfiguration(plugin, cfgFile);
				loading.addToJoin(1);
				res.onDone(cfg -> {
					NetworkSecurityFeature instance = addPluginInstance(plugin, app, cfg);
					logger.info("Configuration loaded for application " + app.getGroupId() + "-" + app.getArtifactId()
						+ ", plugin " + instance.getClass().getName());
					loading.joined();
				}, err -> {
					NetworkSecurityFeature instance = addPluginInstance(plugin, app, null);
					logger.error("Error reading configuration file " + cfgFile.getAbsolutePath()
						+ " for plugin " + instance.getClass().getName(), err);
					loading.joined();
				}, cancel -> loading.joined());
			} else {
				addPluginInstance(plugin, app, null);
			}
		}		
	}
	
	private NetworkSecurityFeature addPluginInstance(NetworkSecurityPlugin plugin, Application app, Object cfg) {
		NetworkSecurityFeature instance = plugin.newInstance(app, cfg);
		instances.put(instance.getClass().getName(), instance);
		plugins.put(plugin, instance);
		if (cfg != null)
			instance.clean();
		return instance;
	}
	
	public IAsync<NoException> isLoaded() {
		return loaded;
	}
	
	/** Return the requested feature. */
	@SuppressWarnings("unchecked")
	public <T extends NetworkSecurityFeature> T getFeature(Class<T> clazz) {
		return (T)instances.get(clazz.getName());
	}
	
	public static AsyncSupplier<Object, SerializationException> loadPluginConfiguration(NetworkSecurityPlugin plugin, File cfgFile) {
		FileIO.ReadOnly input = new FileIO.ReadOnly(cfgFile, Task.Priority.IMPORTANT);
		AsyncSupplier<Object, SerializationException> res =
			new XMLDeserializer(null, plugin.getClass().getSimpleName()).deserialize(
				new TypeDefinition(plugin.getConfigurationClass()), input, new ArrayList<>(0));
		res.onDone(() -> {
			try {
				input.close();
			} catch (Exception e) {
				LCCore.getApplication().getDefaultLogger().error("Unable to close " + cfgFile.getAbsolutePath(), e);
			}
		});
		return res;
	}
	
	/** Save the configuration to the application configuration directory. */
	public IAsync<NoException> save() {
		JoinPoint<NoException> jp = new JoinPoint<>();
		if (!appCfgDir.exists()) {
			jp.start();
			return jp;
		}

		for (Map.Entry<NetworkSecurityPlugin, NetworkSecurityFeature> entry : plugins.entrySet()) {
			NetworkSecurityPlugin plugin = entry.getKey();
			NetworkSecurityFeature instance = entry.getValue();
			Object cfg = instance.getConfigurationIfChanged();
			if (cfg == null)
				continue;
			File cfgFile = new File(appCfgDir, plugin.getClass().getName() + ".xml");
			if (cfgFile.exists()) {
				try {
					Files.delete(cfgFile.toPath());
				} catch (Exception e) {
					logger.error("Unable to delete configuration file " + cfgFile.getAbsolutePath(), e);
					continue;
				}
			}
			FileIO.WriteOnly output = new FileIO.WriteOnly(cfgFile, Task.Priority.IMPORTANT);
			IAsync<SerializationException> ser =
				new XMLSerializer(null, plugin.getClass().getSimpleName(), null, StandardCharsets.UTF_8, 4096, true)
					.serialize(cfg, new TypeDefinition(plugin.getConfigurationClass()), output, new ArrayList<>(0));
			jp.addToJoin(1);
			ser.onDone(() -> {
				output.closeAsync().onDone(jp::joined);
			}, err -> {
				logger.error("Error saving configuration file " + cfgFile.getAbsolutePath(), err);
				output.closeAsync().onDone(jp::joined);
			}, cancel -> jp.joined());
		}

		jp.start();
		return jp;
	}
	
	private void clean() {
		for (NetworkSecurityFeature instance : plugins.values()) {
			instance.clean();
		}
	}
	
}
