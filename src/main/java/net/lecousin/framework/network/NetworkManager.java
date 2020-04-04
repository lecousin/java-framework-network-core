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
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.network.security.IPBlackList;
import net.lecousin.framework.network.security.NetworkSecurity;
import net.lecousin.framework.util.DebugUtil;

/**
 * The NetworkManager launches a thread to listen to network events using the Java NIO framework,
 * making its usage easier.<br/>
 * The method {@link #register(SelectableChannel, int, Listener, int)} is used to add a listener to a channel.<br/>
 * The listeners are called in separate CPU tasks to avoid slowing down the NetworkManager's thread.
 */
public class NetworkManager implements Closeable {

	/**
	 * Base interface for a listener, with a channelClosed event.
	 */
	public static interface Listener {
		/** Called when the associated channel has been closed. */
		void channelClosed();
	}

	/**
	 * Interface for a server listener, with newClient and acceptError events.
	 */
	public static interface Server extends Listener {
		/** Called when a new client is connected. */
		void newClient(SocketChannel client);
		
		/** Called when an error occurs while accepting clients. */
		void acceptError(IOException error);
	}

	/**
	 * Interface for a client listener, with connected and connectionFailed events.
	 */
	public static interface Client extends Listener {
		/** Called once the client has been connected to the server. */
		void connected();
		
		/** Called when an error occurs while connecting to a server. */
		void connectionFailed(IOException error);
	}
	
	/**
	 * Interface for a receiver listener, with events related to receiving data from the network.
	 */
	public static interface Receiver extends Listener {
		/** Ask to allocate a buffer to receive data.
		 * This allows to allocate an amount of bytes according to what is expected.
		 * Because it is directly called by the NetworkManager's thread, it must be as fast as possible.
		 */
		ByteBuffer allocateReceiveBuffer();
		
		/** Called when an error occurs while receiving data. The buffer may be null in case of timeout. */
		void receiveError(IOException error, ByteBuffer buffer);
	}
	
	/** TCP receiver. */
	public static interface TCPReceiver extends Receiver {
		/** Called when some data has been received. */
		void received(ByteBuffer buffer);
		
		/** Called when no more data can be received. */
		void endOfInput(ByteBuffer buffer);
	}
	
	/** UDP receiver. */
	public static interface UDPReceiver extends Receiver {
		/** Called when data has been received. */
		void received(ByteBuffer buffer, SocketAddress source);
	}

	/**
	 * Interface for a sender listener, with an event readyToSend.
	 */
	public static interface Sender extends Listener {
		/** Called when the associated network channel is ready to send data over the network. */
		void readyToSend();
		
		/** Called if a timeout was specified when registering, and the channel is not yet ready to send
		 * data after the specified timeout.
		 */
		void sendTimeout();
	}

	@SuppressWarnings("squid:S00112") // use of RUntimeException
	private NetworkManager(Application app) {
		logger = app.getLoggerFactory().getLogger("network");
		dataLogger = app.getLoggerFactory().getLogger("network-data");
		logger.info("Starting Network Manager for application " + app.getGroupId() + "-" + app.getArtifactId());
		NetworkSecurity security = NetworkSecurity.get(app);
		app.toClose(1, this);
		try {
			selector = Selector.open();
		} catch (IOException e) {
			throw new RuntimeException("Unable to start Network Manager", e);
		}
		security.isLoaded().onDone(() -> {
			IPBlackList bl = security.getFeature(IPBlackList.class);
			if (bl == null)
				logger.error("Network security does not contain IPBlackList for application "
					+ app.getGroupId() + "-" + app.getArtifactId());
			worker = app.getThreadFactory().newThread(new WorkerLoop(bl));
			worker.setName("Network Manager");
			worker.start();
		});
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
	private int maxDataTraceSize = -1;
	
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
		private AsyncSupplier<SelectionKey, IOException> result;
		private Exception registerStack;
		
		private String traceChannelOperations() {
			return channel + " for operations " + newOps + " and timeout " + timeout;
		}
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
	public <T extends SelectableChannel & NetworkChannel> AsyncSupplier<SelectionKey, IOException> register(
		T channel, int ops, Listener listener, int timeout
	) {
		if (channel == null) {
			logger.error("Null channel given to NetworkManager.register", new Exception());
			return new AsyncSupplier<>(null, new IOException("Cannot register a null channel"));
		}
		if ((ops & SelectionKey.OP_ACCEPT) != 0 && !(listener instanceof Server))
			return invalidListener(listener, "ACCEPT");
		if ((ops & SelectionKey.OP_CONNECT) != 0 && !(listener instanceof Client))
			return invalidListener(listener, "CONNECT");
		if ((ops & SelectionKey.OP_READ) != 0 && !(listener instanceof Receiver))
			return invalidListener(listener, "READ");
		if ((ops & SelectionKey.OP_WRITE) != 0 && !(listener instanceof Sender))
			return invalidListener(listener, "WRITE");
		RegisterRequest req = new RegisterRequest();
		req.channel = channel;
		req.newOps = ops;
		req.listener = listener;
		req.timeout = timeout;
		req.result = new AsyncSupplier<>();
		if (logger.trace())
			logger.trace("Registering " + req.traceChannelOperations());
		if (logger.debug())
			req.registerStack = new Exception("registration was here");
		synchronized (requests) {
			requests.addLast(req);
		}
		selector.wakeup();
		return req.result;
	}
	
	private AsyncSupplier<SelectionKey, IOException> invalidListener(Listener listener, String opName) {
		IOException error = new IOException("Invalid listener for " + opName + ": " + listener.getClass().getName());
		logger.error(error.getMessage(), error);
		return new AsyncSupplier<>(null, error);
	}
	
	private class WorkerLoop implements Runnable {
		
		private WorkerLoop(IPBlackList blacklist) {
			this.blacklist = blacklist;
		}

		private long workingTime = 0;
		private long waitingTime = 0;
		private IPBlackList blacklist;
		
		@Override
		@SuppressWarnings({
			"squid:S3776", "squid:S1141", // we keep complexity and nested try for performance
			"squid:S1193" // instanceof IOException
		})
		public void run() {
			long start = System.nanoTime();
			int loopCount = 0;
			while (!stop) {
				processRegisterRequests();
				if (stop) break;
				Set<SelectionKey> keys = selector.selectedKeys();
				if (!keys.isEmpty()) {
					boolean trace = logger.trace();
					if (trace) logger.trace(keys.size() + " network channels are ready for operations");
					loopCount++;
					for (Iterator<SelectionKey> it = keys.iterator(); it.hasNext(); ) {
						SelectionKey key = it.next();
						it.remove();
						Attachment listeners = (Attachment)key.attachment();
						if (!key.isValid()) {
							listeners.channelClosed();
							continue;
						}
						int ops;
						try { ops = key.readyOps(); }
						catch (CancelledKeyException e) {
							listeners.channelClosed();
							continue;
						}
						if (trace) logger.trace("Ready operation: " + ops + " for " + key.channel());
						if ((ops & SelectionKey.OP_ACCEPT) != 0) {
							Server server = listeners.onAccept;
							try {
								SocketChannel client = ((ServerSocketChannel)key.channel()).accept();
								acceptClient(server, client);
							} catch (Exception e) {
								acceptError(server, e);
							}
						}
						if ((ops & SelectionKey.OP_CONNECT) != 0) {
							// a socket is connected
							Client client = listeners.onConnect;
							if (trace)
								logger.trace("A socket is ready to be connected: " + key.channel().toString());
							try {
								((SocketChannel)key.channel()).finishConnect();
								key.interestOps(0);
								listeners.reset();
								connected(client);
							} catch (Exception e) {
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
										if (trace)
											logger.trace("End of stream reached for socket: "
												+ key.channel().toString());
										key.interestOps(0);
										listeners.reset();
										endOfInput(tcp, buffer);
									} else {
										try {
											int iops = key.interestOps();
											key.interestOps(iops - (iops & SelectionKey.OP_READ));
										} catch (CancelledKeyException e) {
											// ignore
										}
										listeners.onRead = null;
										listeners.onReadTimeout = 0;
										dataReceived(tcp, buffer, nb, key.channel());
									}
								} else {
									UDPReceiver udp = (UDPReceiver)receiver;
									SocketAddress source = ((DatagramChannel)key.channel()).receive(buffer);
									try {
										int iops = key.interestOps();
										key.interestOps(iops - (iops & SelectionKey.OP_READ));
										listeners.onRead = null;
										listeners.onReadTimeout = 0;
									} catch (CancelledKeyException e) {
										/* ignore */
									}
									dataReceived(udp, buffer, source);
								}
							} catch (Exception e) {
								if (logger.error() && !(e instanceof IOException))
									logger.error("Error while receiving data from network on "
										+ key.channel().toString(), e);
								receiveError(receiver, e, buffer);
								resetAndClose(key);
								channelClosed(receiver);
							}
						}
						if ((ops & SelectionKey.OP_WRITE) != 0) {
							Sender sender = listeners.onWrite;
							try {
								int iops = key.interestOps();
								key.interestOps(iops - (iops & SelectionKey.OP_WRITE));
								listeners.onWrite = null;
								listeners.onWriteTimeout = 0;
								if (trace)
									logger.trace("Socket ready to send data on " + key.channel());
								readyToSend(sender);
							} catch (CancelledKeyException e) {
								channelClosed(sender);
							} catch (Exception t) {
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
					} else {
						selector.select(60000);
					}
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
			catch (Exception e) { /* ignore */ }
			if (logger.info())
				logger.info("Network Manager closed, worked during "
					+ String.format("%.5f", Double.valueOf(workingTime * 1.d / 1000000000))
					+ "s., waited " + String.format("%.5f", Double.valueOf(waitingTime * 1.d / 1000000000)) + "s.");
		}
	
		private void processRegisterRequests() {
			do {
				RegisterRequest req;
				synchronized (requests) {
					req = requests.pollFirst();
				}
				if (req == null) break;
				try {
					processRegisterRequest(req);
				} catch (ClosedChannelException e) {
					if (logger.info()) logger.info("Channel closed while registering " + req.traceChannelOperations());
					if (req.listener != null)
						channelClosed(req.listener);
					req.result.error(e);
				}
			} while (!stop);
		}
		
		@SuppressWarnings("squid:S1141") // nested try
		private void processRegisterRequest(RegisterRequest req) throws ClosedChannelException {
			SelectionKey key = req.channel.keyFor(selector);
			if (key == null) {
				Attachment listeners = new Attachment();
				listeners.set(req.newOps, req.listener, req.timeout);
				key = req.channel.register(selector, req.newOps, listeners);
				if (logger.trace()) logger.trace("Registered: " + req.traceChannelOperations());
				req.result.unblockSuccess(key);
				return;
			}
			Attachment listeners = (Attachment)key.attachment();
			try {
				int curOps = key.interestOps();
				int conflict = curOps & req.newOps;
				if (conflict == 0) {
					key.interestOps(curOps | req.newOps);
					listeners.set(req.newOps, req.listener, req.timeout);
					if (logger.trace())
						logger.trace("Registered: " + req.traceChannelOperations());
					req.result.unblockSuccess(key);
					return;
				}
				IOException error = new IOException("Operation " + req.newOps + " already registered on " + req.channel,
					req.registerStack);
				if (logger.error()) logger.error("Operation already registered", error);
				req.result.unblockError(error);
				try {
					if ((conflict & SelectionKey.OP_ACCEPT) != 0)
						acceptError((Server)req.listener,
							new IOException("Already registered for accept operation"));
					if ((conflict & SelectionKey.OP_CONNECT) != 0)
						connectionFailed((Client)req.listener,
							new IOException("Already registered for connect operation"));
					if ((conflict & SelectionKey.OP_READ) != 0)
						receiveError((Receiver)req.listener,
							new IOException("Already registered for read operation"), null);
					if ((conflict & SelectionKey.OP_WRITE) != 0)
						logger.error("Already registered for write operation",
							new IOException("Already registered for write operation"));
				} catch (Exception t) {
					logger.error("Error calling listener", t);
				}
			} catch (CancelledKeyException e) {
				if (logger.info()) logger.info("Cancelled key while registering " + req.traceChannelOperations());
				listeners.channelClosed();
				req.result.error(IO.error(e));
			}
		}
		
		@SuppressWarnings("squid:S3776") // complexity
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
						resetAndClose(key);
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
		
		private void resetAndClose(SelectionKey key) {
			try { key.interestOps(0); }
			catch (CancelledKeyException e2) { /* ignore */ }
			catch (Exception t) { logger.error("Error", t); }
			((Attachment)key.attachment()).reset();
			try { key.channel().close(); }
			catch (Exception t) { /* ignore */ }
		}
		
		private void acceptClient(Server server, SocketChannel client) {
			Task.cpu("Accept network client", Task.Priority.NORMAL, t -> {
				try {
					InetSocketAddress addr = (InetSocketAddress)client.getRemoteAddress();
					if (!blacklist.acceptAddress(addr.getAddress())) {
						if (logger.debug())
							logger.debug("Client rejected: " + addr);
						client.close();
					} else {
						if (logger.debug())
							logger.debug("New client connected: " + client.toString());
						client.configureBlocking(false);
						server.newClient(client);
					}
				} catch (Exception e) {
					server.acceptError(IO.error(e));
				}
				return null;
			}).start();
		}
		
		
		private void acceptError(Server server, Throwable error) {
			Task.cpu("Call Server.acceptError", Task.Priority.RATHER_LOW, t -> {
				server.acceptError(IO.error(error));
				return null;
			}).start();
		}
		
		private void connected(Client client) {
			Task.cpu("Call Client.connected", Task.Priority.NORMAL, t -> {
				client.connected();
				return null;
			}).start();
		}
		
		private void connectionFailed(Client client, Throwable error) {
			Task.cpu("Call Client.connectionFailed", Task.Priority.RATHER_LOW, t -> {
				client.connectionFailed(IO.error(error));
				return null;
			}).start();
		}
		
		private void dataReceived(TCPReceiver receiver, ByteBuffer buffer, int nb, SelectableChannel channel) {
			Task.cpu("Call TCPReceiver.received", Task.Priority.NORMAL, t -> {
				buffer.flip();
				if (dataLogger.trace()) {
					StringBuilder s = new StringBuilder(nb * 5 + 256);
					s.append(nb).append(" bytes received on ");
					s.append(channel.toString());
					s.append("\r\n");
					DebugUtil.dumpHex(s, buffer);
					traceData(s);
				}
				receiver.received(buffer);
				return null;
			}).start();
		}
		
		private void dataReceived(UDPReceiver receiver, ByteBuffer buffer, SocketAddress source) {
			Task.cpu("Call UDPReceiver.received", Task.Priority.NORMAL, t -> {
				buffer.flip();
				receiver.received(buffer, source);
				return null;
			}).start();
		}
		
		private void endOfInput(TCPReceiver receiver, ByteBuffer buffer) {
			Task.cpu("Call TCPReceiver.endOfInput", Task.Priority.NORMAL, t -> {
				receiver.endOfInput(buffer);
				return null;
			}).start();
		}
		
		private void receiveError(Receiver client, Throwable error, ByteBuffer buffer) {
			Task.cpu("Call Receiver.receiveError", Task.Priority.RATHER_LOW, t -> {
				client.receiveError(IO.error(error), buffer);
				return null;
			}).start();
		}

		private void readyToSend(Sender sender) {
			Task.cpu("Call Sender.readyToSend", Task.Priority.NORMAL, t -> {
				sender.readyToSend();
				return null;
			}).start();
		}
		
		private void sendTimeout(Sender sender) {
			Task.cpu("Call Sender.sendTimeout", Task.Priority.RATHER_LOW, t -> {
				sender.sendTimeout();
				return null;
			}).start();
		}

	}
	
	private static void channelClosed(Listener listener) {
		Task.cpu("Call Listener.channelClosed", Task.Priority.RATHER_LOW, t -> {
			if (listener != null)
				listener.channelClosed();
			return null;
		}).start();
	}
	
	public int getMaximumDataTraceSize() {
		return maxDataTraceSize;
	}
	
	public void setMaximumDataTraceSize(int maxSize) {
		maxDataTraceSize = maxSize;
	}
	
	/** Trace network data. */
	public void traceData(StringBuilder s) {
		if (maxDataTraceSize > 100 && s.length() > maxDataTraceSize) {
			int middle = maxDataTraceSize / 2 - 10;
			s.replace(middle, s.length() - middle, "\n[...]\n");
		}
		dataLogger.trace(s.toString());
	}
	
}
