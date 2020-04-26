package net.lecousin.framework.network.ssl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.Executable;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.async.LockPoint;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.mutable.MutableBoolean;
import net.lecousin.framework.util.AttributesContainer;
import net.lecousin.framework.util.DebugUtil;

/**
 * SSL implementation.
 */
public class SSLLayer {
	
	/** Interface to implement for a TCP connection on which an SSL layer is added. */
	public static interface TCPConnection extends AttributesContainer {
		
		/** Return true if the connection is closed. */
		boolean isClosed();
		
		/** Signal that the connection has been closed. */
		void closed();
		
		/** Close the connection. */
		void close();
		
		/** Called once the SSL handshake has been successfully done.
		 * @param alpn if protocol negotiation occurred during handshake, the negotiated protocol is given, else null is given
		 */
		void handshakeDone(String alpn);
		
		/** Called if the SSL handshake fail. */
		void handshakeError(SSLException error);

		/** Signal that we are waiting for data.
		 * @param expectedBytes can be used to create a buffer
		 * @param timeout timeout in milliseconds
		 */
		void waitForData(int expectedBytes, int timeout) throws ClosedChannelException;
		
		/** Signal data has been received and decrypted. */
		void dataReceived(LinkedList<ByteBuffer> data);
		
		/** Send an empty buffer than must be encrypted by used the method encryptDataToSend before to send it to the network. */
		IAsync<IOException> sendEmpty(ByteBuffer emptyBuffer) throws ClosedChannelException;
		
	}

	/** Constructor. */
	public SSLLayer(SSLConnectionConfig config) {
		this.config = config;
		this.logger = LCCore.getApplication().getLoggerFactory().getLogger(SSLLayer.class);
		this.bufferCache = ByteArrayCache.getInstance();
	}

	private Logger logger;
	private ByteArrayCache bufferCache;
	private SSLConnectionConfig config;
	
	private static final String ENGINE_ATTRIBUTE = "protocol.ssl.engine";
	private static final String ENGINE_INPUT_BUFFER_ATTRIBUTE = "protocol.ssl.engine.inputBuffer";
	private static final String ENGINE_INPUT_LOCK = "protocol.ssl.engine.input.lock";
	private static final String HANDSHAKING_ATTRIBUTE = "protocol.ssl.handshaking";
	private static final String HANDSHAKE_FOLLOWUP_ATTRIBUTE = "protocol.ssl.handshake.followup";
	private static final String HANDSHAKE_FOLLOWUP_LOCK_ATTRIBUTE = "protocol.ssl.handshake.followup.lock";
	private static final String HANDSHAKE_TASKS_ATTRIBUTE = "protocol.ssl.handshake.followup.tasks";

	private static final ByteBuffer emptyBuffer = ByteBuffer.allocate(0).asReadOnlyBuffer();
	
	public Logger getLogger() {
		return logger;
	}
	
	/**
	 * Start the connection with the SSL handshake.
	 */
	public void startConnection(TCPConnection conn, boolean clientMode, int timeout) {
		try {
			SSLEngine engine = config.createEngine(clientMode);
			ByteBuffer inputBuffer = ByteBuffer.wrap(bufferCache.get(
				Math.max(engine.getSession().getApplicationBufferSize(), engine.getSession().getPacketBufferSize()) << 1,
				true));
			engine.beginHandshake();
			conn.setAttribute(ENGINE_ATTRIBUTE, engine);
			conn.setAttribute(ENGINE_INPUT_BUFFER_ATTRIBUTE, inputBuffer);
			conn.setAttribute(ENGINE_INPUT_LOCK, new Object());
			conn.setAttribute(HANDSHAKING_ATTRIBUTE, Boolean.TRUE);
			Task<Void, NoException> task = handshakeTask(conn, timeout);
			conn.setAttribute(HANDSHAKE_FOLLOWUP_ATTRIBUTE, task);
			task.start();
		} catch (Exception e) {
			logger.error("Error starting SSL connection", e);
			conn.close();
		}
	}
	
	public int getEncryptedBufferSize(TCPConnection conn) {
		SSLEngine engine = (SSLEngine)conn.getAttribute(ENGINE_ATTRIBUTE);
		return engine == null ? 16384 : engine.getSession().getPacketBufferSize() + 50;
	}

	private Task<Void, NoException> handshakeTask(TCPConnection conn, int timeout) {
		return Task.cpu("SSL Handshake", new HandshakeFollowup(conn, timeout));
	}

	private class HandshakeFollowup implements Executable<Void,NoException> {
		
		public HandshakeFollowup(TCPConnection conn, int timeout) {
			this.conn = conn;
			this.timeout = timeout;
		}
		
		private TCPConnection conn;
		private int timeout;
		
		@SuppressWarnings("unchecked")
		@Override
		public Void execute(Task<Void, NoException> t) {
			LockPoint<NoException> lock;
			synchronized (conn) {
				lock = (LockPoint<NoException>)conn.getAttribute(HANDSHAKE_FOLLOWUP_LOCK_ATTRIBUTE);
				if (lock == null) {
					lock = new LockPoint<>();
					conn.setAttribute(HANDSHAKE_FOLLOWUP_LOCK_ATTRIBUTE, lock);
				}
			}
			boolean unlocked = false;
			lock.lock();
			try {
				unlocked = doFollowup(lock);
			} finally {
				if (!unlocked)
					lock.unlock();
			}
			return null;
		}
		
		private boolean doFollowup(LockPoint<NoException> lock) {
			SSLEngine engine = (SSLEngine)conn.getAttribute(ENGINE_ATTRIBUTE);
			ByteBuffer inputBuffer = (ByteBuffer)conn.getAttribute(ENGINE_INPUT_BUFFER_ATTRIBUTE);
			if (inputBuffer == null) return false;
			Object inputLock = conn.getAttribute(ENGINE_INPUT_LOCK);
			do {
				if (logger.debug())
					logger.debug("SSL Handshake status for connection: "
						+ engine.getHandshakeStatus().name() + ", current input buffer: "
						+ inputBuffer.position() + " on " + conn);
				if (conn.isClosed()) {
					lock.unlock();
					conn.closed();
					bufferCache.free(inputBuffer);
					conn.removeAttribute(ENGINE_INPUT_BUFFER_ATTRIBUTE);
					return true;
				}
				checkHandshakeTasks(engine);
				switch (engine.getHandshakeStatus()) {
				case NEED_TASK:
					needTask();
					return false;
				case NEED_WRAP:
					lock.unlock();
					needWrap();
					return true;
				case NEED_UNWRAP:
					if (needUnwrap(engine, inputBuffer, inputLock, lock))
						return true;
					break;
	            case FINISHED:
	            case NOT_HANDSHAKING:
	            	// handshaking done
	            	synchronized (conn) {
	            		// check we didn't already set it to avoid starting multiple times the next protocol
	            		Boolean still = (Boolean)conn.getAttribute(HANDSHAKING_ATTRIBUTE);
	            		if (!still.booleanValue()) return false;
	            		conn.setAttribute(HANDSHAKING_ATTRIBUTE, Boolean.FALSE);
	            	}
	            	conn.removeAttribute(HANDSHAKE_TASKS_ATTRIBUTE);
	            	lock.unlock();
	            	String alpn = null;
	            	if (SSLConnectionConfig.ALPN_SUPPORTED) {
            			alpn = SSLConnectionConfig.getALPNProtocol(engine);
            			if (alpn != null && alpn.isEmpty())
            				alpn = null;
	            	}
	            	conn.handshakeDone(alpn);
	            	// if we already have some data ready, let's send it to the connection
	            	synchronized (inputLock) {
	            		if (inputBuffer.position() > 0)
	            			dataReceived(conn, engine, inputBuffer, timeout);
	            	}
	            	return true;
	            default:
	            	return false;
				}
			} while (true);
		}
		
		private void checkHandshakeTasks(SSLEngine engine) {
			@SuppressWarnings("unchecked")
			List<Task<Void,NoException>> tasks = (List<Task<Void,NoException>>)conn.getAttribute(HANDSHAKE_TASKS_ATTRIBUTE);
			if (tasks == null) {
				tasks = new LinkedList<>();
				conn.setAttribute(HANDSHAKE_TASKS_ATTRIBUTE, tasks);
			}
			// remove tasks done
			for (Iterator<Task<Void,NoException>> it = tasks.iterator(); it.hasNext(); )
				if (it.next().getOutput().isDone())
					it.remove();
			// launch new tasks
			do {
				Runnable task = engine.getDelegatedTask();
				if (task == null) break;
				if (logger.debug())
					logger.debug("Task to run: " + task);
				Task<Void,NoException> t = Task.unmanaged("SSL Handshake task", new Executable.FromRunnable(task));
				t.start();
				tasks.add(t);
			} while (true);
		}
		
		private void needTask() {
			@SuppressWarnings("unchecked")
			List<Task<Void,NoException>> tasks = (List<Task<Void,NoException>>)conn.getAttribute(HANDSHAKE_TASKS_ATTRIBUTE);
			MutableBoolean firstTask = new MutableBoolean(true);
			Runnable taskDone = () -> {
				synchronized (firstTask) {
					if (!firstTask.get())
						return;
					firstTask.set(false);
				}
				Task.cpu("SSL Handshake followup", Priority.NORMAL, t -> {
					followup(conn, timeout);
					return null;
				}).start();
			};
			for (Iterator<Task<Void,NoException>> it = tasks.iterator(); it.hasNext(); )
				it.next().getOutput().onDone(taskDone);
		}
		
		private void needWrap() {
			try {
				IAsync<IOException> send = conn.sendEmpty(emptyBuffer);
				Task<Void, NoException> task = handshakeTask(conn, timeout);
				conn.setAttribute(HANDSHAKE_FOLLOWUP_ATTRIBUTE, task);
				send.onDone(() -> {
					if (send.hasError()) {
						if (logger.error())
							logger.error("Error sending empty data to " + conn, send.getError());
						conn.close();
						return;
					}
					task.start();
				});
			} catch (ClosedChannelException e) {
				conn.closed();
			}
		}
		
		private boolean needUnwrap(SSLEngine engine, ByteBuffer inputBuffer, Object inputLock, LockPoint<NoException> lock) {
			synchronized (inputLock) {
				if (inputBuffer.position() == 0) {
					if (logger.debug())
						logger.debug(
							"No data to unwrap, wait for more data from connection");
					lock.unlock();
					try { conn.waitForData(8192, timeout); }
					catch (ClosedChannelException e) {
						conn.closed();
					}
					return true;
				}
				ByteBuffer dst = ByteBuffer.wrap(bufferCache.get(engine.getSession().getApplicationBufferSize(), true));
				do {
					inputBuffer.flip();
					SSLEngineResult result;
					try { result = engine.unwrap(inputBuffer, dst); }
					catch (SSLException e) {
						if (logger.error())
							logger.error("Cannot unwrap SSL data from connection "
								+ conn, e);
						bufferCache.free(dst);
						lock.unlock();
						conn.handshakeError(e);
						conn.close();
						return true;
					}
					if (SSLEngineResult.Status.BUFFER_UNDERFLOW.equals(result.getStatus())) {
						if (logger.debug())
							logger.debug(
								"Cannot unwrap, wait for more data from connection");
						bufferCache.free(dst);
						inputBuffer.compact();
						lock.unlock();
						try { conn.waitForData(8192, timeout); }
						catch (ClosedChannelException e) {
							conn.closed();
						}
						return true;
					}
					if (SSLEngineResult.Status.BUFFER_OVERFLOW.equals(result.getStatus())) {
						dst = enlargeBuffer(dst);
						continue;
					}
					if (logger.debug())
						logger.debug(dst.position()
							+ " unwrapped from SSL, remaining input: " + inputBuffer.remaining());
					inputBuffer.compact();
					bufferCache.free(dst);
					break;
				} while (true);
			}
			return false;
		}
	}
	
	private void followup(TCPConnection conn, int timeout) {
		@SuppressWarnings("unchecked")
		Task<Void, NoException> task = (Task<Void, NoException>)conn.getAttribute(HANDSHAKE_FOLLOWUP_ATTRIBUTE);
		if (task != null) {
			synchronized (task) {
				if (!task.isRunning() && !task.isDone()) {
					if (logger.debug())
						logger.debug("Followup task already existing, just wait for connection " + conn);
					return; // not yet started, no need to create a new task
				}
			}
		}
		if (logger.debug())
			logger.debug("Starting followup task for handshaking for connection " + conn);
		task = handshakeTask(conn, timeout);
		conn.setAttribute(HANDSHAKE_FOLLOWUP_ATTRIBUTE, task);
		task.start();
	}
	
	/** To call when some encrypted data has been received.
	 * The data will be decrypted, and the method dataReceived called on the given connection.
	 */
	public void dataReceived(TCPConnection conn, ByteBuffer data, int timeout) {
		SSLEngine engine = (SSLEngine)conn.getAttribute(ENGINE_ATTRIBUTE);
		Object inputLock = conn.getAttribute(ENGINE_INPUT_LOCK);
		synchronized (inputLock) {
			ByteBuffer inputBuffer = (ByteBuffer)conn.getAttribute(ENGINE_INPUT_BUFFER_ATTRIBUTE);
			if (inputBuffer == null) return;
			if (logger.debug())
				logger.debug("SSL data received from connection " + conn + ": "
					+ data.remaining() + " bytes (" + inputBuffer.position() + " already in input buffer)");
			// copy data into buffer
			if (data.remaining() > inputBuffer.remaining()) {
				// enlarge input buffer
				ByteBuffer b = ByteBuffer.wrap(bufferCache.get(
					inputBuffer.capacity() + data.remaining() + engine.getSession().getApplicationBufferSize(), true));
				inputBuffer.flip();
				b.put(inputBuffer);
				bufferCache.free(inputBuffer);
				inputBuffer = b;
				conn.setAttribute(ENGINE_INPUT_BUFFER_ATTRIBUTE, inputBuffer);
				// may be we should reduce buffer size sometimes ?
			}
			inputBuffer.put(data);
			bufferCache.free(data);
			// if we are handshaking, make the follow up
			synchronized (conn) {
				Boolean handshaking = (Boolean)conn.getAttribute(HANDSHAKING_ATTRIBUTE);
				if (handshaking.booleanValue()) {
					followup(conn, timeout);
					return;
				}
			}
			dataReceived(conn, engine, inputBuffer, timeout); // try to unwrap and send decrypted data to the next protocol
		}
	}
	
	// must be called synchronized on inputLock
	private void dataReceived(TCPConnection conn, SSLEngine engine, ByteBuffer inputBuffer, int timeout) {
		inputBuffer.flip();
		if (logger.debug())
			logger.debug("Decrypting " + inputBuffer.remaining() + " bytes from SSL connection " + conn);
		LinkedList<ByteBuffer> buffers = new LinkedList<>();
		ByteBuffer dst = null;
		int packetSize = engine.getSession().getPacketBufferSize();
		int appPacketSize = engine.getSession().getApplicationBufferSize();
		while (inputBuffer.hasRemaining()) {
			if (conn.isClosed()) return;
			if (dst == null) {
				int nbPackets = inputBuffer.remaining() / (packetSize + 40);
				dst = ByteBuffer.wrap(bufferCache.get((nbPackets + 1) * appPacketSize, true));
			}
			SSLEngineResult result;
			try { result = engine.unwrap(inputBuffer, dst); }
			catch (SSLException e) {
				logger.error("Error decrypting data from SSL connection " + conn, e);
				conn.close();
				bufferCache.free(dst);
				return;
			}
			if (SSLEngineResult.Status.BUFFER_UNDERFLOW.equals(result.getStatus())) {
				if (logger.debug())
					logger.debug("Not enough data to decrypt, wait for new data from SSL connection " + conn
						+ ", " + buffers.size() + " buffer(s) decrypted");
				compactInputBuffer(inputBuffer, packetSize, conn);
				if (!buffers.isEmpty())
					conn.dataReceived(buffers);
				else
					try { conn.waitForData(inputBuffer.capacity(), timeout); }
					catch (ClosedChannelException e) { conn.closed(); }
				bufferCache.free(dst);
				return;
			}
			if (SSLEngineResult.Status.BUFFER_OVERFLOW.equals(result.getStatus())) {
				dst = enlargeBuffer(dst);
				continue;
			}
			// data ready
			if (logger.debug())
				logger.debug(dst.position() + " bytes decrypted from SSL connection " + conn);
			if (dst.position() > 0) {
				dst.flip();
				if (logger.trace()) {
					StringBuilder s = new StringBuilder(((dst.remaining() / 16) + 1) * 85 + 128);
					s.append("Decrypted data received:\n");
					DebugUtil.dumpHex(s, dst);
					logger.trace(s.toString());
				}
				buffers.add(dst);
				dst = null;
			}
		}
		conn.dataReceived(buffers);
		compactInputBuffer(inputBuffer, packetSize, conn);
	}
	
	private void compactInputBuffer(ByteBuffer inputBuffer, int packetSize, TCPConnection conn) {
		if (!inputBuffer.hasRemaining() && inputBuffer.capacity() > (packetSize + 50) * 3) {
			bufferCache.free(inputBuffer);
			ByteBuffer newBuffer = ByteBuffer.wrap(bufferCache.get((packetSize + 50) * 2, true));
			conn.setAttribute(ENGINE_INPUT_BUFFER_ATTRIBUTE, newBuffer);
			return;
		}
		inputBuffer.compact();
	}
	
	private ByteBuffer enlargeBuffer(ByteBuffer buf) {
		ByteBuffer b = ByteBuffer.wrap(bufferCache.get(buf.capacity() << 1, true));
		buf.flip();
		b.put(buf);
		bufferCache.free(buf);
		return b;
	}
	
	/** Encrypt the given data. */
	@SuppressWarnings("squid:S1319") // return LinkedList instead of List
	public LinkedList<ByteBuffer> encryptDataToSend(TCPConnection conn, List<ByteBuffer> data) throws SSLException {
		SSLEngine engine = (SSLEngine)conn.getAttribute(ENGINE_ATTRIBUTE);
		if (engine == null) throw new SSLException("Cannot send SSL data because connection is not even started");
		int packetSize = engine.getSession().getApplicationBufferSize();
		int totalToEncrypt = 0;
		for (ByteBuffer toEncrypt : data)
			totalToEncrypt += toEncrypt.remaining();
		if (logger.debug())
			logger.debug("Encrypting " + totalToEncrypt + " bytes for SSL connection " + conn);
		if (logger.trace()) {
			StringBuilder s = new StringBuilder(((totalToEncrypt / 16) + 1) * 85 + 1024);
			s.append("Data to encrypt:\n");
			for (ByteBuffer toEncrypt : data)
				DebugUtil.dumpHex(s, toEncrypt);
			logger.trace(s.toString());
		}
		Iterator<ByteBuffer> itData = data.iterator();
		LinkedList<ByteBuffer> buffers = new LinkedList<>();
		ByteBuffer dst = null;
		while (itData.hasNext()) {
			ByteBuffer toEncrypt = itData.next();
			do {
				if (dst == null) {
					int nbPackets = Math.max(totalToEncrypt / packetSize, 16);
					dst = ByteBuffer.wrap(bufferCache.get((nbPackets + 1) * (packetSize + 64), true));
				}
				int r = toEncrypt.remaining();
				SSLEngineResult result = engine.wrap(toEncrypt, dst);
				totalToEncrypt -= toEncrypt.remaining() - r;
				if (SSLEngineResult.Status.BUFFER_OVERFLOW.equals(result.getStatus())) {
					if (dst.position() > 0) {
						dst.flip();
						buffers.add(dst);
						dst = null;
						continue;
					}
					packetSize <<= 1;
					dst = enlargeBuffer(dst);
					continue;
				}
				if (!toEncrypt.hasRemaining()) {
					bufferCache.free(toEncrypt);
					break;
				}
			} while (true);
		}
		if (dst != null) {
			if (dst.position() > 0) {
				dst.flip();
				buffers.add(dst);
			} else {
				bufferCache.free(dst);
			}
		}
		if (logger.debug())
			logger.debug("Data encrypted to send on SSL connection " + conn + ": " + buffers.size() + " buffer(s)");
		return buffers;
	}

}
