package net.lecousin.framework.network.client;

import java.io.Closeable;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.HashMap;

import net.lecousin.framework.collections.TurnArray;
import net.lecousin.framework.concurrent.CancelException;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.concurrent.synch.AsyncWork.AsyncWorkListener;
import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.event.SimpleEvent;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.network.AttributesContainer;
import net.lecousin.framework.network.NetworkManager;
import net.lecousin.framework.network.SocketOptionValue;
import net.lecousin.framework.network.TCPRemote;
import net.lecousin.framework.util.DebugUtil;
import net.lecousin.framework.util.Pair;
import net.lecousin.framework.util.Provider;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * TCP client, connected to a server using TCP protocol (using sockets).
 * It uses the {@link NetworkManager} to make asynchronous operations.<br/>
 */
public class TCPClient implements AttributesContainer, Closeable, TCPRemote {
	
	public static final Log logger = LogFactory.getLog(TCPClient.class);

	/** Constcutor. */
	public TCPClient() {
		manager = NetworkManager.get();
	}
	
	protected NetworkManager manager;
	protected SocketChannel channel;
	protected boolean closed = true;
	protected SynchronizationPoint<IOException> spConnect;
	protected boolean endOfInput = false;
	private HashMap<String,Object> attributes = new HashMap<>(20);
	public static SimpleEvent onclosed = new SimpleEvent();
	private Provider<ByteBuffer> dataToSendProvider = null;
	private SynchronizationPoint<IOException> dataToSendSP = null;

	@Override
	public void setAttribute(String key, Object value) { attributes.put(key, value); }
	
	@Override
	public Object getAttribute(String key) { return attributes.get(key); }
	
	@Override
	public Object removeAttribute(String key) { return attributes.remove(key); }
	
	@Override
	public boolean hasAttribute(String name) { return attributes.containsKey(name); }
	
	@Override
	public SocketAddress getLocalAddress() throws IOException {
		return channel.getLocalAddress();
	}
	
	@Override
	public SocketAddress getRemoteAddress() throws IOException {
		return channel.getRemoteAddress();
	}
	
	@Override
	public void onclosed(Runnable listener) {
		onclosed.addListener(listener);
	}

	protected void channelClosed() {
		if (closed) return;
		if (logger.isDebugEnabled())
			logger.debug("Channel closed - client closed");
		closed = true;
		if (spConnect != null) spConnect.error(new ClosedChannelException());
		synchronized (toSend) {
			while (!toSend.isEmpty())
				toSend.removeFirst().getValue2().error(new ClosedChannelException());
		}
		networkClient.channelClosed();
		onclosed.fire();
	}
	
	private class NetworkClient implements NetworkManager.Client, NetworkManager.TCPReceiver {
		@Override
		public void channelClosed() {
			if (reading != null)
				reading.error(new ClosedChannelException());
			TCPClient.this.channelClosed();
		}
		
		@Override
		public void connected() {
			if (logger.isDebugEnabled())
				logger.debug("Client connected");
			spConnect.unblock();
			spConnect = null;
		}
		
		@Override
		public void connectionFailed(IOException error) {
			if (logger.isDebugEnabled())
				logger.debug("Client connection error", error);
			closed = true;
			spConnect.error(error);
			spConnect = null;
		}
		
		private AsyncWork<ByteBuffer, IOException> reading = null;
		private int expectedBytes = 4096;
		
		@Override
		public boolean received(ByteBuffer buffer) {
			if (logger.isDebugEnabled())
				logger.debug("Client received " + buffer.remaining() + " bytes");
			reading.unblockSuccess(buffer);
			return false;
		}
		
		@Override
		public void receiveError(IOException error, ByteBuffer buffer) {
			if (logger.isDebugEnabled())
				logger.debug("Client receive error", error);
			reading.unblockError(error);
		}
		
		@Override
		public ByteBuffer allocateReceiveBuffer() {
			return ByteBuffer.allocate(expectedBytes);
		}
		
		@Override
		public void endOfInput(ByteBuffer buffer) {
			if (logger.isDebugEnabled())
				logger.debug("Client end of input");
			buffer.flip();
			endOfInput = true;
			reading.unblockSuccess(buffer);
		}
	}
	
	protected NetworkClient networkClient = new NetworkClient();
	
	/** Connect this client to the server at the given address. */
	@SuppressWarnings("unchecked")
	public SynchronizationPoint<IOException> connect(SocketAddress address, int timeout, SocketOptionValue<?>... options) {
		if (logger.isDebugEnabled())
			logger.debug("Connecting to " + address.toString());
		if (spConnect != null)
			return new SynchronizationPoint<>(new IOException("Client already connecting"));
		spConnect = new SynchronizationPoint<>();
		SynchronizationPoint<IOException> result = spConnect;
		try {
			closed = false;
			endOfInput = false;
			channel = SocketChannel.open();
			channel.configureBlocking(false);
			for (@SuppressWarnings("rawtypes") SocketOptionValue option : options)
				channel.setOption(option.getOption(), option.getValue());
			channel.connect(address);
			manager.register(channel, SelectionKey.OP_CONNECT, networkClient, timeout);
			return result;
		} catch (IOException e) {
			if (logger.isDebugEnabled())
				logger.debug("Connection error", e);
			closed = true;
			spConnect.error(e);
			channel = null;
			spConnect = null;
			return result;
		}
	}
	
	/**
	 * Receive data from the server.
	 * @param expectedBytes buffer size that will be used to receive data
	 */
	public AsyncWork<ByteBuffer, IOException> receiveData(int expectedBytes, int timeout) {
		if (endOfInput)
			return new AsyncWork<>(null, null);
		if (logger.isDebugEnabled())
			logger.debug("Register to NetworkManager for reading data");
		networkClient.reading = new AsyncWork<>();
		networkClient.expectedBytes = expectedBytes;
		manager.register(channel, SelectionKey.OP_READ, networkClient, timeout);
		return networkClient.reading;
	}
	
	/** Utility class to bufferize received data. */
	public static class BufferedReceiver {
		/** Constructor. */
		public BufferedReceiver(TCPClient client) {
			this.client = client;
		}
		
		private TCPClient client;
		private ByteBuffer remainingRead = null;
		
		/** Receive some bytes from the server.<br/>
		 * If some bytes have been previously received but not yet consumed, those bytes are
		 * immediately returned. In that case, the returned buffer can be greater than the given buffer size.<br/>
		 * Else it registers this client as waiting for data, and returns data as soon as they are available.
		 */
		public AsyncWork<ByteBuffer, IOException> readAvailableBytes(int bufferSize, int timeout) {
			if (remainingRead != null) {
				AsyncWork<ByteBuffer,IOException> res = new AsyncWork<>(remainingRead, null);
				remainingRead = null;
				return res;
			}
			return client.receiveData(bufferSize, timeout);
		}
		
		/**
		 * Wait for the given number of bytes. If this number of bytes cannot be reached, an error is raised.
		 */
		public AsyncWork<byte[],IOException> readBytes(int nbBytes, int timeout) {
			AsyncWork<byte[],IOException> res = new AsyncWork<>();
			ByteBuffer buf = ByteBuffer.allocate(nbBytes);
			if (remainingRead != null) {
				buf.put(remainingRead);
				if (!remainingRead.hasRemaining())
					remainingRead = null;
				if (!buf.hasRemaining()) {
					res.unblockSuccess(buf.array());
					return res;
				}
			}
			client.receiveData(buf.remaining(), timeout).listenInline(new AsyncWorkListener<ByteBuffer, IOException>() {
				@Override
				public void ready(ByteBuffer result) {
					if (result == null) {
						res.unblockError(new IOException("End of data after " + buf.position()
							+ " bytes while waiting for " + nbBytes + " bytes"));
						return;
					}
					AsyncWorkListener<ByteBuffer, IOException> that = this;
					new Task.Cpu<Void, NoException>("Handle received data from TCPClient", Task.PRIORITY_RATHER_IMPORTANT) {
						@Override
						public Void run() {
							buf.put(result);
							if (result.hasRemaining())
								remainingRead = result;
							if (!buf.hasRemaining()) {
								res.unblockSuccess(buf.array());
								return null;
							}
							client.receiveData(buf.remaining(), timeout).listenInline(that);
							return null;
						}
					}.start();
				}
				
				@Override
				public void error(IOException error) {
					res.unblockError(error);
				}
				
				@Override
				public void cancelled(CancelException event) {
					res.unblockCancel(event);
				}
			});
			return res;
		}
		
		/**
		 * Receive data until the given byte is read. This can be useful for protocols that have an end of message marker,
		 * or protocols that send lines of text.
		 */
		@SuppressWarnings("resource")
		public AsyncWork<ByteArrayIO,IOException> readUntil(byte endMarker, int initialBufferSize, int timeout) {
			AsyncWork<ByteArrayIO,IOException> res = new AsyncWork<>();
			ByteArrayIO io = new ByteArrayIO(initialBufferSize, "");
			if (remainingRead != null) {
				do {
					byte b = remainingRead.get();
					if (b == endMarker) {
						io.seekSync(SeekType.FROM_BEGINNING, 0);
						res.unblockSuccess(io);
						if (!remainingRead.hasRemaining())
							remainingRead = null;
						return res;
					}
					io.write(b);
				} while (remainingRead.hasRemaining());
			}
			client.receiveData(initialBufferSize, timeout).listenInline(new AsyncWorkListener<ByteBuffer, IOException>() {
				@Override
				public void ready(ByteBuffer result) {
					if (result == null) {
						res.unblockError(new IOException("End of data after " + io.getPosition()
							+ " bytes read, while waiting for an end marker byte " + endMarker));
						return;
					}
					AsyncWorkListener<ByteBuffer, IOException> that = this;
					new Task.Cpu<Void, NoException>("TCPClient.readUntil", Task.PRIORITY_RATHER_IMPORTANT) {
						@Override
						public Void run() {
							while (result.hasRemaining()) {
								byte b = result.get();
								if (b == endMarker) {
									if (result.hasRemaining())
										remainingRead = result;
									io.seekSync(SeekType.FROM_BEGINNING, 0);
									res.unblockSuccess(io);
									return null;
								}
								io.write(b);
							}
							client.receiveData(initialBufferSize, timeout).listenInline(that);
							return null;
						}
					}.start();
				}
				
				@Override
				public void error(IOException error) {
					res.unblockError(error);
				}
				
				@Override
				public void cancelled(CancelException event) {
					res.unblockCancel(event);
				}
			});
			return res;
		}
	}
	
	private static final String BUFFERED_RECEIVER_ATTRIBUTE = "TCPClient.BufferedReceiver";
	
	/**
	 * Return a BufferedReceiver associated with this client.
	 * The BufferedReceiver is instantiated on the first call to this method.
	 * Once this method has been called, the method receiveData MUST NOT be used anymore,
	 * because some data may have been already receivedand bufferized into the BufferedReceiver.
	 */
	public BufferedReceiver getReceiver() {
		BufferedReceiver br = (BufferedReceiver)getAttribute(BUFFERED_RECEIVER_ATTRIBUTE);
		if (br == null) {
			br = new BufferedReceiver(this);
			setAttribute(BUFFERED_RECEIVER_ATTRIBUTE, br);
		}
		return br;
	}
	
	private TurnArray<Pair<ByteBuffer, SynchronizationPoint<IOException>>> toSend = new TurnArray<>();
	private NetworkManager.Sender sender = new NetworkManager.Sender() {
		@Override
		public void channelClosed() {
			TCPClient.this.channelClosed();
		}
		
		@Override
		public void sendTimeout() {
		}
		
		@Override
		public void readyToSend() {
			new Task.Cpu<Void, NoException>("Sending data to server", Task.PRIORITY_NORMAL) {
				@Override
				public Void run() {
					if (logger.isDebugEnabled())
						logger.debug("Socket ready for sending, sending...");
					synchronized (toSend) {
						do {
							Pair<ByteBuffer, SynchronizationPoint<IOException>> p;
							if (toSend.isEmpty()) {
								if (dataToSendProvider != null) {
									p = new Pair<>(dataToSendProvider.provide(), dataToSendSP);
									dataToSendProvider = null;
									dataToSendSP = null;
								} else
									break;
							} else
								p = toSend.getFirst();
							if (logger.isDebugEnabled())
								logger.debug("Sending up to " + p.getValue1().remaining() + " bytes to " + channel);
							if (p.getValue1().remaining() == 0) {
								if (p.getValue2() != null)
									p.getValue2().unblock();
								toSend.removeFirst();
								continue;
							}
							int nb;
							try {
								nb = channel.write(p.getValue1());
							} catch (IOException e) {
								// error while sending data, just skip it
								toSend.removeFirst();
								if (p.getValue2() != null)
									p.getValue2().error(e);
								continue;
							}
							if (logger.isDebugEnabled())
								logger.debug(nb + " bytes sent to " + channel);
							if (nb == 0) break; // cannot write anymore
							if (!p.getValue1().hasRemaining()) {
								if (p.getValue2() != null)
									p.getValue2().unblock();
								toSend.removeFirst();
							}
						} while (true);
						if (toSend.isEmpty()) {
							// no more data to send
							return null;
						}
					}
					// still something to write, we need to register to the network manager
					manager.register(channel, SelectionKey.OP_WRITE, sender, 0);
					return null;
				}
			}.start();
		}
	};

	/**
	 * Send data. Data are queued until they can be sent, so multiple calls to this method can be done
	 * without waiting for the previous message to be sent.
	 */
	@Override
	public ISynchronizationPoint<IOException> send(ByteBuffer data) {
		if (logger.isDebugEnabled())
			logger.debug("Sending data: " + data.remaining());
		if (logger.isTraceEnabled()) {
			if (data.hasArray()) {
				StringBuilder s = new StringBuilder(data.remaining() * 4);
				s.append("Data to send:\r\n");
				DebugUtil.dumpHex(s, data.array(), data.arrayOffset() + data.position(), data.remaining());
				logger.trace(s.toString());
			}
		}
		if (data.remaining() == 0)
			return new SynchronizationPoint<>(true);
		SynchronizationPoint<IOException> sp = new SynchronizationPoint<>();
		synchronized (toSend) {
			toSend.addLast(new Pair<>(data, sp));
			if (toSend.size() == 1 && dataToSendProvider == null)
				manager.register(channel, SelectionKey.OP_WRITE, sender, 0);
		}
		return sp;
	}
	
	@Override
	public void newDataToSendWhenPossible(Provider<ByteBuffer> dataProvider, SynchronizationPoint<IOException> sp) {
		synchronized (this) {
			Provider<ByteBuffer> prevProvider = dataToSendProvider;
			SynchronizationPoint<IOException> prevSP = dataToSendSP;
			dataToSendProvider = dataProvider;
			dataToSendSP = sp;
			if (toSend.isEmpty() && prevProvider == null)
				manager.register(channel, SelectionKey.OP_WRITE, sender, 0);
			if (prevProvider != null)
				prevSP.unblock();
		}
	}
	
	@Override
	public void close() {
		if (closed) return;
		if (logger.isDebugEnabled())
			logger.debug("Close client");
		try { channel.close(); }
		catch (Throwable e) { /* ignore */ }
		channelClosed();
		closed = true;
	}
	
	public boolean isClosed() {
		return closed;
	}
	
	@Override
	public String toString() {
		return "TCPClient [" + channel + "]";
	}
	
}
