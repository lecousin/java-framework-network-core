package net.lecousin.framework.network;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.util.Provider;

/** Base interface for a TCP connection with a remote end-point. */
public interface TCPRemote {
	
	/** Return the local address. */
	SocketAddress getLocalAddress() throws IOException;
	
	/** Return the remote address. */
	SocketAddress getRemoteAddress() throws IOException;

	/** Send data to the remote end-point. */
	ISynchronizationPoint<IOException> send(ByteBuffer data);
	
	/** The data will be sent as soon as possible, however a subsequent call to this method will override
	 * the previous one.
	 * The data will be sent after all data sent through the send method is finished.
	 * It means that if data is sent again using the send method before this data is sent,
	 * this data will be send after the new send is finished.
	 */
	void newDataToSendWhenPossible(Provider<ByteBuffer> dataProvider, SynchronizationPoint<IOException> sp);
	
	/** Call the given listener when the TCP connection is closed. */
	void onclosed(Runnable listener);
	
}
