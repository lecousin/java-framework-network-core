package net.lecousin.framework.network.security;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.lecousin.framework.application.Application;
import net.lecousin.framework.io.serialization.annotations.TypeSerializer;
import net.lecousin.framework.io.util.DataUtil;
import net.lecousin.framework.network.NetUtil;
import net.lecousin.framework.network.TCPRemote;

/**
 * Store a possible brute force attempt (in other word a wrong password).
 */
public class BruteForceAttempt implements NetworkSecurityFeature {
	
	public static final String PROPERTY_DELAY_KEEP_ATTEMPT = "brute_force_attack.delay";
	public static final String PROPERTY_MAX_ATTEMPTS = "brute_force_attack.max_attempts";
	public static final String PROPERTY_BLACK_LIST_DELAY = "brute_force_attack.black_list_delay";
	
	/** Plug-in class. */
	public static class Plugin implements NetworkSecurityPlugin {

		@Override
		public Class<?> getConfigurationClass() {
			return Config.class;
		}

		@Override
		public NetworkSecurityFeature newInstance(Application app, Object configuration) {
			return new BruteForceAttempt(app, (Config)configuration);
		}
		
	}

	public static final String IP_BLACKLIST_CATEGORY = "Brute Force";

	/** Configuration. */
	@SuppressWarnings({"squid:ClassVariableVisibilityCheck", "squid:S1319"})
	public static class Config {
		
		public ArrayList<Attempt> attempt;
		
		/** Saved attempt. */
		@TypeSerializer(NetUtil.IPSerializer.class)
		public static class Attempt {
			public byte[] ip;
			public String functionality;
			public String lastValue;
			public long lastTime;
			public int attempts;
		}
		
	}
	

	private NetworkSecurity security;
	private Map<String, Map<Integer, Attempt>> ipv4 = new HashMap<>(5);
	private Map<String, Map<Long, Map<Long, Attempt>>> ipv6 = new HashMap<>(5);
	private boolean updated = false;

	private long attackDelay;
	private int attackMaxAttempts;
	private long attackBlackListTime;
	
	private static class Attempt {
		private String lastValue;
		private long lastTime;
		private int attempts;
	}
		
	private BruteForceAttempt(Application app, Config cfg) {
		try { attackDelay = Long.parseLong(app.getProperty(PROPERTY_DELAY_KEEP_ATTEMPT)); }
		catch (Exception e) { attackDelay = 10L * 60 * 1000; } // default to 10 minutes

		try { attackMaxAttempts = Integer.parseInt(app.getProperty(PROPERTY_MAX_ATTEMPTS)); }
		catch (Exception e) { attackMaxAttempts = 10; } // default to 10
		
		try { attackBlackListTime = Long.parseLong(app.getProperty(PROPERTY_BLACK_LIST_DELAY)); }
		catch (Exception e) { attackBlackListTime = 15L * 60 * 1000; } // default to 15 minutes
		
		security = NetworkSecurity.get(app);
		
		if (cfg != null && cfg.attempt != null)
			for (Config.Attempt ca : cfg.attempt) {
				if (ca.ip == null) continue;
				if (ca.ip.length == 4) {
					Map<Integer, Attempt> m = ipv4.get(ca.functionality);
					if (m == null) {
						m = new HashMap<>(5);
						ipv4.put(ca.functionality, m);
					}
					Attempt a = new Attempt();
					a.lastTime = ca.lastTime;
					a.lastValue = ca.lastValue;
					a.attempts = ca.attempts;
					m.put(Integer.valueOf(DataUtil.readIntegerLittleEndian(ca.ip, 0)), a);
				} else {
					Map<Long, Map<Long, Attempt>> m = ipv6.get(ca.functionality);
					if (m == null) {
						m = new HashMap<>(5);
						ipv6.put(ca.functionality, m);
					}
					Long ip1 = Long.valueOf(DataUtil.readLongLittleEndian(ca.ip, 0));
					Map<Long, Attempt> m2 = m.get(ip1);
					if (m2 == null) {
						m2 = new HashMap<>(20);
						m.put(ip1, m2);
					}
					Attempt a = new Attempt();
					a.lastTime = ca.lastTime;
					a.lastValue = ca.lastValue;
					a.attempts = ca.attempts;
					Long ip2 = Long.valueOf(DataUtil.readLongLittleEndian(ca.ip, 8));
					m2.put(ip2, a);
				}
			}
	}
	
	/** Signal an attempt from the given address. */
	public void attempt(InetAddress address, String functionality, String value) {
		boolean bl = false;
		synchronized (this) {
			updated = true;
			if (address instanceof Inet4Address) {
				Integer ip = Integer.valueOf(address.hashCode());
				Map<Integer, Attempt> map = ipv4.get(functionality);
				if (map == null) {
					map = new HashMap<>(5);
					ipv4.put(functionality, map);
				}
				Attempt a = attempt(map, ip, value);
				if (a == null)
					return;
				if (++a.attempts >= attackMaxAttempts)
					bl = true;
			}
			if (address instanceof Inet6Address) {
				byte[] ip = address.getAddress();
				Map<Long, Map<Long, Attempt>> map = ipv6.get(functionality);
				if (map == null) {
					map = new HashMap<>(5);
					ipv6.put(functionality, map);
				}
				Long ip1 = Long.valueOf(DataUtil.readLongLittleEndian(ip, 0));
				Map<Long, Attempt> mapIp = map.get(ip1);
				if (mapIp == null) {
					mapIp = new HashMap<>(20);
					map.put(ip1, mapIp);
				}
				Long ip2 = Long.valueOf(DataUtil.readLongLittleEndian(ip, 8));
				Attempt a = attempt(mapIp, ip2, value);
				if (a == null)
					return;
				if (++a.attempts >= attackMaxAttempts)
					bl = true;
			}
		}
		if (bl)
			security.getFeature(IPBlackList.class).blacklist(IP_BLACKLIST_CATEGORY, address, attackBlackListTime);
	}
	
	private <T> Attempt attempt(Map<T, Attempt> map, T ip, String value) {
		Attempt a = map.get(ip);
		if (a == null) {
			a = new Attempt();
			a.attempts = 0;
			a.lastTime = System.currentTimeMillis();
			a.lastValue = value;
			map.put(ip, a);
			return a;
		}
		if (System.currentTimeMillis() - a.lastTime > attackDelay) {
			a.lastTime = System.currentTimeMillis();
			a.lastValue = value;
			a.attempts = 0;
			return a;
		}

		a.lastTime = System.currentTimeMillis();
		if (a.lastValue.equals(value))
			return null;
		a.lastValue = value;
		return a;
	}
	
	/** Signal an attempt from the given client. */
	public void attempt(TCPRemote client, String functionality, String value) {
		try {
			attempt(((InetSocketAddress)client.getRemoteAddress()).getAddress(), functionality, value);
		} catch (Exception e) {
			// ignore
		}
	}
	
	@Override
	public void clean() {
		synchronized (this) {
			for (Iterator<Map<Integer, Attempt>> it1 = ipv4.values().iterator(); it1.hasNext(); ) {
				Map<Integer, Attempt> m = it1.next();
				for (Iterator<Attempt> it2 = m.values().iterator(); it2.hasNext(); ) {
					if (System.currentTimeMillis() - it2.next().lastTime > attackDelay) {
						it2.remove();
						updated = true;
					}
				}
				if (m.isEmpty())
					it1.remove();
			}
			
			for (Iterator<Map<Long, Map<Long, Attempt>>> it1 = ipv6.values().iterator(); it1.hasNext(); ) {
				Map<Long, Map<Long, Attempt>> m1 = it1.next();
				for (Iterator<Map<Long, Attempt>> it2 = m1.values().iterator(); it2.hasNext(); ) {
					Map<Long, Attempt> m2 = it2.next();
					for (Iterator<Attempt> it3 = m2.values().iterator(); it3.hasNext(); ) {
						if (System.currentTimeMillis() - it3.next().lastTime > attackDelay) {
							it3.remove();
							updated = true;
						}
					}
					if (m2.isEmpty())
						it2.remove();
				}
				if (m1.isEmpty())
					it1.remove();
			}
		}
	}
	
	@Override
	public Object getConfigurationIfChanged() {
		Config cfg = new Config();
		cfg.attempt = new ArrayList<>();
		synchronized (this) {
			if (!updated)
				return null;
			updated = false;
			for (Map.Entry<String, Map<Integer, Attempt>> e1 : ipv4.entrySet()) {
				String func = e1.getKey();
				for (Map.Entry<Integer, Attempt> e2 : e1.getValue().entrySet()) {
					Config.Attempt a = new Config.Attempt();
					a.functionality = func;
					a.ip = new byte[4];
					DataUtil.writeIntegerLittleEndian(a.ip, 0, e2.getKey().intValue());
					Attempt ma = e2.getValue();
					a.lastValue = ma.lastValue;
					a.lastTime = ma.lastTime;
					a.attempts = ma.attempts;
					cfg.attempt.add(a);
				}
			}
			for (Map.Entry<String, Map<Long, Map<Long, Attempt>>> e1 : ipv6.entrySet()) {
				String func = e1.getKey();
				for (Map.Entry<Long, Map<Long, Attempt>> e2 : e1.getValue().entrySet()) {
					Long ip1 = e2.getKey();
					for (Map.Entry<Long, Attempt> e3 : e2.getValue().entrySet()) {
						Config.Attempt a = new Config.Attempt();
						a.functionality = func;
						a.ip = new byte[16];
						Long ip2 = e3.getKey();
						DataUtil.writeLongLittleEndian(a.ip, 0, ip1.longValue());
						DataUtil.writeLongLittleEndian(a.ip, 8, ip2.longValue());
						Attempt ma = e3.getValue();
						a.lastValue = ma.lastValue;
						a.lastTime = ma.lastTime;
						a.attempts = ma.attempts;
						cfg.attempt.add(a);
					}
				}
			}
		}
		return cfg;
	}
	
}
