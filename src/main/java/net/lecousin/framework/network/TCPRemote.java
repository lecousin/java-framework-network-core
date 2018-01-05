package net.lecousin.framework.network;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.synch.ISynchronizationPoint;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.util.Provider;

public interface TCPRemote {
	
	SocketAddress getLocalAddress() throws IOException;
	
	SocketAddress getRemoteAddress() throws IOException;

	ISynchronizationPoint<IOException> send(ByteBuffer data);
	
	/** The data will be sent as soon as possible, however a subsequent call to this method will override
	 * the previous one.
	 * The data will be sent after all data sent through the send method is finished.
	 * It means that if data is sent again using the send method before this data is sent,
	 * this data will be send after the new send is finished.
	 */
	void newDataToSendWhenPossible(Provider<ByteBuffer> dataProvider, SynchronizationPoint<IOException> sp);
	
	void onclosed(Runnable listener);
	
}
