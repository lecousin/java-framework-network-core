package net.lecousin.framework.network.tests.tcp;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.io.util.DataUtil;
import net.lecousin.framework.network.server.TCPServerClient;
import net.lecousin.framework.network.server.protocol.ServerProtocol;

import org.junit.Assert;

public class WelcomeProtocol implements ServerProtocol {

	@Override
	public void startProtocol(TCPServerClient client) {
		client.send(ByteBuffer.wrap(new String("Welcome\n").getBytes(StandardCharsets.US_ASCII)));
		client.setAttribute("welcome", Boolean.TRUE);
		try { client.waitForData(10000); }
		catch (ClosedChannelException e) {
			e.printStackTrace(System.err);
		}
		Closeable c = new Closeable() { @Override public void close() {} };
		client.addToClose(c);
		client.removeToClose(c);
		Async<Exception> sp = new Async<>();
		client.addPending(sp);
		sp.unblock();
		client.addPending(sp);
		try {
			client.getLocalAddress();
			client.getRemoteAddress();
			client.getClientAddress();
		} catch (Throwable t) {
			t.printStackTrace(System.err);
		}
	}

	@Override
	public int getInputBufferSize() {
		return 1024;
	}

	@Override
	public void dataReceivedFromClient(TCPServerClient client, ByteBuffer data, Runnable onbufferavailable) {
		System.out.println("Received from client: " + data.remaining());
		Assert.assertNotNull(client.getServer());
		int timeout = 10000;
		while (data.hasRemaining()) {
			StringBuilder msg;
			if (!client.hasAttribute("reading")) {
				msg = new StringBuilder();
				client.setAttribute("reading", msg);
			} else
				msg = (StringBuilder)client.getAttribute("reading");
			byte b = data.get();
			if (b == '\n') {
				String s = msg.toString();
				if (!s.startsWith("I'm ")) {
					if (!s.equals("flood me")) {
						client.send(ByteBuffer.wrap("I don't understand you\n".getBytes(StandardCharsets.US_ASCII)));
						client.close();
						break;
					}
					for (int i = 0; i < 1000; ++i) {
						byte[] buf = new byte[1024 * 1024];
						DataUtil.writeIntegerLittleEndian(buf, i, i);
						client.send(ByteBuffer.wrap(buf));
					}
					timeout = 1200000;
					break;
				}
				client.send(ByteBuffer.wrap(("Hello " + s.substring(4) + '\n').getBytes(StandardCharsets.US_ASCII)));
				client.removeAttribute("reading");
				continue;
			}
			msg.append((char)b);
		}
		onbufferavailable.run();
		try { client.waitForData(timeout); }
		catch (ClosedChannelException e) {}
	}

	@Override
	public LinkedList<ByteBuffer> prepareDataToSend(TCPServerClient client, ByteBuffer data) {
		LinkedList<ByteBuffer> list = new LinkedList<>();
		list.add(data);
		return list;
	}
	
}