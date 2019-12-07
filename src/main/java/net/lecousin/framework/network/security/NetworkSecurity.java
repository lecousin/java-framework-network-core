package net.lecousin.framework.network.security;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import net.lecousin.framework.application.Application;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.CancelException;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.async.JoinPoint;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.FileIO;
import net.lecousin.framework.io.serialization.SerializationException;
import net.lecousin.framework.io.serialization.TypeDefinition;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.plugins.ExtensionPoints;
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
	private Map<Class<?>, NetworkSecurityFeature> instances = new HashMap<>();
	
	private NetworkSecurity() {
	}
	
	private void load(Application app) {
		logger = app.getLoggerFactory().getLogger(NetworkSecurity.class);
		logger.info("Loading security configuration for application " + app.getGroupId() + "-" + app.getArtifactId());
		appCfgDir = new File(app.getProperty(Application.PROPERTY_CONFIG_DIRECTORY));
		if (!appCfgDir.exists() && !appCfgDir.mkdirs())
			logger.error("Unable to create directory " + appCfgDir.getAbsolutePath());
		loadConfiguration(app, loaded);

		Task<Void, NoException> taskSave = new Task.Cpu.FromRunnable("Save network security", Task.PRIORITY_LOW, this::save);
		taskSave.executeEvery(2L * 60 * 1000, 30L * 1000);
		
		Task<Void, NoException> taskClean = new Task.Cpu.FromRunnable("Cleaning network security", Task.PRIORITY_LOW, this::clean);
		taskClean.executeEvery(5L * 60 * 1000, 10L * 60 * 1000);
		
		app.toClose(() -> {
			taskSave.cancel(new CancelException("Application stopping"));
			taskClean.cancel(new CancelException("Application stopping"));
			clean();
			save().block(0);
		});
		loaded.start();
		
		loaded.thenStart(taskSave, true);
		loaded.thenStart(taskClean, true);
	}
	
	private void loadConfiguration(Application app, JoinPoint<NoException> loading) {
		for (NetworkSecurityPlugin plugin : ExtensionPoints.getExtensionPoint(NetworkSecurityExtensionPoint.class).getPlugins()) {
			File cfgFile = new File(appCfgDir, plugin.getClass().getName() + ".xml");
			if (cfgFile.exists()) {
				FileIO.ReadOnly input = new FileIO.ReadOnly(cfgFile, Task.PRIORITY_IMPORTANT);
				AsyncSupplier<Object, SerializationException> res =
					new XMLDeserializer(null, plugin.getClass().getSimpleName()).deserialize(
						new TypeDefinition(plugin.getConfigurationClass()), input, new ArrayList<>(0));
				loading.addToJoin(1);
				res.onDone(cfg -> {
					NetworkSecurityFeature instance = plugin.newInstance(app, cfg);
					instances.put(instance.getClass(), instance);
					plugins.put(plugin, instance);
					instance.clean();
					logger.info("Configuration loaded for application " + app.getGroupId() + "-" + app.getArtifactId()
						+ ", plugin " + instance.getClass().getName());
					loading.joined();
				}, err -> {
					logger.error("Error reading configuration file " + cfgFile.getAbsolutePath(), err);
					NetworkSecurityFeature instance = plugin.newInstance(app, null);
					instances.put(instance.getClass(), instance);
					plugins.put(plugin, instance);
					loading.joined();
				}, cancel -> loading.joined());
				input.closeAfter(res);
			} else {
				NetworkSecurityFeature instance = plugin.newInstance(app, null);
				instances.put(instance.getClass(), instance);
				plugins.put(plugin, instance);
			}
		}		
	}
	
	public IAsync<NoException> isLoaded() {
		return loaded;
	}
	
	/** Return the requested feature. */
	@SuppressWarnings("unchecked")
	public <T extends NetworkSecurityFeature> T getFeature(Class<T> clazz) {
		return (T)instances.get(clazz);
	}
	
	/** Save the configuration to the application configuration directory. */
	public IAsync<NoException> save() {
		JoinPoint<NoException> jp = new JoinPoint<>();
		if (!appCfgDir.exists()) {
			jp.start();
			return jp;
		}

		for (NetworkSecurityPlugin plugin : ExtensionPoints.getExtensionPoint(NetworkSecurityExtensionPoint.class).getPlugins()) {
			NetworkSecurityFeature instance = plugins.get(plugin);
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
			FileIO.WriteOnly output = new FileIO.WriteOnly(cfgFile, Task.PRIORITY_IMPORTANT);
			IAsync<SerializationException> ser =
				new XMLSerializer(null, plugin.getClass().getSimpleName(), null, StandardCharsets.UTF_8, 4096, true)
					.serialize(cfg, new TypeDefinition(plugin.getConfigurationClass()), output, new ArrayList<>(0));
			jp.addToJoin(1);
			ser.onDone(jp::joined, err -> {
				logger.error("Error saving configuration file " + cfgFile.getAbsolutePath(), err);
				jp.joined();
			}, cancel -> jp.joined());
			output.closeAfter(ser);
		}

		jp.start();
		return jp;
	}
	
	private void clean() {
		for (NetworkSecurityPlugin plugin : ExtensionPoints.getExtensionPoint(NetworkSecurityExtensionPoint.class).getPlugins()) {
			NetworkSecurityFeature instance = plugins.get(plugin);
			instance.clean();
		}
	}
	
}
