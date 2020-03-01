package net.lecousin.framework.network.tests;

import java.net.NetworkInterface;

import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.network.NetUtil;

import org.junit.Assert;
import org.junit.Test;

public class TestNetUtil extends LCCoreAbstractTest {

	
	@Test
	public void test() throws Exception {
		NetUtil.getAllIPs();
		Assert.assertEquals(0, NetUtil.getIPsFromMAC(new byte[] { 1, 2, 3, 4, 5, 6 }).size());
		NetworkInterface i = NetworkInterface.getNetworkInterfaces().nextElement();
		NetUtil.getIPsFromMAC(i.getHardwareAddress());
	}
	
}
