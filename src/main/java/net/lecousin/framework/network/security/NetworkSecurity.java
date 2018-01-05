package net.lecousin.framework.network.security;

import java.io.Closeable;
import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import net.lecousin.framework.application.Application;
import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.CancelException;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.FileIO;
import net.lecousin.framework.io.serialization.TypeDefinition;
import net.lecousin.framework.network.TCPRemote;
import net.lecousin.framework.xml.serialization.XMLDeserializer;
import net.lecousin.framework.xml.serialization.XMLSerializer;

/**
 * Utility to manage security.
 */
public class NetworkSecurity {

	/** Configuration. */
	public static class Config {
		
		public IPBlackList.Config ipBlackList;
		public BruteForceAttempt.Config bruteForceAttempt;
		
		/** Constructor. */
		public Config() {}
		
		private Config(Mapping m) {
			ipBlackList = new IPBlackList.Config(m.ipBlackList);
			bruteForceAttempt = new BruteForceAttempt.Config(m.bruteForceAttempt);
		}
		
	}

	private static class Mapping {
		
		public IPBlackList.Mapping ipBlackList;
		public BruteForceAttempt.Mapping bruteForceAttempt;
		
		public Mapping(Config cfg) {
			ipBlackList = new IPBlackList.Mapping(cfg != null ? cfg.ipBlackList : null);
			bruteForceAttempt = new BruteForceAttempt.Mapping(cfg != null ? cfg.bruteForceAttempt : null);
		}
		
	}
	
	/** Initialize an instance for the current application. */
	@SuppressWarnings("resource")
	public static void init() {
		net.lecousin.framework.application.Application app = LCCore.getApplication();
		NetworkSecurity instance = app.getInstance(NetworkSecurity.class);
		if (instance != null) return;
		NetworkSecurity sec = new NetworkSecurity();
		app.setInstance(NetworkSecurity.class, sec);
		File file = new File(app.getProperty(Application.PROPERTY_CONFIG_DIRECTORY));
		if (!file.exists())
			if (!file.mkdirs()) {
				System.err.println("Unable to create directory " + file.getAbsolutePath());
			}
		file = new File(file, "net.lecousin.framework.network.security.xml");
		Config config = null;
		if (file.exists()) {
			try (FileIO.ReadOnly input = new FileIO.ReadOnly(file, Task.PRIORITY_IMPORTANT)) {
				AsyncWork<Object, Exception> res = new XMLDeserializer(null, "Security").deserialize(new TypeDefinition(Config.class), input, new ArrayList<>(0));
				config = (Config)res.blockResult(0);
			} catch (Throwable t) {
				System.err.println("Error reading configuration file " + file.getAbsolutePath() + ": " + t.getMessage());
				t.printStackTrace(System.err);
			}
		}
		sec.mapping = new Mapping(config);
		config = null;
		sec.cleaning();

		sec.taskSave = new Task.Cpu<Void,NoException>("Save network security", Task.PRIORITY_LOW) {
			@Override
			public Void run() {
				if (!updated) return null;
				sec.save();
				return null;
			}
		};
		sec.taskSave.executeEvery(2 * 60 * 1000, 30 * 1000);
		sec.taskSave.start();
		sec.taskClean = new Task.Cpu<Void,NoException>("Cleaning network security", Task.PRIORITY_LOW) {
			@Override
			public Void run() {
				sec.cleaning();
				return null;
			}
		};
		sec.taskClean.executeEvery(5 * 60 * 1000, 10 * 60 * 1000);
		sec.taskClean.start();
		
		app.toClose(new Closeable() {
			@Override
			public void close() {
				sec.cleaning();
				if (updated)
					sec.save();
				sec.taskSave.cancel(new CancelException("Application stopping"));
				sec.taskClean.cancel(new CancelException("Application stopping"));
			}
		});
	}
	
	
	private Mapping mapping;
	static boolean updated = false;
	
	private Task.Cpu<Void,NoException> taskSave;
	private Task.Cpu<Void,NoException> taskClean;
	
	private void save() {
		File file = new File(LCCore.getApplication().getProperty(Application.PROPERTY_CONFIG_DIRECTORY));
		if (!file.exists())
			if (!file.mkdirs()) {
				System.err.println("Unable to create directory " + file.getAbsolutePath());
			}
		file = new File(file, "net.lecousin.framework.network.security.xml");
		synchronized (mapping.ipBlackList) {
			synchronized (mapping.bruteForceAttempt) {
				Config cfg = new Config(mapping);
				if (file.exists())
					if (!file.delete()) {
						System.err.println("Unable to remove previous file " + file.getAbsolutePath());
					}
				try (FileIO.WriteOnly output = new FileIO.WriteOnly(file, Task.PRIORITY_IMPORTANT)) {
					ISynchronizationPoint<Exception> ser =
						new XMLSerializer(null, "Security", null, StandardCharsets.UTF_8, 4096, true)
							.serialize(cfg, new TypeDefinition(Config.class), output, new ArrayList<>(0));
					ser.blockThrow(0);
					updated = false;
				} catch (Throwable t) {
					// TODO logger
					System.err.println("Error writing configuration file " + file.getAbsolutePath() + ": " + t.getMessage());
					t.printStackTrace(System.err);
					if (!file.delete())
						System.err.println("Unable to remove configuration file that may be corrupted: "
							+ file.getAbsolutePath());
				}
			}
		}
	}
	
	/** Return the instance for the current application. */
	public static NetworkSecurity get() {
		return LCCore.getApplication().getInstance(NetworkSecurity.class);
	}
	
	/** Return true if the given address is not black listed. */
	public static boolean acceptAddress(InetAddress address) {
		return get().mapping.ipBlackList.acceptAddress(address);
	}
	
	/** Add the given address to the black list for the given amount of milliseconds. */
	public static void blacklist(String category, InetAddress address, long delay) {
		get().mapping.ipBlackList.blacklist(category, address, delay);
	}
	
	/** Remove the given address to the black list. */
	public static void unblacklist(String category, InetAddress address) {
		get().mapping.ipBlackList.unblacklist(category, address);
	}
	
	/** Register a wrong password as a possible brute force attack. */
	public static void possibleBruteForceAttack(TCPRemote client, String application, String functionality, String value) {
		try {
			possibleBruteForceAttack(((InetSocketAddress)client.getRemoteAddress()).getAddress(), application, functionality, value);
		} catch (Throwable e) { /* ignore */ }
	}
	
	/** Register a wrong password as a possible brute force attack. */
	public static void possibleBruteForceAttack(InetAddress address, String application, String functionality, String value) {
		get().mapping.bruteForceAttempt.attempt(address, application, functionality, value);
	}
	
	private void cleaning() {
		mapping.ipBlackList.clean();
		mapping.bruteForceAttempt.clean();
	}
	
}
