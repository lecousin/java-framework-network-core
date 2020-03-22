package net.lecousin.framework.network.cache;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import net.lecousin.framework.memory.IMemoryManageable;
import net.lecousin.framework.memory.MemoryManager;

public class HostKnowledgeCache implements IMemoryManageable {

	private static HostKnowledgeCache instance = new HostKnowledgeCache();
	
	public static HostKnowledgeCache get() {
		return instance;
	}

	private Map<InetAddress, Map<Integer, HostPortKnowledge>> cache = new HashMap<>();
	
	private HostKnowledgeCache() {
		MemoryManager.register(this);
	}
	
	public Collection<Integer> getKnownPorts(InetAddress ip) {
		synchronized (cache) {
			Map<Integer, HostPortKnowledge> map = cache.get(ip);
			if (map == null)
				return Collections.emptyList();
			return map.keySet();
		}
	}
	
	public HostPortKnowledge getKnowledge(InetAddress ip, int port) {
		synchronized (cache) {
			Map<Integer, HostPortKnowledge> map = cache.get(ip);
			if (map == null)
				return null;
			return map.get(Integer.valueOf(port));
		}
	}
	
	public HostPortKnowledge getKnowledge(InetSocketAddress address) {
		return getKnowledge(address.getAddress(), address.getPort());
	}
	
	public HostPortKnowledge getOrCreateKnowledge(InetAddress ip, int port) {
		synchronized (cache) {
			Map<Integer, HostPortKnowledge> map = cache.get(ip);
			if (map == null) {
				map = new HashMap<>(5);
				cache.put(ip, map);
			}
			HostPortKnowledge k = map.get(Integer.valueOf(port));
			if (k == null) {
				k = new HostPortKnowledge();
				map.put(Integer.valueOf(port), k);
			}
			return k;
		}
	}
	
	public HostPortKnowledge getOrCreateKnowledge(InetSocketAddress address) {
		return getOrCreateKnowledge(address.getAddress(), address.getPort());
	}

	@Override
	public String getDescription() {
		return "Host knowledge";
	}

	@Override
	public List<String> getItemsDescription() {
		List<String> list = new LinkedList<>();
		synchronized (cache) {
			for (Map.Entry<InetAddress, Map<Integer, HostPortKnowledge>> ipEntry : cache.entrySet()) {
				list.add(ipEntry.getKey().toString() + ": " + ipEntry.getValue().size() + " known ports");
			}
		}
		return list;
	}

	@Override
	public void freeMemory(FreeMemoryLevel level) {
		long minUsage = System.currentTimeMillis();
		int nbToRemove;
		switch (level) {
		default:
		case EXPIRED_ONLY:
			minUsage -= 2 * 60 * 60 * 1000;
			nbToRemove = 25;
			break;
		case LOW:
			minUsage -= 30 * 60 * 1000;
			nbToRemove = 50;
			break;
		case MEDIUM:
			minUsage -= 5 * 60 * 1000;
			nbToRemove = 200;
			break;
		case URGENT:
			minUsage -= 30 * 1000;
			nbToRemove = 1000;
			break;
		}
		ArrayList<InetAddress> ipToRemove = new ArrayList<>(nbToRemove);
		synchronized (cache) {
			for (Map.Entry<InetAddress, Map<Integer, HostPortKnowledge>> ipEntry : cache.entrySet()) {
				boolean recent = false;
				for (HostPortKnowledge port : ipEntry.getValue().values())
					if (port.getLastUsed() > minUsage) {
						recent = true;
						break;
					}
				if (!recent) {
					ipToRemove.add(ipEntry.getKey());
					if (ipToRemove.size() == nbToRemove)
						break;
				}
			}
			for (InetAddress ip : ipToRemove)
				cache.remove(ip);
		}
	}
	
}
