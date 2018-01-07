package net.lecousin.framework.network.tests;

import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.serialization.TypeDefinition;
import net.lecousin.framework.io.serialization.annotations.TypeSerializer;
import net.lecousin.framework.network.NetUtil;
import net.lecousin.framework.xml.serialization.XMLDeserializer;
import net.lecousin.framework.xml.serialization.XMLSerializer;

import org.junit.Assert;
import org.junit.Test;

public class TestNetUtil extends LCCoreAbstractTest {

	@Test
	public void testMACFromString() {
		Assert.assertArrayEquals(
			new byte[] { 1, 2, 3, 4, 5, 6 },
			NetUtil.MACFromString("01:02:03:04:05:06")
		);
	}
	
	@Test
	public void testMACToString() {
		Assert.assertEquals(
			"01:02:03:04:05:06",
			NetUtil.MACToString(new byte[] { 1, 2, 3, 4, 5, 6 })
		);
	}
	
	@Test
	public void testIPv4FromString() {
		Assert.assertArrayEquals(
			new byte[] { 10, (byte)245, 0, 12 },
			NetUtil.IPv4FromString("10.245.0.12")
		);
	}
	
	@Test
	public void testIPv4ToString() {
		Assert.assertEquals(
			"10.245.0.12",
			NetUtil.IPv4ToString(new byte[] { 10, (byte)245, 0, 12 })
		);
	}
	
	@Test
	public void testIPv6FromString() {
		Assert.assertArrayEquals(
			new byte[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1 },
			NetUtil.IPv6FromString("::1")
		);
		Assert.assertArrayEquals(
			new byte[] { 0x20, 0x01, 0x0d, (byte)0xb8, 0, 0, 0, 0, 0, 0, (byte)0xFF, 0, 0, 0x42, (byte)0x83, 0x29 },
			NetUtil.IPv6FromString("2001:db8::ff00:42:8329")
		);
	}
	
	@Test
	public void testIPFromString() {
		Assert.assertArrayEquals(
			new byte[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0x23 },
			NetUtil.IPFromString("::123")
		);
		Assert.assertArrayEquals(
			new byte[] { 10, (byte)245, 0, 12 },
			NetUtil.IPFromString("10.245.0.12")
		);
	}
	
	@Test
	public void test() throws Exception {
		NetUtil.getAllIPs();
		Assert.assertNull(NetUtil.getIPsFromMAC(new byte[] { 1, 2, 3, 4, 5, 6 }));
		NetworkInterface i = NetworkInterface.getNetworkInterfaces().nextElement();
		NetUtil.getIPsFromMAC(i.getHardwareAddress());
	}
	
	@TypeSerializer(NetUtil.IPSerializer.class)
	public static class TestIPSerializer {
		public byte[] ip;
	}

	public static class TestIPSerializer2 {
		public String ip;
	}
	
	@Test
	public void testIPSerializerIPv4() throws Exception {
		TestIPSerializer t = new TestIPSerializer();
		t.ip = new byte[] { 1, 2, 3, 4 };
		ByteArrayIO io = new ByteArrayIO(4096, "test");
		io.lockClose();
		new XMLSerializer(null, "test", null).serialize(t, new TypeDefinition(TestIPSerializer.class), io, new ArrayList<>(0)).blockThrow(0);
		System.out.println(io.getAsString(StandardCharsets.UTF_8));
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		TestIPSerializer t2 = (TestIPSerializer)new XMLDeserializer(null, "test").deserialize(new TypeDefinition(TestIPSerializer.class), io, new ArrayList<>(0)).blockResult(0);
		Assert.assertArrayEquals(t.ip, t2.ip);
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		TestIPSerializer2 t3 = (TestIPSerializer2)new XMLDeserializer(null, "test").deserialize(new TypeDefinition(TestIPSerializer2.class), io, new ArrayList<>(0)).blockResult(0);
		Assert.assertEquals("1.2.3.4", t3.ip);
		io.unlockClose();
		io.close();
	}
	
	@Test
	public void testIPSerializerIPv6() throws Exception {
		TestIPSerializer t = new TestIPSerializer();
		t.ip = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 };
		ByteArrayIO io = new ByteArrayIO(4096, "test");
		io.lockClose();
		new XMLSerializer(null, "test", null).serialize(t, new TypeDefinition(TestIPSerializer.class), io, new ArrayList<>(0)).blockThrow(0);
		System.out.println(io.getAsString(StandardCharsets.UTF_8));
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		TestIPSerializer t2 = (TestIPSerializer)new XMLDeserializer(null, "test").deserialize(new TypeDefinition(TestIPSerializer.class), io, new ArrayList<>(0)).blockResult(0);
		Assert.assertArrayEquals(t.ip, t2.ip);
		io.unlockClose();
		io.close();
	}
	
}
