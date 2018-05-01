package net.lecousin.framework.network;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.NetworkChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

import net.lecousin.framework.application.Application;
import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.collections.TurnArray;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.network.security.NetworkSecurity;
import net.lecousin.framework.util.DebugUtil;

/**
 * The NetworkManager launches a thread to listen to network events using the Java NIO framework,
 * making its usage easier.<br/>
 * The method {@link #register(SelectableChannel, int, Listener)} is used to add a listener to a channel.<br/>
 * The listeners are called in separate CPU tasks to avoid slowing down the NetworkManager's thread.
 */
public class NetworkManager implements Closeable {

	/**
	 * Base interface for a listener, with a channelClosed event.
	 */
	public static interface Listener {
		/** Called when the associated channel has been closed. */
		public void channelClosed();
	}

	/**
	 * Interface for a server listener, with newClient and acceptError events.
	 */
	public static interface Server extends Listener {
		/** Called when a new client is connected. */
		public void newClient(SocketChannel client);
		
		/** Called when an error occurs while accepting clients. */
		public void acceptError(IOException error);
	}

	/**
	 * Interface for a client listener, with connected and connectionFailed events.
	 */
	public static interface Client extends Listener {
		/** Called once the client has been connected to the server. */
		public void connected();
		
		/** Called when an error occurs while connecting to a server. */
		public void connectionFailed(IOException error);
	}
	
	/**
	 * Interface for a receiver listener, with events related to receiving data from the network.
	 */
	public static interface Receiver extends Listener {
		/** Ask to allocate a buffer to receive data.
		 * This allows to allocate an amount of bytes according to what is expected.
		 * Because it is directly called by the NetworkManager's thread, it must be as fast as possible.
		 */
		public ByteBuffer allocateReceiveBuffer();
		
		/** Called when an error occus while receiving data. The buffer may be null in case of timeout. */
		public void receiveError(IOException error, ByteBuffer buffer);
	}
	
	/** TCP receiver. */
	public static interface TCPReceiver extends Receiver {
		/** Called when some data has been received. */
		public void received(ByteBuffer buffer);
		
		/** Called when no more data can be received. */
		public void endOfInput(ByteBuffer buffer);
	}
	
	/** UDP receiver. */
	public static interface UDPReceiver extends Receiver {
		/** Called when data has been received. */
		public void received(ByteBuffer buffer, SocketAddress source);
	}

	/**
	 * Interface for a sender listener, with an event readyToSend.
	 */
	public static interface Sender extends Listener {
		/** Called when the associated network channel is ready to send data over the network. */
		public void readyToSend();
		
		/** Called if a timeout was specified when registering, and the channel is not yet ready to send
		 * data after the specified timeout.
		 */
		public void sendTimeout();
	}
	
	private NetworkManager(Application app) {
		logger = app.getLoggerFactory().getLogger("network");
		dataLogger = app.getLoggerFactory().getLogger("network-data");
		app.toClose(this);
		try {
			selector = Selector.open();
		} catch (IOException e) {
			throw new RuntimeException("Unable to start Network Manager", e);
		}
		worker = app.getThreadFactory().newThread(new WorkerLoop());
		worker.setName("Network Manager");
		worker.start();
	}
	
	/** Get the instance for the current application. */
	public static NetworkManager get() {
		Application app = LCCore.getApplication();
		return get(app);
	}

	/** Get the instance for the given application. */
	public static synchronized NetworkManager get(Application app) {
		NetworkManager nm = app.getInstance(NetworkManager.class);
		if (nm == null) {
			nm = new NetworkManager(app);
			app.setInstance(NetworkManager.class, nm);
		}
		return nm;
	}
	
	private Logger logger;
	private Logger dataLogger;
	private Selector selector;
	private Thread worker;
	private boolean stop = false;
	private TurnArray<RegisterRequest> requests = new TurnArray<>(30);
	
	public Logger getLogger() {
		return logger;
	}
	
	public Logger getDataLogger() {
		return dataLogger;
	}
	
	private static class RegisterRequest {
		private SelectableChannel channel;
		private int newOps;
		private Listener listener;
		private int timeout;
		private AsyncWork<SelectionKey, IOException> result;
	}
	
	private static class Attachment {
		private Server onAccept;
		private Client onConnect;
		private long connectStart;
		private int onConnectTimeout;
		private Receiver onRead;
		private long readStart;
		private int onReadTimeout;
		private Sender onWrite;
		private long writeStart;
		private int onWriteTimeout;
		
		public void set(int ops, Listener listener, int timeout) {
			if ((ops & SelectionKey.OP_ACCEPT) != 0)
				onAccept = (Server)listener;
			if ((ops & SelectionKey.OP_CONNECT) != 0) {
				onConnect = (Client)listener;
				onConnectTimeout = timeout;
				if (timeout > 0)
					connectStart = System.currentTimeMillis();
			}
			if ((ops & SelectionKey.OP_READ) != 0) {
				onRead = (Receiver)listener;
				onReadTimeout = timeout;
				if (timeout > 0)
					readStart = System.currentTimeMillis();
			}
			if ((ops & SelectionKey.OP_WRITE) != 0) {
				onWrite = (Sender)listener;
				onWriteTimeout = timeout;
				if (timeout > 0)
					writeStart = System.currentTimeMillis();
			}
		}
		
		public void reset() {
			onAccept = null;
			onConnect =  null;
			onConnectTimeout = 0;
			onRead = null;
			onReadTimeout = 0;
			onWrite = null;
			onWriteTimeout = 0;
		}
		
		public void channelClosed() {
			if (onAccept != null) NetworkManager.channelClosed(onAccept);
			if (onConnect != null) NetworkManager.channelClosed(onConnect);
			if (onRead != null) NetworkManager.channelClosed(onRead);
			if (onWrite != null) NetworkManager.channelClosed(onWrite);
		}
	}
	
	@Override
	public void close() {
		stop = true;
		selector.wakeup();
	}
	
	/**
	 * Register a listener to listen to the given operations on the given channel.
	 * @param channel the channel to listen
	 * @param ops operations (see {@link SelectionKey}
	 * @param listener listener that must implement the interfaces according to the requested operations
	 */
	public <T extends SelectableChannel & NetworkChannel> AsyncWork<SelectionKey, IOException> register(
		T channel, int ops, Listener listener, int timeout
	) {
		if ((ops & SelectionKey.OP_ACCEPT) != 0)
			if (!(listener instanceof Server)) {
				logger.error("Invalid listener for ACCEPT: " + listener.getClass().getName(), new Exception());
				return new AsyncWork<>(null, new IOException("Invalid listener"));
			}
		if ((ops & SelectionKey.OP_CONNECT) != 0)
			if (!(listener instanceof Client)) {
				logger.error("Invalid listener for CONNECT: " + listener.getClass().getName(), new Exception());
				return new AsyncWork<>(null, new IOException("Invalid listener"));
			}
		if ((ops & SelectionKey.OP_READ) != 0)
			if (!(listener instanceof Receiver)) {
				logger.error("Invalid listener for READ: " + listener.getClass().getName(), new Exception());
				return new AsyncWork<>(null, new IOException("Invalid listener"));
			}
		if ((ops & SelectionKey.OP_WRITE) != 0)
			if (!(listener instanceof Sender)) {
				logger.error("Invalid listener for WRITE: " + listener.getClass().getName(), new Exception());
				return new AsyncWork<>(null, new IOException("Invalid listener"));
			}
		RegisterRequest req = new RegisterRequest();
		req.channel = channel;
		req.newOps = ops;
		req.listener = listener;
		req.timeout = timeout;
		req.result = new AsyncWork<>();
		if (logger.trace())
			logger.trace("Registering channel " + channel + " for operations " + ops + " with timeout " + timeout);
		synchronized (requests) {
			requests.addLast(req);
		}
		selector.wakeup();
		return req.result;
	}
	
	private class WorkerLoop implements Runnable {

		private long workingTime = 0;
		private long waitingTime = 0;
		
		@Override
		public void run() {
			long start = System.nanoTime();
			NetworkSecurity.init();
			int loopCount = 0;
			while (!stop) {
				processRegisterRequests();
				if (stop) break;
				Set<SelectionKey> keys = selector.selectedKeys();
				if (!keys.isEmpty()) {
					if (logger.trace()) logger.trace(keys.size() + " network channels are ready for operations");
					loopCount++;
					for (Iterator<SelectionKey> it = keys.iterator(); it.hasNext(); ) {
						SelectionKey key = it.next();
						it.remove();
						Attachment listeners = (Attachment)key.attachment();
						if (!key.isValid()) {
							listeners.channelClosed();
							continue;
						}
						int ops = key.readyOps();
						if (logger.trace()) logger.trace("Ready operation: " + ops + " for " + key.toString());
						if ((ops & SelectionKey.OP_ACCEPT) != 0) {
							Server server = listeners.onAccept;
							try {
								@SuppressWarnings("resource")
								SocketChannel client = ((ServerSocketChannel)key.channel()).accept();
								acceptClient(server, client);
							} catch (Throwable e) {
								acceptError(server, e);
							}
						}
						if ((ops & SelectionKey.OP_CONNECT) != 0) {
							// a socket is connected
							Client client = listeners.onConnect;
							if (logger.trace())
								logger.trace("A socket is ready to be connected: " + key.channel().toString());
							try {
								((SocketChannel)key.channel()).finishConnect();
								key.interestOps(0);
								listeners.reset();
								connected(client);
							} catch (Throwable e) {
								if (logger.info())
									logger.info("Connection failed: " + key.channel().toString());
								key.cancel();
								connectionFailed(client, e);
								continue;
							}
						}
						if ((ops & SelectionKey.OP_READ) != 0) {
							// data received
							Receiver receiver = listeners.onRead;
							ByteBuffer buffer = null;
							try {
								buffer = receiver.allocateReceiveBuffer();
								if (receiver instanceof TCPReceiver) {
									TCPReceiver tcp = (TCPReceiver)receiver;
									int nb = ((ReadableByteChannel)key.channel()).read(buffer);
									if (nb < 0) {
										if (logger.trace())
											logger.trace("End of stream reached for socket: "
												+ key.channel().toString());
										key.interestOps(0);
										listeners.reset();
										endOfInput(tcp, buffer);
									} else {
										dataReceived(tcp, buffer, nb, key.channel());
										try {
											int iops = key.interestOps();
											key.interestOps(iops - (iops & SelectionKey.OP_READ));
										} catch (CancelledKeyException e) {
											// ignore
										}
										listeners.onRead = null;
										listeners.onReadTimeout = 0;
									}
								} else {
									UDPReceiver udp = (UDPReceiver)receiver;
									SocketAddress source = ((DatagramChannel)key.channel()).receive(buffer);
									dataReceived(udp, buffer, source);
									try {
										int iops = key.interestOps();
										key.interestOps(iops - (iops & SelectionKey.OP_READ));
										listeners.onRead = null;
										listeners.onReadTimeout = 0;
									} catch (CancelledKeyException e) {
										/* ignore */
									}
								}
							} catch (Throwable e) {
								if (logger.error())
									logger.error("Error while receiving data from network on "
										+ key.channel().toString(), e);
								receiveError(receiver, e, buffer);
								try { key.interestOps(0); }
								catch (CancelledKeyException e2) { /* ignore */ }
								catch (Throwable t) { logger.error("Error", t); }
								listeners.reset();
								try { key.channel().close(); }
								catch (Throwable t) { /* ignore */ }
							}
						}
						if ((ops & SelectionKey.OP_WRITE) != 0) {
							Sender sender = listeners.onWrite;
							try {
								int iops = key.interestOps();
								key.interestOps(iops - (iops & SelectionKey.OP_WRITE));
								listeners.onWrite = null;
								listeners.onWriteTimeout = 0;
								if (logger.trace())
									logger.trace("Socket ready to send data on " + key.channel());
								readyToSend(sender);
							} catch (CancelledKeyException e) {
								channelClosed(sender);
							} catch (Throwable t) {
								if (logger.error())
									logger.error("Error with channel ready to send", t);
							}
						}
					}
					if (++loopCount > 1000) {
						checkTimeouts();
						loopCount = 0;
					}
					continue;
				}
				loopCount = 0;
				long nextTimeout = checkTimeouts();
				synchronized (requests) {
					if (!requests.isEmpty()) continue;
				}
				if (logger.trace()) logger.trace("NetworkManager is waiting for operations");
				long now = System.nanoTime();
				workingTime += now - start;
				try {
					if (nextTimeout > 0) {
						long ms = nextTimeout - System.currentTimeMillis();
						if (ms <= 0) ms = 1;
						selector.select(ms);
					} else
						selector.select();
				} catch (IOException e) {
					if (logger.error()) logger.error("Error selecting channels", e);
					break;
				} catch (ClosedSelectorException e) {
					break; // closing
				}
				start = System.nanoTime();
				waitingTime += start - now;
			}
			try { selector.close(); }
			catch (Throwable e) { /* ignore */ }
			if (logger.info())
				logger.info("Network Manager closed, worked during "
					+ String.format("%.5f", new Double(workingTime * 1.d / 1000000000))
					+ "s., waited " + String.format("%.5f", new Double(waitingTime * 1.d / 1000000000)) + "s.");
		}
	}
	
	private void processRegisterRequests() {
		do {
			RegisterRequest req;
			synchronized (requests) {
				req = requests.pollFirst();
			}
			if (req == null) break;
			try {
				SelectionKey key = req.channel.keyFor(selector);
				if (key == null) {
					Attachment listeners = new Attachment();
					listeners.set(req.newOps, req.listener, req.timeout);
					key = req.channel.register(selector, req.newOps, listeners);
					req.result.unblockSuccess(key);
					continue;
				}
				Attachment listeners = (Attachment)key.attachment();
				try {
					int curOps = key.interestOps();
					int conflict = curOps & req.newOps;
					if (conflict == 0) {
						key.interestOps(curOps | req.newOps);
						listeners.set(req.newOps, req.listener, req.timeout);
						req.result.unblockSuccess(key);
						continue;
					}
					req.result.unblockError(new IOException("Operation already registered"));
					try {
						if ((conflict & SelectionKey.OP_ACCEPT) != 0)
							acceptError((Server)req.listener, new IOException("Already registered for accept operation"));
						if ((conflict & SelectionKey.OP_CONNECT) != 0)
							connectionFailed((Client)req.listener,
								new IOException("Already registered for connect operation"));
						if ((conflict & SelectionKey.OP_READ) != 0)
							receiveError((Receiver)req.listener,
								new IOException("Already registered for read operation"), null);
						if ((conflict & SelectionKey.OP_WRITE) != 0)
							logger.error("Already registered for write operation",
								new IOException("Already registered for write operation"));
					} catch (Throwable t) {
						logger.error("Error calling listener", t);
					}
				} catch (CancelledKeyException e) {
					listeners.channelClosed();
					req.result.error(IO.error(e));
				}
			} catch (ClosedChannelException e) {
				if (req.listener != null)
					channelClosed(req.listener);
				req.result.error(e);
			}
		} while (!stop);
	}
	
	private long checkTimeouts() {
		long now = System.currentTimeMillis();
		long nextTimeout = 0;
		for (SelectionKey key : selector.keys()) {
			if (!key.isValid()) continue;
			Attachment listeners = (Attachment)key.attachment();
			if (listeners.onConnect != null && listeners.onConnectTimeout > 0) {
				if (now > listeners.connectStart + listeners.onConnectTimeout) {
					connectionFailed(listeners.onConnect,
						new IOException("Connection timeout after " + listeners.onConnectTimeout + "ms."));
					key.cancel();
					continue;
				}
				if (listeners.connectStart + listeners.onConnectTimeout > nextTimeout)
					nextTimeout = listeners.connectStart + listeners.onConnectTimeout;
			}
			if (listeners.onRead != null && listeners.onReadTimeout > 0) {
				if (now > listeners.readStart + listeners.onReadTimeout) {
					receiveError(listeners.onRead,
						new IOException("Network read timeout after " + listeners.onReadTimeout + "ms."), null);
					try { key.interestOps(0); }
					catch (CancelledKeyException e2) { /* ignore */ }
					catch (Throwable t) { logger.error("Error", t); }
					listeners.reset();
					try { key.channel().close(); }
					catch (Throwable t) { /* ignore */ }
					continue;
				}
				if (listeners.readStart + listeners.onReadTimeout > nextTimeout)
					nextTimeout = listeners.readStart + listeners.onReadTimeout;
			}
			if (listeners.onWrite != null && listeners.onWriteTimeout > 0) {
				if (now > listeners.writeStart + listeners.onWriteTimeout) {
					int iops = key.interestOps();
					key.interestOps(iops - (iops & SelectionKey.OP_WRITE));
					Sender sender = listeners.onWrite;
					listeners.onWrite = null;
					listeners.onWriteTimeout = 0;
					sendTimeout(sender);
					continue;
				}
				if (listeners.writeStart + listeners.onWriteTimeout > nextTimeout)
					nextTimeout = listeners.writeStart + listeners.onWriteTimeout;
			}
		}
		return nextTimeout;
	}
	
	private void acceptClient(Server server, SocketChannel client) {
		new Task.Cpu<Void, NoException>("Accept network client", Task.PRIORITY_NORMAL) {
			@Override
			public Void run() {
				try {
					InetSocketAddress addr = (InetSocketAddress)client.getRemoteAddress();
					if (!NetworkSecurity.acceptAddress(addr.getAddress())) {
						if (logger.debug())
							logger.debug("Client rejected: " + addr);
						client.close();
					} else {
						if (logger.debug())
							logger.debug("New client connected: " + client.toString());
						client.configureBlocking(false);
						server.newClient(client);
					}
				} catch (Throwable e) {
					server.acceptError(IO.error(e));
				}
				return null;
			}
		}.start();
	}
	
	private static void acceptError(Server server, Throwable error) {
		new Task.Cpu<Void, NoException>("Call Server.acceptError", Task.PRIORITY_RATHER_LOW) {
			@Override
			public Void run() {
				server.acceptError(IO.error(error));
				return null;
			}
		}.start();
	}
	
	private static void connected(Client client) {
		new Task.Cpu<Void, NoException>("Call Client.connected", Task.PRIORITY_NORMAL) {
			@Override
			public Void run() {
				client.connected();
				return null;
			}
		}.start();
	}
	
	private static void connectionFailed(Client client, Throwable error) {
		new Task.Cpu<Void, NoException>("Call Client.connectionFailed", Task.PRIORITY_RATHER_LOW) {
			@Override
			public Void run() {
				client.connectionFailed(IO.error(error));
				return null;
			}
		}.start();
	}
	
	private void dataReceived(TCPReceiver receiver, ByteBuffer buffer, int nb, SelectableChannel channel) {
		new Task.Cpu<Void, NoException>("Call TCPReceiver.received", Task.PRIORITY_NORMAL) {
			@Override
			public Void run() {
				buffer.flip();
				if (dataLogger.trace()) {
					StringBuilder s = new StringBuilder(nb * 5 + 256);
					s.append(nb).append(" bytes received on ");
					s.append(channel.toString());
					s.append("\r\n");
					DebugUtil.dumpHex(s, buffer);
					dataLogger.trace(s.toString());
				}
				receiver.received(buffer);
				return null;
			}
		}.start();
	}
	
	private static void dataReceived(UDPReceiver receiver, ByteBuffer buffer, SocketAddress source) {
		new Task.Cpu<Void, NoException>("Call UDPReceiver.received", Task.PRIORITY_NORMAL) {
			@Override
			public Void run() {
				buffer.flip();
				receiver.received(buffer, source);
				return null;
			}
		}.start();
	}
	
	private static void endOfInput(TCPReceiver receiver, ByteBuffer buffer) {
		new Task.Cpu<Void, NoException>("Call TCPReceiver.endOfInput", Task.PRIORITY_NORMAL) {
			@Override
			public Void run() {
				receiver.endOfInput(buffer);
				return null;
			}
		}.start();
	}
	
	private static void receiveError(Receiver client, Throwable error, ByteBuffer buffer) {
		new Task.Cpu<Void, NoException>("Call Receiver.receiveError", Task.PRIORITY_RATHER_LOW) {
			@Override
			public Void run() {
				client.receiveError(IO.error(error), buffer);
				return null;
			}
		}.start();
	}

	private static void readyToSend(Sender sender) {
		new Task.Cpu<Void, NoException>("Call Sender.readyToSend", Task.PRIORITY_NORMAL) {
			@Override
			public Void run() {
				sender.readyToSend();
				return null;
			}
		}.start();
	}
	
	private static void sendTimeout(Sender sender) {
		new Task.Cpu<Void, NoException>("Call Sender.sendTimeout", Task.PRIORITY_RATHER_LOW) {
			@Override
			public Void run() {
				sender.sendTimeout();
				return null;
			}
		}.start();
	}
	
	private static void channelClosed(Listener listener) {
		new Task.Cpu<Void, NoException>("Call Listener.channelClosed", Task.PRIORITY_RATHER_LOW) {
			@Override
			public Void run() {
				listener.channelClosed();
				return null;
			}
		}.start();
	}
	
}
