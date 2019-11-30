package net.lecousin.framework.network.test;

import java.io.File;

import javax.net.ssl.SSLContext;

import net.lecousin.framework.application.Application;
import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.io.FileIO;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IOUtil;
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
		log.getLogger("network-data").setLevel(Level.TRACE);
		log.getLogger("SSL").setLevel(Level.TRACE);
		log.getLogger(TCPClient.class).setLevel(Level.TRACE);
		log.getLogger(SSLClient.class).setLevel(Level.TRACE);
		
		// security
		File cfgDir = new File(LCCore.getApplication().getProperty(Application.PROPERTY_CONFIG_DIRECTORY));
		if (!cfgDir.exists()) cfgDir.mkdirs();
		File file = new File(cfgDir, "net.lecousin.framework.network.security.IPBlackList$Plugin.xml");
		IO.Readable input = LCCore.getApplication().getResource("tests-network-core/security/net.lecousin.framework.network.security.IPBlackList$Plugin.xml", Task.PRIORITY_NORMAL);
		FileIO.WriteOnly output = new FileIO.WriteOnly(file, Task.PRIORITY_NORMAL);
		IOUtil.copy(input, output, -1, true, null, 0).blockThrow(0);
		file = new File(cfgDir, "net.lecousin.framework.network.security.BruteForceAttempt$Plugin.xml");
		input = LCCore.getApplication().getResource("tests-network-core/security/net.lecousin.framework.network.security.BruteForceAttempt$Plugin.xml", Task.PRIORITY_NORMAL);
		output = new FileIO.WriteOnly(file, Task.PRIORITY_NORMAL);
		IOUtil.copy(input, output, -1, true, null, 0).blockThrow(0);
		
		// SSL
		System.setProperty("com.sun.net.ssl.checkRevocation", "false");
		SSLContextConfig sslConfig = new SSLContextConfig();
		sslConfig.keyStore = new SSLContextConfig.Store("JKS", "classpath:tests-network-core/ssl/keystore.ssl.test", "password");
		sslConfig.trustStore = new SSLContextConfig.Store("JKS", "classpath:tests-network-core/ssl/truststore.ssl.test", "password");
		sslTest = SSLContextConfig.create(sslConfig);
	}
	
	public static SSLContext sslTest;
	
}
