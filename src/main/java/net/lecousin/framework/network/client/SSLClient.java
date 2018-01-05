package net.lecousin.framework.network.client;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import javax.net.ssl.SSLContext;

import net.lecousin.framework.collections.TurnArray;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.network.SocketOptionValue;
import net.lecousin.framework.network.ssl.SSLLayer;

/**
 * SSL client adding SSL layer to a TCPClient.
 */
public class SSLClient extends TCPClient {

	/** Constructor. */
	public SSLClient(SSLContext context) {
		ssl = new SSLLayer(context);
	}
	
	/** Constructor. */
	public SSLClient() throws GeneralSecurityException {
		this(SSLContext.getDefault());
	}
	
	private SSLLayer ssl;
	private TurnArray<ByteBuffer> receivedData = new TurnArray<>(5);

	private static final String CONNECT_ATTRIBUTE = "sslclient.connect";
	private static final String WAITING_DATA_ATTRIBUTE = "sslclient.waitfordata";
	
	/** Configure the SSL layer to accept the given hostnames. */
	public void setHostNames(String... hostNames) {
		ssl.setHostNames(Arrays.asList(hostNames));
	}

	/** Configure the SSL layer to accept the given hostnames. */
	public void setHostNames(List<String> hostNames) {
		ssl.setHostNames(hostNames);
	}
	
	private SSLLayer.TCPConnection sslClient = new SSLLayer.TCPConnection() {
		
		@Override
		public Object getAttribute(String name) {
			return SSLClient.this.getAttribute(name);
		}
		
		@Override
		public void setAttribute(String name, Object value) {
			SSLClient.this.setAttribute(name, value);
		}
		
		@Override
		public Object removeAttribute(String name) {
			return SSLClient.this.removeAttribute(name);
		}
		
		@Override
		public boolean hasAttribute(String name) {
			return SSLClient.this.hasAttribute(name);
		}
		
		@Override
		public boolean isClosed() {
			return SSLClient.this.isClosed();
		}
		
		@Override
		public void close() {
			SSLClient.this.close();
		}
		
		@Override
		public void closed() {
			@SuppressWarnings("unchecked")
			SynchronizationPoint<IOException> sp = (SynchronizationPoint<IOException>)getAttribute(CONNECT_ATTRIBUTE);
			if (sp != null && !sp.isUnblocked())
				sp.error(new ClosedChannelException());
			channelClosed();
		}
		
		@Override
		public void handshakeDone() {
			@SuppressWarnings("unchecked")
			SynchronizationPoint<IOException> sp = (SynchronizationPoint<IOException>)getAttribute(CONNECT_ATTRIBUTE);
			sp.unblock();
		}
		
		@SuppressWarnings("unchecked")
		@Override
		public void dataReceived(LinkedList<ByteBuffer> data) {
			if (data.isEmpty()) return;
			AsyncWork<ByteBuffer, IOException> waiting;
			ByteBuffer buffer;
			synchronized (this) {
				waiting = (AsyncWork<ByteBuffer, IOException>)removeAttribute(WAITING_DATA_ATTRIBUTE);
				if (waiting == null) {
					receivedData.addAll(data);
					return;
				}
				buffer = data.removeFirst();
				if (!data.isEmpty())
					receivedData.addAll(data);
			}
			waiting.unblockSuccess(buffer);
		}
		
		@Override
		public void waitForData() {
			waitForSSLData(8192, 60000); // TODO buffer size ? timeout ?
		}
		
		@Override
		public ISynchronizationPoint<IOException> sendEmpty(ByteBuffer data) {
			LinkedList<ByteBuffer> b = ssl.encryptDataToSend(this, data);
			if (b != null && !b.isEmpty()) {
				do {
					ByteBuffer buffer = b.removeFirst();
					ISynchronizationPoint<IOException> send = SSLClient.super.send(buffer);
					if (b.isEmpty())
						return send;
				} while (true);
			}
			return new SynchronizationPoint<>(new IOException("Error encrypting data"));
		}
		
		@Override
		public String toString() {
			return SSLClient.this.toString();
		}

	};
	
	@Override
	protected void channelClosed() {
		if (closed) return;
		super.channelClosed();
		sslClient.closed();
	}
	
	@Override
	public SynchronizationPoint<IOException> connect(SocketAddress address, int timeout, SocketOptionValue<?>... options) {
		SynchronizationPoint<IOException> result = new SynchronizationPoint<>();
		setAttribute(CONNECT_ATTRIBUTE, result);
		SynchronizationPoint<IOException> conn = super.connect(address, timeout, options);
		conn.listenAsync(new Task.Cpu<Void, NoException>("Start SSL protocol for TCPClient", Task.PRIORITY_NORMAL) {
			@Override
			public Void run() {
				if (conn.hasError()) {
					removeAttribute(CONNECT_ATTRIBUTE);
					result.error(conn.getError());
					return null;
				}
				if (conn.isCancelled()) {
					removeAttribute(CONNECT_ATTRIBUTE);
					result.cancel(conn.getCancelEvent());
					return null;
				}
				ssl.startConnection(sslClient, true);
				return null;
			}
		}, true);
		return result;
	}
	
	/**
	 * Instead of connecting directly, this method can be used when a tunnel has been established through
	 * another TCPClient. This SSLClient will use the SocketChannel of the tunnel, and start the SSL handshake.
	 * The given synchronization point is unblocked once the SSL handshake is done.
	 */
	public void tunnelConnected(TCPClient tunnel, SynchronizationPoint<IOException> connection) {
		setAttribute(CONNECT_ATTRIBUTE, connection);
		channel = tunnel.channel;
		closed = false;
		new Task.Cpu<Void, NoException>("Start SSL protocol for TCPClient through tunnel", Task.PRIORITY_NORMAL) {
			@Override
			public Void run() {
				ssl.startConnection(sslClient, true);
				return null;
			}
		}.start();
	}
	
	@Override
	public AsyncWork<ByteBuffer, IOException> receiveData(int expectedBytes, int timeout) {
		AsyncWork<ByteBuffer, IOException> result = new AsyncWork<>();
		synchronized (sslClient) {
			if (!receivedData.isEmpty()) {
				result.unblockSuccess(receivedData.removeFirst());
			} else
				setAttribute(WAITING_DATA_ATTRIBUTE, result);
		}
		if (result.isUnblocked())
			return result;
		waitForSSLData(expectedBytes, timeout);
		return result;
	}
	
	private void waitForSSLData(int expectedBytes, int timeout) {
		AsyncWork<ByteBuffer, IOException> receive = super.receiveData(expectedBytes, timeout);
		receive.listenAsync(new Task.Cpu<Void, NoException>("Receive SSL data from server", Task.PRIORITY_NORMAL) {
			@SuppressWarnings("unchecked")
			@Override
			public Void run() {
				if (receive.hasError()) {
					AsyncWork<ByteBuffer, IOException> waiting;
					synchronized (sslClient) {
						waiting = (AsyncWork<ByteBuffer, IOException>)removeAttribute(WAITING_DATA_ATTRIBUTE);
					}
					if (waiting != null)
						waiting.error(receive.getError());
					return null;
				}
				if (receive.isCancelled()) {
					AsyncWork<ByteBuffer, IOException> waiting;
					synchronized (sslClient) {
						waiting = (AsyncWork<ByteBuffer, IOException>)removeAttribute(WAITING_DATA_ATTRIBUTE);
					}
					if (waiting != null)
						waiting.cancel(receive.getCancelEvent());
					return null;
				}
				ByteBuffer b = receive.getResult();
				if (b == null) {
					close();
					return null;
				}
				ssl.dataReceived(sslClient, b, null);
				return null;
			}
		}, true);
	}
	
	private SynchronizationPoint<IOException> lastSend = null;
	
	@Override
	public SynchronizationPoint<IOException> send(ByteBuffer data) {
		SynchronizationPoint<IOException> result = new SynchronizationPoint<>();
		Task.Cpu<Void, NoException> task = new Task.Cpu<Void, NoException>("Encrypting SSL data", Task.PRIORITY_NORMAL) {
			@Override
			public Void run() {
				LinkedList<ByteBuffer> encrypted = ssl.encryptDataToSend(sslClient, data);
				if (encrypted == null) {
					result.error(new IOException("SSL error"));
					return null;
				}
				do {
					ByteBuffer b = encrypted.removeFirst();
					ISynchronizationPoint<IOException> send = SSLClient.super.send(b);
					if (encrypted.isEmpty())
						send.listenInline(result);
				} while (!encrypted.isEmpty());
				return null;
			}
		};
		SynchronizationPoint<IOException> previous = lastSend;
		lastSend = result;
		if (previous == null) task.start();
		else task.startOn(previous, false);
		return result;
	}
	
}
