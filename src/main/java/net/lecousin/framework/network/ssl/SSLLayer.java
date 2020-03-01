package net.lecousin.framework.network.ssl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.Executable;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.async.LockPoint;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.network.AttributesContainer;

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
		
		/** Called once the SSL handshake has been successfully done. */
		void handshakeDone();
		
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
	public SSLLayer(SSLContext context) {
		this.context = context;
		this.logger = LCCore.getApplication().getLoggerFactory().getLogger(SSLLayer.class);
		this.bufferCache = ByteArrayCache.getInstance();
	}

	/** Constructor. */
	public SSLLayer() throws GeneralSecurityException {
		this(SSLContext.getDefault());
	}
	
	private Logger logger;
	private ByteArrayCache bufferCache;
	private SSLContext context;
	private List<String> hostNames = null;
	
	private static final String ENGINE_ATTRIBUTE = "protocol.ssl.engine";
	private static final String ENGINE_INPUT_BUFFER_ATTRIBUTE = "protocol.ssl.engine.inputBuffer";
	private static final String ENGINE_INPUT_LOCK = "protocol.ssl.engine.input.lock";
	private static final String HANDSHAKING_ATTRIBUTE = "protocol.ssl.handshaking";
	private static final String HANDSHAKE_FOLLOWUP_ATTRIBUTE = "protocol.ssl.handshake.followup";
	private static final String HANDSHAKE_FOLLOWUP_LOCK_ATTRIBUTE = "protocol.ssl.handshake.followup.lock";

	private static final ByteBuffer emptyBuffer = ByteBuffer.allocate(0).asReadOnlyBuffer();
	
	public void setHostNames(List<String> hostNames) {
		this.hostNames = hostNames;
	}
	
	public Logger getLogger() {
		return logger;
	}
	
	/**
	 * Start the connection with the SSL handshake.
	 */
	public void startConnection(TCPConnection conn, boolean clientMode, int timeout) {
		try {
			SSLEngine engine = context.createSSLEngine();
			if (hostNames != null && !hostNames.isEmpty()) {
				SSLParameters params = new SSLParameters();
				ArrayList<SNIServerName> list = new ArrayList<>(hostNames.size());
				for (String name : hostNames)
					list.add(new SNIHostName(name));
				params.setServerNames(list);
				engine.setSSLParameters(params);
			}
			engine.setUseClientMode(clientMode);
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
		} catch (SSLException e) {
			logger.error("Error starting SSL connection", e);
			conn.close();
		}
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
			lock.lock();
			try {
				doFollowup();
			} finally {
				lock.unlock();
			}
			return null;
		}
		
		private void doFollowup() {
			SSLEngine engine = (SSLEngine)conn.getAttribute(ENGINE_ATTRIBUTE);
			ByteBuffer inputBuffer = (ByteBuffer)conn.getAttribute(ENGINE_INPUT_BUFFER_ATTRIBUTE);
			if (inputBuffer == null) return;
			Object inputLock = conn.getAttribute(ENGINE_INPUT_LOCK);
			do {
				if (logger.debug())
					logger.debug("SSL Handshake status for connection: "
						+ engine.getHandshakeStatus().name() + ", current input buffer: "
						+ inputBuffer.position() + " on " + conn);
				if (conn.isClosed()) {
					conn.closed();
					bufferCache.free(inputBuffer);
					conn.removeAttribute(ENGINE_INPUT_BUFFER_ATTRIBUTE);
					return;
				}
				switch (engine.getHandshakeStatus()) {
				case NEED_TASK:
					needTask(engine);
					return;
				case NEED_WRAP:
					needWrap();
					return;
				case NEED_UNWRAP:
					if (needUnwrap(engine, inputBuffer, inputLock))
						return;
					break;
	            case FINISHED:
	            case NOT_HANDSHAKING:
	            	// handshaking done
	            	synchronized (conn) {
	            		// check we didn't already set it to avoid starting multiple times the next protocol
	            		Boolean still = (Boolean)conn.getAttribute(HANDSHAKING_ATTRIBUTE);
	            		if (!still.booleanValue()) return;
	            		conn.setAttribute(HANDSHAKING_ATTRIBUTE, Boolean.FALSE);
	            	}
	            	conn.handshakeDone();
	            	// if we already have some data ready, let's send it to the connection
	            	synchronized (inputLock) {
	            		if (inputBuffer.position() > 0)
	            			dataReceived(conn, engine, inputBuffer, timeout);
	            	}
	            	return;
	            default:
	            	return;
				}
			} while (true);
		}
		
		private void needTask(SSLEngine engine) {
			do {
				Runnable task = engine.getDelegatedTask();
				if (logger.debug())
					logger.debug("Task to run: " + task);
				if (task == null) break;
				Task<Void,NoException> t = Task.cpu("SSL Server Handshake task", new Executable.FromRunnable(task));
				t.start();
				t.getOutput().onDone(() -> {
					if (logger.debug())
						logger.debug("Task done: " + task);
					followup(conn, timeout);
				});
			} while (true);
		}
		
		private void needWrap() {
			try {
				IAsync<IOException> send = conn.sendEmpty(emptyBuffer);
				send.onDone(() -> {
					if (send.hasError()) {
						conn.close();
						return;
					}
					Task<Void, NoException> task = handshakeTask(conn, timeout);
					conn.setAttribute(HANDSHAKE_FOLLOWUP_ATTRIBUTE, task);
					task.start();
				});
			} catch (ClosedChannelException e) {
				conn.closed();
			}
		}
		
		private boolean needUnwrap(SSLEngine engine, ByteBuffer inputBuffer, Object inputLock) {
			synchronized (inputLock) {
				if (inputBuffer.position() == 0) {
					if (logger.debug())
						logger.debug(
							"No data to unwrap, wait for more data from connection");
					try { conn.waitForData(8192, timeout); }
					catch (ClosedChannelException e) {
						conn.closed();
					}
					return true;
				}
				ByteBuffer dst = ByteBuffer.wrap(bufferCache.get(
					Math.max(engine.getSession().getApplicationBufferSize(),
							engine.getSession().getPacketBufferSize()), true));
				do {
					inputBuffer.flip();
					SSLEngineResult result;
					try { result = engine.unwrap(inputBuffer, dst); }
					catch (SSLException e) {
						if (logger.error())
							logger.error("Cannot unwrap SSL data from connection "
								+ conn, e);
						bufferCache.free(dst);
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
						try { conn.waitForData(8192, timeout); }
						catch (ClosedChannelException e) {
							conn.closed();
						}
						return true;
					}
					if (SSLEngineResult.Status.BUFFER_OVERFLOW.equals(result.getStatus())) {
						if (logger.debug())
							logger.debug(
								"Cannot unwrap because buffer is too small, enlarge it");
						ByteBuffer b = ByteBuffer.wrap(bufferCache.get(dst.capacity() << 1, true));
						dst.flip();
						b.put(dst);
						bufferCache.free(dst);
						dst = b;
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
		while (inputBuffer.hasRemaining()) {
			if (conn.isClosed()) return;
			if (dst == null)
				dst = ByteBuffer.wrap(bufferCache.get(
					Math.max(engine.getSession().getApplicationBufferSize(), engine.getSession().getPacketBufferSize()), true));
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
				inputBuffer.compact();
				if (!buffers.isEmpty())
					conn.dataReceived(buffers);
				else
					try { conn.waitForData(inputBuffer.capacity(), timeout); }
					catch (ClosedChannelException e) { conn.closed(); }
				bufferCache.free(dst);
				return;
			}
			if (SSLEngineResult.Status.BUFFER_OVERFLOW.equals(result.getStatus())) {
				if (logger.debug())
					logger.debug("Output buffer too small to decrypt data, try again with larger one");
				ByteBuffer b = ByteBuffer.wrap(bufferCache.get(dst.capacity() << 1, true));
				dst.flip();
				b.put(dst);
				bufferCache.free(dst);
				dst = b;
				continue;
			}
			// data ready
			dst.flip();
			if (logger.debug())
				logger.debug(dst.remaining() + " bytes decrypted from SSL connection " + conn);
			buffers.add(dst);
			dst = null;
		}
		conn.dataReceived(buffers);
		inputBuffer.compact();
	}
	
	/** Encrypt the given data. */
	@SuppressWarnings("squid:S1319") // return LinkedList instead of List
	public LinkedList<ByteBuffer> encryptDataToSend(TCPConnection conn, List<ByteBuffer> data) throws SSLException {
		SSLEngine engine = (SSLEngine)conn.getAttribute(ENGINE_ATTRIBUTE);
		if (engine == null) throw new SSLException("Cannot send SSL data because connection is not even started");
		Iterator<ByteBuffer> itData = data.iterator();
		LinkedList<ByteBuffer> buffers = new LinkedList<>();
		ByteBuffer dst = null;
		while (itData.hasNext()) {
			ByteBuffer toEncrypt = itData.next();
			if (logger.debug())
				logger.debug("Encrypting " + toEncrypt.remaining() + " bytes for SSL connection " + conn);
			do {
				if (dst == null) dst = ByteBuffer.wrap(bufferCache.get(engine.getSession().getPacketBufferSize(), true));
				SSLEngineResult result = engine.wrap(toEncrypt, dst);
				if (SSLEngineResult.Status.BUFFER_OVERFLOW.equals(result.getStatus())) {
					ByteBuffer b = ByteBuffer.wrap(bufferCache.get(dst.capacity() << 1, true));
					dst.flip();
					b.put(dst);
					bufferCache.free(dst);
					dst = b;
					continue;
				}
				if (dst.position() > 0) {
					dst.flip();
					buffers.add(dst);
					if (logger.debug())
						logger.debug(result.bytesConsumed() + " bytes encrypted into "
							+ dst.remaining() + " bytes for SSL connection " + conn);
					dst = null;
				} else if (logger.debug()) {
					logger.debug(result.bytesConsumed() + " bytes encrypted into 0 bytes for SSL connection " + conn);
				}
				if (!toEncrypt.hasRemaining()) {
					bufferCache.free(toEncrypt);
					break;
				}
			} while (true);
		}
		if (logger.debug())
			logger.debug("Data encrypted to send on SSL connection " + conn + ": " + buffers.size() + " buffer(s)");
		return buffers;
	}

}
