package net.lecousin.framework.network.server.protocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.security.GeneralSecurityException;
import java.util.LinkedList;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.network.server.TCPServerClient;
import net.lecousin.framework.network.ssl.SSLLayer;

/**
 * SSL layer for a server protocol.
 */
public class SSLServerProtocol implements ServerProtocol {

	/** Constructor. */
	public SSLServerProtocol(SSLContext context, ServerProtocol protocol) {
		this.ssl = new SSLLayer(context);
		this.protocol = protocol;
	}

	/** Constructor. */
	public SSLServerProtocol(ServerProtocol protocol) throws GeneralSecurityException {
		this(SSLContext.getDefault(), protocol);
	}

	private SSLLayer ssl;
	private ServerProtocol protocol;
	
	public ServerProtocol getInnerProtocol() {
		return protocol;
	}
	
	private class Client implements SSLLayer.TCPConnection {
		
		public Client(TCPServerClient client) {
			this.client = client;
		}
		
		private TCPServerClient client;
		
		@Override
		public Object getAttribute(String name) {
			return client.getAttribute(name);
		}
		
		@Override
		public void setAttribute(String name, Object value) {
			client.setAttribute(name, value);
		}
		
		@Override
		public Object removeAttribute(String name) {
			return client.removeAttribute(name);
		}
		
		@Override
		public boolean hasAttribute(String name) {
			return client.hasAttribute(name);
		}
		
		@Override
		public boolean isClosed() {
			return client.isClosed();
		}
		
		@Override
		public void close() {
			client.close();
		}
		
		@Override
		public void closed() {
			client.closed();
		}
		
		@Override
		public void handshakeDone() {
			// start the next protocol
			protocol.startProtocol(client);
		}
		
		@Override
		public void handshakeError(SSLException error) {
			// nothing to do
		}
		
		@Override
		public void waitForData(int expectedBytes, int timeout) throws ClosedChannelException {
			client.waitForData(timeout);
		}
		
		@Override
		public void dataReceived(LinkedList<ByteBuffer> data) {
			if (data.isEmpty())
				return;
			ByteBuffer buf;
			if (data.size() == 1)
				buf = data.getFirst();
			else {
				// we need to concatenate it
				int total = 0;
				for (ByteBuffer b : data) total += b.remaining();
				if (total == 0) return;
				buf = ByteBuffer.allocate(total);
				for (ByteBuffer b : data)
					buf.put(b);
				buf.flip();
			}
			protocol.dataReceivedFromClient(client, buf, () -> { });
		}
		
		@Override
		public Async<IOException> sendEmpty(ByteBuffer data) throws ClosedChannelException {
			return client.send(data, false);
		}
		
		@Override
		public String toString() {
			return client.toString();
		}
	}
	
	private static final String ATTRIBUTE_SSL_CLIENT = "SSLServerProtocol.client";
	
	@Override
	public void startProtocol(TCPServerClient client) {
		Client c = new Client(client);
		client.setAttribute(ATTRIBUTE_SSL_CLIENT, c);
		ssl.startConnection(c, false, 30000);
	}
	
	@Override
	public int getInputBufferSize() {
		return 16384; // ask ssl engine??
	}
	
	@Override
	public void dataReceivedFromClient(TCPServerClient client, ByteBuffer data, Runnable onbufferavailable) {
		Client c = (Client)client.getAttribute(ATTRIBUTE_SSL_CLIENT);
		ssl.dataReceived(c, data, onbufferavailable, 30000);
	}
	
	@Override
	public LinkedList<ByteBuffer> prepareDataToSend(TCPServerClient client, ByteBuffer data) {
		Client c = (Client)client.getAttribute(ATTRIBUTE_SSL_CLIENT);
		try {
			return ssl.encryptDataToSend(c, data);
		} catch (SSLException e) {
			ssl.getLogger().error("Error encrypting SSL data to client", e);
			return null;
		}
	}
	
}
