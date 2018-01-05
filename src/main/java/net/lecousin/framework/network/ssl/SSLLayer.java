package net.lecousin.framework.network.ssl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;
import net.lecousin.framework.concurrent.synch.LockPoint;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.network.AttributesContainer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * SSL implementation.
 */
public class SSLLayer {
	
	public static final Log logger = LogFactory.getLog(SSLLayer.class);	
	
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

		/** Signal that we are waiting for data. */
		void waitForData() throws ClosedChannelException;
		
		/** Signal data has been received and decrypted. */
		void dataReceived(LinkedList<ByteBuffer> data);
		
		/** Send an empty buffer than must be encrypted by used the method encryptDataToSend before to send it to the network. */
		ISynchronizationPoint<IOException> sendEmpty(ByteBuffer emptyBuffer) throws ClosedChannelException;
		
	}

	/** Constructor. */
	public SSLLayer(SSLContext context) {
		this.context = context;
	}

	/** Constructor. */
	public SSLLayer() throws GeneralSecurityException {
		this(SSLContext.getDefault());
	}
	
	private SSLContext context;
	private List<String> hostNames = null;
	
	private static final String ENGINE_ATTRIBUTE = "protocol.ssl.engine";
	private static final String ENGINE_INPUT_BUFFER_ATTRIBUTE = "protocol.ssl.engine.inputBuffer";
	private static final String HANDSHAKING_ATTRIBUTE = "protocol.ssl.handshaking";
	private static final String HANDSHAKE_FOLLOWUP_ATTRIBUTE = "protocol.ssl.handshake.followup";
	private static final String HANDSHAKE_FOLLOWUP_LOCK_ATTRIBUTE = "protocol.ssl.handshake.followup.lock";

	private static final ByteBuffer emptyBuffer = ByteBuffer.allocate(0);
	
	public void setHostNames(List<String> hostNames) {
		this.hostNames = hostNames;
	}
	
	/**
	 * Start the connection with the SSL handshake.
	 */
	public void startConnection(TCPConnection conn, boolean clientMode) {
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
			ByteBuffer inputBuffer = ByteBuffer.allocate(
				Math.max(engine.getSession().getApplicationBufferSize(), engine.getSession().getPacketBufferSize()) << 1);
			engine.beginHandshake();
			conn.setAttribute(ENGINE_ATTRIBUTE, engine);
			conn.setAttribute(ENGINE_INPUT_BUFFER_ATTRIBUTE, inputBuffer);
			conn.setAttribute(HANDSHAKING_ATTRIBUTE, Boolean.TRUE);
			HandshakeFollowup task = new HandshakeFollowup(conn);
			conn.setAttribute(HANDSHAKE_FOLLOWUP_ATTRIBUTE, task);
			task.start();
		} catch (SSLException e) {
			// TODO
		}
	}
	
	private class HandshakeFollowup extends Task.Cpu<Void,NoException> {
		public HandshakeFollowup(TCPConnection conn) {
			super("SSL Handshake", Task.PRIORITY_NORMAL);
			this.conn = conn;
		}
		
		private TCPConnection conn;
		
		@SuppressWarnings("unchecked")
		@Override
		public Void run() {
			LockPoint<NoException> lock;
			synchronized (conn) {
				lock = (LockPoint<NoException>)conn.getAttribute(HANDSHAKE_FOLLOWUP_LOCK_ATTRIBUTE);
				if (lock == null) {
					lock = new LockPoint<NoException>();
					conn.setAttribute(HANDSHAKE_FOLLOWUP_LOCK_ATTRIBUTE, lock);
				}
			}
			lock.lock();
			try {
				SSLEngine engine = (SSLEngine)conn.getAttribute(ENGINE_ATTRIBUTE);
				ByteBuffer inputBuffer = (ByteBuffer)conn.getAttribute(ENGINE_INPUT_BUFFER_ATTRIBUTE);
				do {
					if (logger.isDebugEnabled())
						logger.debug("SSL Handshake status for connection: "
							+ engine.getHandshakeStatus().name() + ", current input buffer: "
							+ inputBuffer.position() + " on " + conn);
					if (conn.isClosed()) {
						conn.closed();
						return null;
					}
					switch (engine.getHandshakeStatus()) {
					case NEED_TASK: {
							do {
								Runnable task = engine.getDelegatedTask();
								if (logger.isDebugEnabled())
									logger.debug("Task to run: " + task);
								if (task == null) break;
								Task<Void,NoException> t = new Task.Cpu.FromRunnable(
									task, "SSL Server Handshake task", Task.PRIORITY_NORMAL);
								t.start();
								t.getOutput().listenInline(new Runnable() {
									@Override
									public void run() {
										if (logger.isDebugEnabled())
											logger.debug("Task done: " + task);
										followup(conn);
									}
								});
							} while (true);
							return null;
						}
					case NEED_WRAP:
						try {
							ISynchronizationPoint<IOException> send = conn.sendEmpty(emptyBuffer);
							send.listenInline(() -> {
								if (send.hasError()) {
									conn.close();
									return;
								}
								HandshakeFollowup task = new HandshakeFollowup(conn);
								conn.setAttribute(HANDSHAKE_FOLLOWUP_ATTRIBUTE, task);
								task.start();
							});
						} catch (ClosedChannelException e) {
							conn.closed();
						}
						return null;
					case NEED_UNWRAP:
						synchronized (inputBuffer) {
							if (inputBuffer.position() == 0) {
								if (logger.isDebugEnabled())
									logger.debug(
										"No data to unwrap, wait for more data from connection");
								try { conn.waitForData(); }
								catch (ClosedChannelException e) {
									conn.closed();
									return null;
								}
								return null;
							}
							ByteBuffer dst = ByteBuffer.allocate(
								Math.max(engine.getSession().getApplicationBufferSize(),
										engine.getSession().getPacketBufferSize()));
							do {
								inputBuffer.flip();
								SSLEngineResult result;
								try { result = engine.unwrap(inputBuffer, dst); }
								catch (SSLException e) {
									if (logger.isErrorEnabled())
										logger.error("Cannot unwrap SSL data from connection "
											+ conn, e);
									conn.close();
									return null;
								}
								if (SSLEngineResult.Status.BUFFER_UNDERFLOW.equals(result.getStatus())) {
									if (logger.isDebugEnabled())
										logger.debug(
											"Cannot unwrap, wait for more data from connection");
									inputBuffer.compact();
									try { conn.waitForData(); }
									catch (ClosedChannelException e) {
										conn.closed();
										return null;
									}
									return null;
								}
								if (SSLEngineResult.Status.BUFFER_OVERFLOW.equals(result.getStatus())) {
									if (logger.isDebugEnabled())
										logger.debug(
											"Cannot unwrap because buffer is too small, enlarge it");
									ByteBuffer b = ByteBuffer.allocate(dst.capacity() << 1);
									dst.flip();
									b.put(dst);
									dst = b;
									continue;
								}
								if (logger.isDebugEnabled())
									logger.debug(dst.position()
										+ " unwrapped from SSL, remaining input: " + inputBuffer.remaining());
								inputBuffer.compact();
								break;
							} while (true);
						}
						break;
		            case FINISHED:
		            case NOT_HANDSHAKING:
		            	// handshaking done
		            	synchronized (conn) {
		            		// check we didn't already set it to avoid starting multiple times the next protocol
		            		Boolean still = (Boolean)conn.getAttribute(HANDSHAKING_ATTRIBUTE);
		            		if (!still.booleanValue()) return null;
		            		conn.setAttribute(HANDSHAKING_ATTRIBUTE, Boolean.FALSE);
		            	}
		            	conn.handshakeDone();
		            	// if we already have some data ready, let's send it to the connection
		            	synchronized (inputBuffer) {
		            		if (inputBuffer.position() > 0)
		            			dataReceived(conn, engine, inputBuffer);
		            	}
		            	return null;
		            default:
		            	return null;
					}
				} while (true);
			} finally {
				lock.unlock();
			}
		}
	}
	
	private void followup(TCPConnection conn) {
		HandshakeFollowup task = (HandshakeFollowup)conn.getAttribute(HANDSHAKE_FOLLOWUP_ATTRIBUTE);
		if (task != null) {
			synchronized (task) {
				if (!task.isRunning() && !task.isDone()) {
					if (logger.isDebugEnabled())
						logger.debug("Followup task already existing, just wait for connection " + conn);
					return; // not yet started, no need to create a new task
				}
			}
		}
		if (logger.isDebugEnabled())
			logger.debug("Starting followup task for handshaking for connection " + conn);
		task = new HandshakeFollowup(conn);
		conn.setAttribute(HANDSHAKE_FOLLOWUP_ATTRIBUTE, task);
		task.start();
	}
	
	/** To call when some encrypted data has been received.
	 * The data will be decrypted, and the method dataReceived called on the given connection.
	 */
	public void dataReceived(TCPConnection conn, ByteBuffer data, Runnable onbufferavailable) {
		SSLEngine engine = (SSLEngine)conn.getAttribute(ENGINE_ATTRIBUTE);
		ByteBuffer inputBuffer = (ByteBuffer)conn.getAttribute(ENGINE_INPUT_BUFFER_ATTRIBUTE);
		if (logger.isDebugEnabled())
			logger.debug("SSL data received from connection " + conn + ": "
				+ data.remaining() + " bytes (" + inputBuffer.position() + " already in input buffer)");
		synchronized (inputBuffer) {
			// copy data into buffer
			if (data.remaining() > inputBuffer.remaining()) {
				// enlarge input buffer
				ByteBuffer b = ByteBuffer.allocate(
					inputBuffer.capacity() + data.remaining() + engine.getSession().getApplicationBufferSize());
				inputBuffer.flip();
				b.put(inputBuffer);
				inputBuffer = b;
				conn.setAttribute(ENGINE_INPUT_BUFFER_ATTRIBUTE, inputBuffer);
				// TODO reduce it sometimes ???
			}
			inputBuffer.put(data);
			if (onbufferavailable != null)
				onbufferavailable.run();
			// if we are handshaking, make the follow up
			synchronized (conn) {
				Boolean handshaking = (Boolean)conn.getAttribute(HANDSHAKING_ATTRIBUTE);
				if (handshaking.booleanValue()) {
					followup(conn);
					return;
				}
			}
			dataReceived(conn, engine, inputBuffer); // try to unwrap and send decrypted data to the next protocol
		}
	}
	
	// must be called synchronized on inputBuffer
	private static void dataReceived(TCPConnection conn, SSLEngine engine, ByteBuffer inputBuffer) {
		inputBuffer.flip();
		if (logger.isDebugEnabled())
			logger.debug("Decrypting " + inputBuffer.remaining() + " bytes from SSL connection " + conn);
		LinkedList<ByteBuffer> buffers = new LinkedList<ByteBuffer>();
		while (inputBuffer.hasRemaining()) {
			ByteBuffer dst = ByteBuffer.allocate(
				Math.max(engine.getSession().getApplicationBufferSize(), engine.getSession().getPacketBufferSize()));
			SSLEngineResult result;
			try { result = engine.unwrap(inputBuffer, dst); }
			catch (SSLException e) {
				if (logger.isErrorEnabled())
					logger.error("Error decrypting data from SSL connection", e);
				conn.close();
				return;
			}
			if (SSLEngineResult.Status.BUFFER_UNDERFLOW.equals(result.getStatus())) {
				if (!buffers.isEmpty())
					conn.dataReceived(buffers);
				if (logger.isDebugEnabled())
					logger.debug("Not enough data to decrypt, wait for new data from SSL connection " + conn);
				inputBuffer.compact();
				try { conn.waitForData(); }
				catch (ClosedChannelException e) { conn.closed(); }
				return;
			}
			if (SSLEngineResult.Status.BUFFER_OVERFLOW.equals(result.getStatus())) {
				if (logger.isDebugEnabled())
					logger.debug("Output buffer too small to decrypt data, try again with larger one");
				ByteBuffer b = ByteBuffer.allocate(dst.capacity() << 1);
				dst.flip();
				b.put(dst);
				dst = b;
				continue;
			}
			// data ready
			dst.flip();
			if (logger.isDebugEnabled())
				logger.debug(dst.remaining() + " bytes decrypted from SSL connection " + conn);
			buffers.add(dst);
		}
		conn.dataReceived(buffers);
		inputBuffer.compact();
	}
	
	/** Encrypt the given data. */
	public LinkedList<ByteBuffer> encryptDataToSend(TCPConnection conn, ByteBuffer data) {
		SSLEngine engine = (SSLEngine)conn.getAttribute(ENGINE_ATTRIBUTE);
		if (logger.isDebugEnabled())
			logger.debug("Encrypting " + data.remaining() + " bytes for SSL connection " + conn);
		LinkedList<ByteBuffer> buffers = new LinkedList<>();
		ByteBuffer dst = null;
		do {
			if (dst == null) dst = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
			SSLEngineResult result;
			try { result = engine.wrap(data, dst); }
			catch (SSLException e) {
				if (logger.isErrorEnabled())
					logger.error("Error encrypting data for SSL client", e);
				conn.close();
				return null;
			}
			if (SSLEngineResult.Status.BUFFER_OVERFLOW.equals(result.getStatus())) {
				ByteBuffer b = ByteBuffer.allocate(dst.capacity() << 1);
				dst.flip();
				b.put(dst);
				dst = b;
				continue;
			}
			dst.flip();
			buffers.add(dst);
			if (logger.isDebugEnabled())
				logger.debug(result.bytesConsumed() + " bytes encrypted into " + dst.remaining() + " bytes for SSL connection " + conn);
			dst = null;
			if (!data.hasRemaining())
				break;
		} while (true);
		return buffers;
	}

}
