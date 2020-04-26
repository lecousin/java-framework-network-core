package net.lecousin.framework.network.server.protocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.network.server.TCPServerClient;
import net.lecousin.framework.network.ssl.SSLConnectionConfig;
import net.lecousin.framework.network.ssl.SSLLayer;

/**
 * SSL layer for a server protocol.
 */
public class SSLServerProtocol implements ServerProtocol {

	/** Constructor. */
	public SSLServerProtocol(SSLContext context, ServerProtocol protocol, ALPNServerProtocol... alpnProtocols) {
		SSLConnectionConfig sslConfig = new SSLConnectionConfig();
		sslConfig.setContext(context);
		this.protocol = protocol;
		if (alpnProtocols.length == 0 || !SSLConnectionConfig.ALPN_SUPPORTED) {
			this.protocols = null;
		} else {
			this.protocols = new HashMap<>(Math.max(3, Math.min(alpnProtocols.length, 10)));
			List<String> alpns = new LinkedList<>();
			for (ALPNServerProtocol p : alpnProtocols) {
				this.protocols.put(p.getALPNName(), p);
				alpns.add(p.getALPNName());
			}
			if (protocol instanceof ALPNServerProtocol) {
				ALPNServerProtocol p = (ALPNServerProtocol)protocol;
				this.protocols.put(p.getALPNName(), p);
				if (!alpns.contains(p.getALPNName()))
					alpns.add(p.getALPNName());
			}
			sslConfig.setApplicationProtocols(alpns);
		}
		this.ssl = new SSLLayer(sslConfig);
	}

	/** Constructor. */
	public SSLServerProtocol(ServerProtocol protocol) throws GeneralSecurityException {
		this(SSLContext.getDefault(), protocol);
	}

	private SSLLayer ssl;
	private ServerProtocol protocol;
	private Map<String, ALPNServerProtocol> protocols;
	
	@Override
	public String toString() {
		StringBuilder s = new StringBuilder();
		s.append("SSL: ");
		s.append(protocol.toString());
		if (protocols != null) {
			s.append(" (with ALPN protocols ");
			boolean first = true;
			for (String alpn : protocols.keySet()) {
				if (first)
					first = false;
				else
					s.append(", ");
				s.append(alpn);
			}
			s.append(')');
		}
		return s.toString();
	}
	
	public ServerProtocol getInnerProtocol() {
		return protocol;
	}
	
	private class Client implements SSLLayer.TCPConnection {
		
		private ServerProtocol selectedProtocol;
		
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
		public void handshakeDone(String alpn) {
			if (alpn == null || protocols == null)
				selectedProtocol = protocol;
			else {
				selectedProtocol = protocols.get(alpn);
				if (selectedProtocol == null) {
					ssl.getLogger().warn("Client selected protocol " + alpn + " which is not supported by our server");
					client.close();
					return;
				}
			}
			// start the next protocol
			int recvTimeout = selectedProtocol.startProtocol(client);
			if (recvTimeout >= 0)
				try { client.waitForData(recvTimeout); }
				catch (ClosedChannelException e) { client.closed(); }
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
				ByteArrayCache cache = ByteArrayCache.getInstance();
				buf = ByteBuffer.wrap(cache.get(total, true));
				for (ByteBuffer b : data) {
					buf.put(b);
					cache.free(b);
				}
				buf.flip();
			}
			selectedProtocol.dataReceivedFromClient(client, buf);
		}
		
		@Override
		public Async<IOException> sendEmpty(ByteBuffer data) throws ClosedChannelException {
			return client.send(Collections.singletonList(data), 10000, false);
		}
		
		@Override
		public String toString() {
			return client.toString();
		}
	}
	
	private static final String ATTRIBUTE_SSL_CLIENT = "SSLServerProtocol.client";
	
	@Override
	public int startProtocol(TCPServerClient client) {
		Client c = new Client(client);
		client.setAttribute(ATTRIBUTE_SSL_CLIENT, c);
		ssl.startConnection(c, false, 30000);
		return -1;
	}
	
	@Override
	public int getInputBufferSize(TCPServerClient client) {
		Client c = (Client)client.getAttribute(ATTRIBUTE_SSL_CLIENT);
		return c == null ? 16384 : ssl.getEncryptedBufferSize(c);
	}
	
	@Override
	public void dataReceivedFromClient(TCPServerClient client, ByteBuffer data) {
		Client c = (Client)client.getAttribute(ATTRIBUTE_SSL_CLIENT);
		ssl.dataReceived(c, data, 30000);
	}
	
	@Override
	public LinkedList<ByteBuffer> prepareDataToSend(TCPServerClient client, List<ByteBuffer> data) {
		Client c = (Client)client.getAttribute(ATTRIBUTE_SSL_CLIENT);
		try {
			return ssl.encryptDataToSend(c, data);
		} catch (SSLException e) {
			ssl.getLogger().error("Error encrypting SSL data to client", e);
			client.close();
			return new LinkedList<>();
		}
	}
	
}
