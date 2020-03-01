package net.lecousin.framework.network.test;

import javax.net.ssl.SSLContext;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.log.Logger.Level;
import net.lecousin.framework.log.LoggerFactory;
import net.lecousin.framework.network.NetworkManager;
import net.lecousin.framework.network.client.SSLClient;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.ssl.SSLContextConfig;
import net.lecousin.framework.network.ssl.SSLLayer;

import org.junit.BeforeClass;

public abstract class AbstractNetworkTest extends LCCoreAbstractTest {

	@BeforeClass
	public static void initNetwork() throws Exception {
		if (sslTest != null) return;
		// logging
		activateNetworkTraces();
		
		// SSL
		System.setProperty("com.sun.net.ssl.checkRevocation", "false");
		SSLContextConfig sslConfig = new SSLContextConfig();
		sslConfig.keyStore = new SSLContextConfig.Store("JKS", "classpath:tests-network-core/ssl/keystore.ssl.test", "password");
		sslConfig.trustStore = new SSLContextConfig.Store("JKS", "classpath:tests-network-core/ssl/truststore.ssl.test", "password");
		sslTest = SSLContextConfig.create(sslConfig);
	}
	
	public static SSLContext sslTest;
	
	public static void activateNetworkTraces() {
		LoggerFactory log = LCCore.getApplication().getLoggerFactory();
		log.getLogger(TCPClient.class).setLevel(Level.TRACE);
		log.getLogger(SSLClient.class).setLevel(Level.TRACE);
		log.getLogger(SSLLayer.class).setLevel(Level.TRACE);
		NetworkManager manager = NetworkManager.get();
		manager.getLogger().setLevel(Level.TRACE);
		manager.getDataLogger().setLevel(Level.TRACE);
		manager.setMaximumDataTraceSize(10000);
	}
	
	public static void deactivateNetworkTraces() {
		LoggerFactory log = LCCore.getApplication().getLoggerFactory();
		log.getLogger(TCPClient.class).setLevel(Level.INFO);
		log.getLogger(SSLClient.class).setLevel(Level.INFO);
		log.getLogger(SSLLayer.class).setLevel(Level.INFO);
		NetworkManager manager = NetworkManager.get();
		manager.getLogger().setLevel(Level.INFO);
		manager.getDataLogger().setLevel(Level.INFO);
	}
	
}
