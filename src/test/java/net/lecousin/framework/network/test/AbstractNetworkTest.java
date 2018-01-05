package net.lecousin.framework.network.test;

import javax.net.ssl.SSLContext;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.log.Logger.Level;
import net.lecousin.framework.log.LoggerFactory;
import net.lecousin.framework.network.client.SSLClient;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.ssl.SSLContextConfig;

import org.junit.BeforeClass;

public abstract class AbstractNetworkTest extends LCCoreAbstractTest {

	@BeforeClass
	public static void initNetwork() throws Exception {
		if (sslTest != null) return;
		// logging
		LoggerFactory log = LCCore.getApplication().getLoggerFactory();
		log.getLogger("network").setLevel(Level.TRACE);
		log.getLogger("SSL").setLevel(Level.TRACE);
		log.getLogger(TCPClient.class).setLevel(Level.TRACE);
		log.getLogger(SSLClient.class).setLevel(Level.TRACE);
		// SSL
		System.setProperty("com.sun.net.ssl.checkRevocation", "false");
		SSLContextConfig sslConfig = new SSLContextConfig();
		sslConfig.keyStore = new SSLContextConfig.Store("JKS", "classpath:net/lecousin/framework/network/test/ssl/keystore.ssl.test", "password");
		sslConfig.trustStore = new SSLContextConfig.Store("JKS", "classpath:net/lecousin/framework/network/test/ssl/truststore.ssl.test", "password");
		sslTest = SSLContextConfig.create(LCCore.getApplication(), sslConfig);
	}
	
	public static SSLContext sslTest;
	
}
