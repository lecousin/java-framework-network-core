package net.lecousin.framework.network.tests.tcp;

import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.network.ssl.SSLContextConfig;
import net.lecousin.framework.network.ssl.SSLContextConfig.Store;

import org.junit.Test;

public class TestSSLConfig extends LCCoreAbstractTest {

	@Test
	public void test() throws Exception {
		new Store();
		SSLContextConfig.create(new SSLContextConfig());
	}
	
}
