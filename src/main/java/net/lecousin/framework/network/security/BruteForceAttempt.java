package net.lecousin.framework.network.security;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.lecousin.framework.io.serialization.annotations.TypeSerializer;
import net.lecousin.framework.io.util.DataUtil;
import net.lecousin.framework.network.NetUtil;

/**
 * Store a possible brute force attempt (in other word a wrong password).
 */
public class BruteForceAttempt {

	static class Mapping {
		
		private Map<String, Map<String, Map<Integer, Attempt>>> ipv4 = new HashMap<>(5);
		private Map<String, Map<String, Map<Long, Map<Long, Attempt>>>> ipv6 = new HashMap<>(5);

		public static class Attempt {
			public String lastValue;
			public long lastTime;
			public int attempts;
		}
		
		Mapping(Config cfg) {
			if (cfg != null && cfg.attempt != null)
				for (Config.Attempt ca : cfg.attempt) {
					if (ca.ip.length == 4) {
						Map<String, Map<Integer, Attempt>> m1 = ipv4.get(ca.application);
						if (m1 == null) {
							m1 = new HashMap<>(5);
							ipv4.put(ca.application, m1);
						}
						Map<Integer, Attempt> m2 = m1.get(ca.functionality);
						if (m2 == null) {
							m2 = new HashMap<>(50);
							m1.put(ca.functionality, m2);
						}
						Attempt a = new Attempt();
						a.lastTime = ca.lastTime;
						a.lastValue = ca.lastValue;
						a.attempts = ca.attempts;
						m2.put(Integer.valueOf(DataUtil.readIntegerLittleEndian(ca.ip, 0)), a);
					} else {
						Map<String, Map<Long, Map<Long, Attempt>>> m1 = ipv6.get(ca.application);
						if (m1 == null) {
							m1 = new HashMap<>(5);
							ipv6.put(ca.application, m1);
						}
						Map<Long, Map<Long, Attempt>> m2 = m1.get(ca.functionality);
						if (m2 == null) {
							m2 = new HashMap<>(50);
							m1.put(ca.functionality, m2);
						}
						Long ip1 = Long.valueOf(DataUtil.readLongLittleEndian(ca.ip, 0));
						Map<Long, Attempt> m3 = m2.get(ip1);
						if (m3 == null) {
							m3 = new HashMap<>(20);
							m2.put(ip1, m3);;
						}
						Attempt a = new Attempt();
						a.lastTime = ca.lastTime;
						a.lastValue = ca.lastValue;
						a.attempts = ca.attempts;
						Long ip2 = Long.valueOf(DataUtil.readLongLittleEndian(ca.ip, 8));
						m3.put(ip2, a);
					}
				}
		}
		
		private static final long BRUTE_FORCE_ATTACK_DELAY = 10 * 60 * 1000; // after 10 minutes, previous attempts expire
		private static final int BRUTE_FORCE_ATTACK_MAX_ATTEMPTS = 10; // we allow 10 attempts before to black list
		private static final long BRUTE_FORCE_ATTACK_BLACK_LIST_TIME = 15 * 60 * 1000; // black list for 15 minutes
		
		public void attempt(InetAddress address, String application, String functionality, String value) {
			boolean bl = false;
			synchronized (this) {
				NetworkSecurity.updated = true;
				if (address instanceof Inet4Address) {
					Integer ip = Integer.valueOf(address.hashCode());
					Map<String, Map<Integer, Attempt>> map1 = ipv4.get(application);
					if (map1 == null) {
						map1 = new HashMap<>(5);
						ipv4.put(application, map1);
					}
					Map<Integer, Attempt> map2 = map1.get(functionality);
					if (map2 == null) {
						map2 = new HashMap<>(50);
						map1.put(functionality, map2);
					}
					Attempt a = map2.get(ip);
					if (a == null) {
						a = new Attempt();
						a.attempts = 1;
						a.lastTime = System.currentTimeMillis();
						a.lastValue = value;
						map2.put(ip, a);
						return;
					}
					if (System.currentTimeMillis() - a.lastTime > BRUTE_FORCE_ATTACK_DELAY) {
						a.lastTime = System.currentTimeMillis();
						a.lastValue = value;
						a.attempts = 1;
						return;
					}

					a.lastTime = System.currentTimeMillis();
					if (a.lastValue.equals(value))
						return;
					a.lastValue = value;
					if (++a.attempts >= BRUTE_FORCE_ATTACK_MAX_ATTEMPTS)
						bl = true;
				}
				if (address instanceof Inet6Address) {
					byte[] ip = address.getAddress();
					Map<String, Map<Long, Map<Long, Attempt>>> map1 = ipv6.get(application);
					if (map1 == null) {
						map1 = new HashMap<>(5);
						ipv6.put(application, map1);
					}
					Map<Long, Map<Long, Attempt>> map2 = map1.get(functionality);
					if (map2 == null) {
						map2 = new HashMap<>(50);
						map1.put(functionality, map2);
					}
					Long ip1 = Long.valueOf(DataUtil.readLongLittleEndian(ip, 0));
					Map<Long, Attempt> map3 = map2.get(ip1);
					if (map3 == null) {
						map3 = new HashMap<>(20);
						map2.put(ip1, map3);
					}
					Long ip2 = Long.valueOf(DataUtil.readLongLittleEndian(ip, 8));
					Attempt a = map3.get(ip2);
					if (a == null) {
						a = new Attempt();
						a.attempts = 1;
						a.lastTime = System.currentTimeMillis();
						a.lastValue = value;
						map3.put(ip2, a);
						return;
					}
					if (System.currentTimeMillis() - a.lastTime > BRUTE_FORCE_ATTACK_DELAY) {
						a.lastTime = System.currentTimeMillis();
						a.lastValue = value;
						a.attempts = 1;
						return;
					}
					a.lastTime = System.currentTimeMillis();
					if (a.lastValue.equals(value))
						return;
					a.lastValue = value;
					if (++a.attempts >= BRUTE_FORCE_ATTACK_MAX_ATTEMPTS)
						bl = true;
				}
			}
			if (bl)
				NetworkSecurity.blacklist(IPBlackList.CATEGORY_BRUTE_FORCE_ATTACK, address, BRUTE_FORCE_ATTACK_BLACK_LIST_TIME);
		}
		
		public void clean() {
			synchronized (this) {
				for (Iterator<Map<String, Map<Integer, Attempt>>> it1 = ipv4.values().iterator(); it1.hasNext(); ) {
					Map<String, Map<Integer, Attempt>> m1 = it1.next();
					for (Iterator<Map<Integer, Attempt>> it2 = m1.values().iterator(); it2.hasNext(); ) {
						Map<Integer, Attempt> m2 = it2.next();
						for (Iterator<Attempt> it3 = m2.values().iterator(); it3.hasNext(); ) {
							if (System.currentTimeMillis() - it3.next().lastTime > BRUTE_FORCE_ATTACK_DELAY) {
								it3.remove();
								NetworkSecurity.updated = true;
							}
						}
						if (m2.isEmpty())
							it2.remove();
					}
					if (m1.isEmpty())
						it1.remove();
				}
				
				for (Iterator<Map<String, Map<Long, Map<Long, Attempt>>>> it1 = ipv6.values().iterator(); it1.hasNext(); ) {
					Map<String, Map<Long, Map<Long, Attempt>>> m1 = it1.next();
					for (Iterator<Map<Long, Map<Long, Attempt>>> it2 = m1.values().iterator(); it2.hasNext(); ) {
						Map<Long, Map<Long, Attempt>> m2 = it2.next();
						for (Iterator<Map<Long, Attempt>> it3 = m2.values().iterator(); it3.hasNext(); ) {
							Map<Long, Attempt> m3 = it3.next();
							for (Iterator<Attempt> it4 = m3.values().iterator(); it4.hasNext(); ) {
								if (System.currentTimeMillis() - it4.next().lastTime > BRUTE_FORCE_ATTACK_DELAY) {
									it4.remove();
									NetworkSecurity.updated = true;
								}
							}
							if (m3.isEmpty())
								it3.remove();
						}
						if (m2.isEmpty())
							it2.remove();
					}
					if (m1.isEmpty())
						it1.remove();
				}
			}
		}
		
	}
	
	/** Configuration. */
	public static class Config {
		
		public ArrayList<Attempt> attempt;
		
		/** Saved attempt. */
		public static class Attempt {
			@TypeSerializer(NetUtil.IPSerializer.class)
			public byte[] ip;
			public String application;
			public String functionality;
			public String lastValue;
			public long lastTime;
			public int attempts;
		}
		
		/** Constructor. */
		public Config() {}
		
		Config(Mapping m) {
			attempt = new ArrayList<>();
			for (Map.Entry<String, Map<String, Map<Integer, Mapping.Attempt>>> e1 : m.ipv4.entrySet()) {
				String app = e1.getKey();
				for (Map.Entry<String, Map<Integer, Mapping.Attempt>> e2 : e1.getValue().entrySet()) {
					String func = e2.getKey();
					for (Map.Entry<Integer, Mapping.Attempt> e3 : e2.getValue().entrySet()) {
						Attempt a = new Attempt();
						a.application = app;
						a.functionality = func;
						a.ip = new byte[4];
						DataUtil.writeIntegerLittleEndian(a.ip, 0, e3.getKey().intValue());
						Mapping.Attempt ma = e3.getValue();
						a.lastValue = ma.lastValue;
						a.lastTime = ma.lastTime;
						a.attempts = ma.attempts;
						attempt.add(a);
					}
				}
			}
			for (Map.Entry<String, Map<String, Map<Long, Map<Long, Mapping.Attempt>>>> e1 : m.ipv6.entrySet()) {
				String app = e1.getKey();
				for (Map.Entry<String, Map<Long, Map<Long, Mapping.Attempt>>> e2 : e1.getValue().entrySet()) {
					String func = e2.getKey();
					for (Map.Entry<Long, Map<Long, Mapping.Attempt>> e3 : e2.getValue().entrySet()) {
						Long ip1 = e3.getKey();
						for (Map.Entry<Long, Mapping.Attempt> e4 : e3.getValue().entrySet()) {
							Attempt a = new Attempt();
							a.application = app;
							a.functionality = func;
							a.ip = new byte[16];
							Long ip2 = e4.getKey();
							DataUtil.writeLongLittleEndian(a.ip, 0, ip1.longValue());
							DataUtil.writeLongLittleEndian(a.ip, 8, ip2.longValue());
							Mapping.Attempt ma = e4.getValue();
							a.lastValue = ma.lastValue;
							a.lastTime = ma.lastTime;
							a.attempts = ma.attempts;
							attempt.add(a);
						}
					}
				}
			}
		}
		
	}
	
}
