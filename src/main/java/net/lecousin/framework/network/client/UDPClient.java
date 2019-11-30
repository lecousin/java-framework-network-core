package net.lecousin.framework.network.client;

import java.io.Closeable;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;

import net.lecousin.framework.collections.TurnArray;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.CancelException;
import net.lecousin.framework.network.NetworkManager;
import net.lecousin.framework.util.Pair;

/**
 * UPD Client.
 */
public class UDPClient implements Closeable {

	/** Constructor. */
	public UDPClient(SocketAddress target) {
		this.target = target;
		manager = NetworkManager.get();
	}
	
	private final NetworkManager manager;
	private final SocketAddress target;
	private DatagramChannel channel;
	private TurnArray<Pair<ByteBuffer, Async<IOException>>> toSend = new TurnArray<>();
	
	private NetworkManager.Sender sender = new NetworkManager.Sender() {
		@Override
		public void channelClosed() {
			close();
		}
		
		@Override
		public void sendTimeout() {
			// nothing
		}
		
		@Override
		public void readyToSend() {
			boolean needsMore = false; 
			while (true) {
				Pair<ByteBuffer, Async<IOException>> bufToSend;
				synchronized (toSend) {
					if (toSend.isEmpty()) break;
					bufToSend = toSend.getFirst();
				}
				int nb;
				try {
					synchronized (UDPClient.this) {
						if (channel == null) throw new ClosedChannelException();
						nb = channel.send(bufToSend.getValue1(), target);
					}
				} catch (IOException e) {
					// error while sending data, just skip it
					if (bufToSend.getValue2() != null) bufToSend.getValue2().error(e);
					synchronized (toSend) {
						if (!toSend.isEmpty()) toSend.removeFirst();
					}
					continue;
				}
				if (nb == 0) {
					// cannot write anymore
					needsMore = true;
					break;
				}
				if (!bufToSend.getValue1().hasRemaining()) {
					if (bufToSend.getValue2() != null)
						bufToSend.getValue2().unblock();
					synchronized (toSend) {
						if (!toSend.isEmpty())
							toSend.removeFirst();
					}
				}
			}
			if (!needsMore) {
				// no more data to send
				return;
			}
			// still something to write, we need to register to the network manager
			manager.register(channel, SelectionKey.OP_WRITE, sender, 0);
		}
	};
	
	private synchronized void openChannel() throws IOException {
		if (channel != null) return;
		channel = DatagramChannel.open();
		channel.configureBlocking(false);
		channel.connect(target); // to avoid security checks on every send
	}
	
	@Override
	public synchronized void close() {
		if (channel != null) {
			try { channel.close(); }
			catch (Exception e) { /* ignore */ }
			channel = null;
			synchronized (toSend) {
				Pair<ByteBuffer, Async<IOException>> p;
				while ((p = toSend.pollFirst()) != null) {
					if (p.getValue2() != null)
						p.getValue2().cancel(new CancelException("Channel closed"));
				}
			}
		}
	}
	
	/**
	 * Send data. Data are queued until they can be sent, so multiple calls to this method can be done
	 * without waiting for the previous message to be sent.
	 * @param data the data to send
	 * @param onSent unblocked when the data has been fully sent or an error occured. Can be null.
	 */
	public void send(ByteBuffer data, Async<IOException> onSent) {
		if (data.remaining() == 0) {
			if (onSent != null) onSent.unblock();
			return;
		}
		try { openChannel(); }
		catch (IOException e) {
			if (onSent != null) onSent.error(e);
			return;
		}
		synchronized (toSend) {
			toSend.push(new Pair<>(data, onSent));
			if (toSend.size() == 1)
				manager.register(channel, SelectionKey.OP_WRITE, sender, 0);
		}
	}

	/** Interface to implement to wait an answer from the server. */
	public static interface AnswerListener {
		/** Called when some data are received.
		 * Must return null if no more data is expected.
		 * Else it can return the same buffer to continue to fill it, or allocate a new one especially if the
		 * current buffer is already full. 
		 */
		ByteBuffer dataReceived(ByteBuffer buffer);
		
		/** Called if an error occurs while sending the data. */
		void error(IOException error);
		
		/** Called if a timeout occurs. */
		void timeout();
	}
	
	private class Receiver implements NetworkManager.UDPReceiver {
		private Receiver(ByteBuffer buffer, AnswerListener listener, int timeout) {
			this.buffer = buffer;
			this.listener = listener;
			this.timeout = timeout;
		}
		
		private ByteBuffer buffer;
		private AnswerListener listener;
		private int timeout;
		
		@Override
		public void channelClosed() {
			error(new ClosedChannelException());
		}
		
		@Override
		public void receiveError(IOException error, ByteBuffer buffer) {
			error(error);
		}
		
		private void error(IOException error) {
			AnswerListener l;
			synchronized (UDPClient.this) {
				close();
				l = listener;
				listener = null;
			}
			if (l != null) l.error(error);
		}
		
		@Override
		public ByteBuffer allocateReceiveBuffer() {
			return buffer;
		}
		
		@Override
		public void received(ByteBuffer buffer, SocketAddress source) {
			AnswerListener l;
			synchronized (UDPClient.this) {
				l = listener;
				listener = null;
			}
			this.buffer = l.dataReceived(buffer);
			if (this.buffer == null) {
				close();
				return;
			}
			synchronized (UDPClient.this) {
				listener = l;
			}
			manager.register(channel, SelectionKey.OP_READ, this, timeout);
		}
	}
	
	/**
	 * Wait for an answer from the server. 
	 * @param buffer An initial buffer to fill with data received from the server.
	 * @param listener The listener to handle events.
	 * @param timeout a timeout in milliseconds, or 0 for no timeout.
	 */
	public void waitForAnswer(ByteBuffer buffer, AnswerListener listener, int timeout) {
		try { openChannel(); }
		catch (IOException e) {
			listener.error(e);
			return;
		}
		manager.register(channel, SelectionKey.OP_READ, new Receiver(buffer, listener, timeout), timeout);
	}
	
}
