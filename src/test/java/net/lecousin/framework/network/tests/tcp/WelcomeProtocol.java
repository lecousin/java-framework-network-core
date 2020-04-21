package net.lecousin.framework.network.tests.tcp;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.io.util.DataUtil;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.network.server.TCPServerClient;
import net.lecousin.framework.network.server.protocol.ALPNServerProtocol;

import org.junit.Assert;

public class WelcomeProtocol implements ALPNServerProtocol {

	@Override
	public String getALPNName() {
		return "welcome";
	}
	
	@Override
	public int startProtocol(TCPServerClient client) {
		client.send(ByteBuffer.wrap(new String("Welcome\n").getBytes(StandardCharsets.US_ASCII)), 5000);
		client.setAttribute("welcome", Boolean.TRUE);
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
		return 10000;
	}

	@Override
	public int getInputBufferSize(TCPServerClient client) {
		return 1024;
	}

	@Override
	public void dataReceivedFromClient(TCPServerClient client, ByteBuffer data) {
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
				client.removeAttribute("reading");
				if (s.startsWith("I'm ")) {
					client.send(ByteBuffer.wrap(("Hello " + s.substring(4) + '\n').getBytes(StandardCharsets.US_ASCII)), 5000);
					continue;
				}
				if (s.equals("flood me")) {
					@SuppressWarnings("unchecked")
					IAsync<IOException>[] sent = new IAsync[1000];
					for (int i = 0; i < 1000; ++i) {
						if (i > 100 && (i % 250) == 0) sent[i - 50].block(5000);
						byte[] buf = new byte[1024 * 1024];
						DataUtil.Write32.LE.write(buf, i, i);
						IAsync<IOException> send = client.send(ByteBuffer.wrap(buf), 5000);
						sent[i] = send;
						send.onError(e -> e.printStackTrace());
						if (i < 10 || i > 995 || (i % 100) == 0) {
							final int fi = i;
							send.onDone(() -> System.out.println("Sent: " + fi));
						}
					}
					timeout = 1200000;
					break;
				}
				if (s.equals("Bye")) {
					try {
						client.send(Arrays.asList(ByteBuffer.wrap("Bye".getBytes(StandardCharsets.US_ASCII))), 5000, true);
					} catch (ClosedChannelException e) {
						// ignore
					}
					return;
				}
				try {
					client.send(Arrays.asList(ByteBuffer.wrap("I don't understand you\n".getBytes(StandardCharsets.US_ASCII))), 5000, true);
				} catch (ClosedChannelException e) {
					// ignore
				}
				return;
			}
			msg.append((char)b);
		}
		ByteArrayCache.getInstance().free(data);
		try { client.waitForData(timeout); }
		catch (ClosedChannelException e) {}
	}

}