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
import javax.net.ssl.SSLException;

import net.lecousin.framework.collections.TurnArray;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.network.SocketOptionValue;
import net.lecousin.framework.network.ssl.SSLLayer;
import net.lecousin.framework.util.Triple;

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
		
		@Override
		public void handshakeError(SSLException error) {
			@SuppressWarnings("unchecked")
			SynchronizationPoint<IOException> sp = (SynchronizationPoint<IOException>)getAttribute(CONNECT_ATTRIBUTE);
			sp.error(error);
		}
		
		@SuppressWarnings("unchecked")
		@Override
		public void dataReceived(LinkedList<ByteBuffer> data) {
			if (data.isEmpty()) return;
			AsyncWork<ByteBuffer, IOException> waiting;
			ByteBuffer buffer;
			Triple<AsyncWork<ByteBuffer, IOException>, Integer, Integer> waitAgain = null;
			synchronized (this) {
				LinkedList<Triple<AsyncWork<ByteBuffer, IOException>, Integer, Integer>> list =
					(LinkedList<Triple<AsyncWork<ByteBuffer, IOException>, Integer, Integer>>)
					getAttribute(WAITING_DATA_ATTRIBUTE);
				if (list == null) {
					receivedData.addAll(data);
					return;
				}
				waiting = list.removeFirst().getValue1();
				if (list.isEmpty())
					removeAttribute(WAITING_DATA_ATTRIBUTE);
				else
					waitAgain = list.getFirst();
				buffer = data.removeFirst();
				if (!data.isEmpty())
					receivedData.addAll(data);
			}
			if (waitAgain != null)
				waitForSSLData(waitAgain.getValue2().intValue(), waitAgain.getValue3().intValue());
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
		boolean firstWait = false;
		synchronized (sslClient) {
			if (!receivedData.isEmpty()) {
				result.unblockSuccess(receivedData.removeFirst());
				return result;
			}
			@SuppressWarnings("unchecked")
			LinkedList<Triple<AsyncWork<ByteBuffer, IOException>, Integer, Integer>> list =
				(LinkedList<Triple<AsyncWork<ByteBuffer, IOException>,Integer,Integer>>)getAttribute(WAITING_DATA_ATTRIBUTE);
			if (list == null) {
				list = new LinkedList<>();
				setAttribute(WAITING_DATA_ATTRIBUTE, list);
				firstWait = true;
			}
			list.add(new Triple<>(result, Integer.valueOf(expectedBytes), Integer.valueOf(timeout)));
		}
		if (result.isUnblocked())
			return result;
		if (firstWait)
			waitForSSLData(expectedBytes, timeout);
		return result;
	}
	
	private AsyncWork<ByteBuffer, IOException> lastReceive = null;
	
	private void waitForSSLData(int expectedBytes, int timeout) {
		AsyncWork<ByteBuffer, IOException> receive;
		synchronized (sslClient) {
			if (lastReceive != null && !lastReceive.isUnblocked())
				return;
			receive = super.receiveData(expectedBytes, timeout);
			lastReceive = receive;
		}
		receive.listenAsync(new Task.Cpu<Void, NoException>("Receive SSL data from server", Task.PRIORITY_NORMAL) {
			@SuppressWarnings("unchecked")
			@Override
			public Void run() {
				if (receive.hasError()) {
					LinkedList<Triple<AsyncWork<ByteBuffer, IOException>, Integer, Integer>> list;
					synchronized (sslClient) {
						list = (LinkedList<Triple<AsyncWork<ByteBuffer, IOException>, Integer, Integer>>)
							removeAttribute(WAITING_DATA_ATTRIBUTE);
					}
					if (list != null)
						for (Triple<AsyncWork<ByteBuffer, IOException>, Integer, Integer> t : list)
							t.getValue1().error(receive.getError());
					return null;
				}
				if (receive.isCancelled()) {
					LinkedList<Triple<AsyncWork<ByteBuffer, IOException>, Integer, Integer>> list;
					synchronized (sslClient) {
						list = (LinkedList<Triple<AsyncWork<ByteBuffer, IOException>, Integer, Integer>>)
							removeAttribute(WAITING_DATA_ATTRIBUTE);
					}
					if (list != null)
						for (Triple<AsyncWork<ByteBuffer, IOException>, Integer, Integer> t : list)
							t.getValue1().cancel(receive.getCancelEvent());
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
