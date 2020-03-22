package net.lecousin.framework.network.cache;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.concurrent.async.ReadWriteLockPoint;
import net.lecousin.framework.network.AbstractAttributesContainer;

public class HostPortKnowledge extends AbstractAttributesContainer {

	private List<HostProtocol> protocols = new LinkedList<>();
	private long lastUsed = System.currentTimeMillis();
	private ReadWriteLockPoint lock = new ReadWriteLockPoint();
	
	public List<HostProtocol> getProtocols() {
		ArrayList<HostProtocol> list;
		lock.startRead();
		list = new ArrayList<>(protocols);
		lock.endRead();
		return list;
	}
	
	public HostProtocol getProtocolByName(String name) {
		try {
			lock.startRead();
			for (HostProtocol p : protocols)
				if (name.equals(p.getName()))
					return p;
			return null;
		} finally {
			lock.endRead();
		}
	}
	
	public HostProtocol getProtocolByAlpn(String alpn) {
		try {
			lock.startRead();
			for (HostProtocol p : protocols)
				if (alpn.equals(p.getAlpn()))
					return p;
			return null;
		} finally {
			lock.endRead();
		}
	}
	
	public void addProtocol(HostProtocol protocol) {
		lock.startWrite();
		protocols.add(protocol);
		lock.endWrite();
	}
	
	public HostProtocol getOrCreateProtocolByName(String name) {
		lock.startWrite();
		try {
			for (HostProtocol p : protocols)
				if (name.equals(p.getName()))
					return p;
			HostProtocol p = new HostProtocol(name);
			protocols.add(p);
			return p;
		} finally {
			lock.endWrite();
		}
	}
	
	public void used() {
		lastUsed = System.currentTimeMillis();
	}
	
	public long getLastUsed() {
		return lastUsed;
	}
	
}
