package net.lecousin.framework.network.tests;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.encoding.EncodingException;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.network.IPAddressUtil;
import net.lecousin.framework.serialization.SerializationException;
import net.lecousin.framework.serialization.TypeDefinition;
import net.lecousin.framework.serialization.annotations.TypeSerializer;
import net.lecousin.framework.xml.serialization.XMLDeserializer;
import net.lecousin.framework.xml.serialization.XMLSerializer;

import org.junit.Assert;
import org.junit.Test;

public class TestIPAddressUtil extends LCCoreAbstractTest {
	@Test
	public void testIPv4FromString() throws EncodingException {
		Assert.assertArrayEquals(
			new byte[] { 10, (byte)245, 0, 12 },
			IPAddressUtil.fromStringV4("10.245.0.12")
		);
		Assert.assertArrayEquals(
			new byte[] { (byte)255, (byte)255, (byte)255, (byte)255 },
			IPAddressUtil.fromStringV4("255.255.255.255")
		);
		Assert.assertArrayEquals(
			new byte[] { 1, 2, 3, 4 },
			IPAddressUtil.fromStringV4("1.2.3.4")
		);
		
		try {
			IPAddressUtil.fromStringV4("1.2-3.4");
			throw new AssertionError();
		} catch (EncodingException e) {
			// ok
		}
		
		try {
			IPAddressUtil.fromStringV4("1.2.3");
			throw new AssertionError();
		} catch (EncodingException e) {
			// ok
		}
		
		try {
			IPAddressUtil.fromStringV4("1.2.3.4.");
			throw new AssertionError();
		} catch (EncodingException e) {
			// ok
		}
		
		try {
			IPAddressUtil.fromStringV4("1.2345.1.2");
			throw new AssertionError();
		} catch (EncodingException e) {
			// ok
		}
		
		try {
			IPAddressUtil.fromStringV4("1.256.3.4");
			throw new AssertionError();
		} catch (EncodingException e) {
			// ok
		}
		
		try {
			IPAddressUtil.fromStringV4("1.2..3.4");
			throw new AssertionError();
		} catch (EncodingException e) {
			// ok
		}
		
		try {
			IPAddressUtil.fromStringV4("255.255.255.255.255");
			throw new AssertionError();
		} catch (EncodingException e) {
			// ok
		}
	}
	
	@Test
	public void testIPv4ToString() {
		Assert.assertEquals(
			"10.245.0.12",
			IPAddressUtil.toStringV4(new byte[] { 10, (byte)245, 0, 12 })
		);
	}
	
	@Test
	public void testIPv6FromString() throws EncodingException {
		Assert.assertArrayEquals(
			new byte[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1 },
			IPAddressUtil.fromStringV6("::1")
		);
		Assert.assertArrayEquals(
			new byte[] { 0x20, 0x01, 0x0d, (byte)0xb8, 0, 0, 0, 0, 0, 0, (byte)0xFF, 0, 0, 0x42, (byte)0x83, 0x29 },
			IPAddressUtil.fromStringV6("2001:db8::ff00:42:8329")
		);
		Assert.assertArrayEquals(
			new byte[] { 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 },
			IPAddressUtil.fromStringV6("1::")
		);
		Assert.assertArrayEquals(
			new byte[] { 0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0, 7, 0, 8 },
			IPAddressUtil.fromStringV6("0001:0002:0003:0004:0005:0006:0007:0008")
		);
		Assert.assertArrayEquals(
			new byte[] { 0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0, 7, 0, 8 },
			IPAddressUtil.fromStringV6("1:2:3:4:5:6:7:8")
		);
		
		try {
			IPAddressUtil.fromStringV6("1234::5678::9ABC");
			throw new AssertionError();
		} catch (EncodingException e) {
			// ok
		}
		
		try {
			IPAddressUtil.fromStringV6("1:2:3:4:5:6:7");
			throw new AssertionError();
		} catch (EncodingException e) {
			// ok
		}
		
		try {
			IPAddressUtil.fromStringV6("1:2:3:4:5.6:7:8");
			throw new AssertionError();
		} catch (EncodingException e) {
			// ok
		}
		
		try {
			IPAddressUtil.fromStringV6("1:2:3:G:5:6:7:8");
			throw new AssertionError();
		} catch (EncodingException e) {
			// ok
		}
		
		try {
			IPAddressUtil.fromStringV6("1:2:3:4:5:6:7:");
			throw new AssertionError();
		} catch (EncodingException e) {
			// ok
		}
		
		try {
			IPAddressUtil.fromStringV6(":1:2:3:4:5:6:7:8");
			throw new AssertionError();
		} catch (EncodingException e) {
			// ok
		}
		
		try {
			IPAddressUtil.fromStringV6("1234:5678");
			throw new AssertionError();
		} catch (EncodingException e) {
			// ok
		}
		
		try {
			IPAddressUtil.fromStringV6("1234:5678:9ABC:DEF0:1234:5678:9ABC:DEF0:1234");
			throw new AssertionError();
		} catch (EncodingException e) {
			// ok
		}
		
		try {
			IPAddressUtil.fromStringV6("1234:5678:9ABC.DEF0:1234:5678:9ABC:DEF0:1234");
			throw new AssertionError();
		} catch (EncodingException e) {
			// ok
		}
		
		try {
			IPAddressUtil.fromStringV6("1234:5678::9ABC.DEF0:1234");
			throw new AssertionError();
		} catch (EncodingException e) {
			// ok
		}
		
		try {
			IPAddressUtil.fromStringV6("::1234:5678:9ABC:DEF0:1234:5678:9ABC:DEF0:1234");
			throw new AssertionError();
		} catch (EncodingException e) {
			// ok
		}
		
		try {
			IPAddressUtil.fromStringV6("::1234:5678:9ABC:DEF0:1234:5678:9ABC:DEF0:1234:5678");
			throw new AssertionError();
		} catch (EncodingException e) {
			// ok
		}
	}
	
	@Test
	public void testIPFromString() throws EncodingException {
		Assert.assertArrayEquals(
			new byte[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0x23 },
			IPAddressUtil.fromString("::1:123")
		);
		Assert.assertArrayEquals(
			new byte[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1 },
			IPAddressUtil.fromString("::1")
		);
		Assert.assertArrayEquals(
			new byte[] { 0, 10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0x23 },
			IPAddressUtil.fromString("A::1:123")
		);
		Assert.assertArrayEquals(
			new byte[] { 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1, 0, 1 },
			IPAddressUtil.fromString("1::1:1:1")
		);
		Assert.assertArrayEquals(
			new byte[] { 0, 18, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1, 0, 1 },
			IPAddressUtil.fromString("12::1:1:1")
		);
		Assert.assertArrayEquals(
			new byte[] { 1, 18, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1, 0, 1 },
			IPAddressUtil.fromString("112::1:1:1")
		);
		Assert.assertArrayEquals(
			new byte[] { 10, (byte)245, 0, 12 },
			IPAddressUtil.fromString("10.245.0.12")
		);
		Assert.assertArrayEquals(
			new byte[] { 1, (byte)245, 0, 12 },
			IPAddressUtil.fromString("1.245.0.12")
		);
		Assert.assertArrayEquals(
			new byte[] { 100, (byte)245, 0, 12 },
			IPAddressUtil.fromString("100.245.0.12")
		);
	}
	@TypeSerializer(IPAddressUtil.IPSerializer.class)
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
	
	@Test
	public void testIPSerializerNull() throws Exception {
		TestIPSerializer t = new TestIPSerializer();
		t.ip = null;
		ByteArrayIO io = new ByteArrayIO(4096, "test");
		io.lockClose();
		new XMLSerializer(null, "test", null).serialize(t, new TypeDefinition(TestIPSerializer.class), io, new ArrayList<>(0)).blockThrow(0);
		System.out.println(io.getAsString(StandardCharsets.UTF_8));
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		TestIPSerializer t2 = (TestIPSerializer)new XMLDeserializer(null, "test").deserialize(new TypeDefinition(TestIPSerializer.class), io, new ArrayList<>(0)).blockResult(0);
		Assert.assertNull(t2.ip);
		io.unlockClose();
		io.close();
	}
	
	@Test
	public void testIPSerializerInvalidIP() throws Exception {
		TestIPSerializer t = new TestIPSerializer();
		t.ip = new byte[2];
		ByteArrayIO io = new ByteArrayIO(4096, "test");
		io.lockClose();
		new XMLSerializer(null, "test", null).serialize(t, new TypeDefinition(TestIPSerializer.class), io, new ArrayList<>(0)).blockThrow(0);
		System.out.println(io.getAsString(StandardCharsets.UTF_8));
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		TestIPSerializer t2 = (TestIPSerializer)new XMLDeserializer(null, "test").deserialize(new TypeDefinition(TestIPSerializer.class), io, new ArrayList<>(0)).blockResult(0);
		Assert.assertNull(t2.ip);
		io.unlockClose();
		io.close();
	}
	
	@Test
	public void testDeserializeInvalidIP() throws Exception {
		String xml = "<?xml version=\"1.1\" encoding=\"UTF-8\"?><test xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ip=\"1.256.3.4\"/>";
		try {
			new XMLDeserializer(null, "test").deserialize(new TypeDefinition(TestIPSerializer.class), new ByteArrayIO(xml.getBytes(StandardCharsets.UTF_8), "test"), new ArrayList<>(0)).blockResult(0);
			throw new AssertionError();
		} catch (SerializationException e) {
			// ok
		}
	}
	
	@Test
	public void testDeserializeEmptyIP() throws Exception {
		String xml = "<?xml version=\"1.1\" encoding=\"UTF-8\"?><test xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ip=\"\"/>";
		TestIPSerializer t2 = (TestIPSerializer)new XMLDeserializer(null, "test").deserialize(new TypeDefinition(TestIPSerializer.class), new ByteArrayIO(xml.getBytes(StandardCharsets.UTF_8), "test"), new ArrayList<>(0)).blockResult(0);
		Assert.assertNull(t2.ip);
	}
}
