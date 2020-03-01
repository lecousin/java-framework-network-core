package net.lecousin.framework.network.security;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.lecousin.framework.application.Application;
import net.lecousin.framework.io.util.DataUtil;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.network.IPAddressUtil;
import net.lecousin.framework.serialization.annotations.TypeSerializer;

/** Black list of IP addresses. */
public class IPBlackList implements NetworkSecurityFeature {
	
	/** Plug-in class. */
	public static class Plugin implements NetworkSecurityPlugin {

		@Override
		public Class<?> getConfigurationClass() {
			return Config.class;
		}

		@Override
		public NetworkSecurityFeature newInstance(Application app, Object configuration) {
			return new IPBlackList((Config)configuration, app.getLoggerFactory().getLogger(IPBlackList.class));
		}
		
	}
	
	/** Configuration. */
	@SuppressWarnings({"squid:ClassVariableVisibilityCheck", "squid:S1319"})
	public static class Config {
		
		public ArrayList<Category> category;
		
		/** Category of black list. */
		public static class Category {
			public String name;
			
			public ArrayList<IP> ipv4 = new ArrayList<>();
			public ArrayList<IP> ipv6 = new ArrayList<>();
		}
		
		/** IP address. */
		@TypeSerializer(IPAddressUtil.IPSerializer.class)
		public static class IP {
			public byte[] ip;
			public long expiration;
		}
		
	}

	private Logger logger;
	private ArrayList<Category> categories = new ArrayList<>();
	private boolean updated = false;

	private static class Category {
		private String name;
		private Map<Integer,Long> ipv4 = new HashMap<>(50);
		private Map<Long,Map<Long,Long>> ipv6 = new HashMap<>(50);
	}
	
	private IPBlackList(Config cfg, Logger logger) {
		this.logger = logger;
		if (cfg != null && cfg.category != null)
			for (Config.Category cfgCat : cfg.category) {
				Category cat = new Category();
				categories.add(cat);
				cat.name = cfgCat.name;
				if (cfgCat.ipv4 != null)
					for (Config.IP ip : cfgCat.ipv4) {
						int address  = ip.ip[3] & 0xFF;
						address |= ((ip.ip[2] << 8) & 0xFF00);
						address |= ((ip.ip[1] << 16) & 0xFF0000);
						address |= ((ip.ip[0] << 24) & 0xFF000000);
						cat.ipv4.put(Integer.valueOf(address), Long.valueOf(ip.expiration));
					}
				if (cfgCat.ipv6 != null)
					for (Config.IP ip : cfgCat.ipv6) {
						Long ip1 = Long.valueOf(DataUtil.Read64.LE.read(ip.ip, 0));
						Long ip2 = Long.valueOf(DataUtil.Read64.LE.read(ip.ip, 8));
						Map<Long,Long> m = cat.ipv6.get(ip1);
						if (m == null) {
							m = new HashMap<>(20);
							cat.ipv6.put(ip1, m);
						}
						m.put(ip2, Long.valueOf(ip.expiration));
					}
			}
	}
	
	@Override
	public Object getConfigurationIfChanged() {
		Config cfg = new Config();
		cfg.category = new ArrayList<>();
		synchronized (this) {
			if (!updated)
				return null;
			for (Category mcat : categories) {
				Config.Category cat = new Config.Category();
				cfg.category.add(cat);
				cat.name = mcat.name;
				for (Map.Entry<Integer,Long> e : mcat.ipv4.entrySet()) {
					Config.IP ip = new Config.IP();
			        ip.ip = new byte[4];
			        int address = e.getKey().intValue();
			        ip.ip[0] = (byte) ((address >>> 24) & 0xFF);
			        ip.ip[1] = (byte) ((address >>> 16) & 0xFF);
			        ip.ip[2] = (byte) ((address >>> 8) & 0xFF);
			        ip.ip[3] = (byte) (address & 0xFF);
			        ip.expiration = e.getValue().longValue();
			        cat.ipv4.add(ip);
				}
				for (Map.Entry<Long, Map<Long, Long>> e1 : mcat.ipv6.entrySet()) {
					for (Map.Entry<Long, Long> e2 : e1.getValue().entrySet()) {
						Config.IP ip = new Config.IP();
				        ip.ip = new byte[16];
						DataUtil.Write64.LE.write(ip.ip, 0, e1.getKey().longValue());
						DataUtil.Write64.LE.write(ip.ip, 8, e2.getKey().longValue());
						ip.expiration = e2.getValue().longValue();
						cat.ipv6.add(ip);
					}
				}
			}
			updated = true;
		}
		return cfg;
	}
	
	/** Return true if the given address can be accepted for a connection. */
	public boolean acceptAddress(InetAddress address) {
		if (address instanceof Inet4Address)
			return acceptAddress((Inet4Address)address);
		if (address instanceof Inet6Address)
			return acceptAddress((Inet6Address)address);
		return true;
	}
	
	/** Return true if the given address can be accepted for a connection. */
	public boolean acceptAddress(Inet4Address address) {
		Integer ip = Integer.valueOf(address.hashCode());
		synchronized (this) {
			for (Category cat : categories) {
				Long exp = cat.ipv4.get(ip);
				if (exp == null)
					continue;
				if (exp.longValue() < System.currentTimeMillis()) {
					cat.ipv4.remove(ip);
					updated = true;
					continue;
				}
				return false;
			}
		}
		return true;
	}
	
	/** Return true if the given address can be accepted for a connection. */
	public boolean acceptAddress(Inet6Address address) {
		byte[] ip = address.getAddress();
		Long ip1 = Long.valueOf(DataUtil.Read64.LE.read(ip, 0));
		Long ip2 = Long.valueOf(DataUtil.Read64.LE.read(ip, 8));
		synchronized (this) {
			for (Category cat : categories) {
				Map<Long,Long> map = cat.ipv6.get(ip1);
				if (map == null)
					continue;
				Long exp = map.get(ip2);
				if (exp == null)
					continue;
				if (exp.longValue() < System.currentTimeMillis()) {
					map.remove(ip2);
					if (map.isEmpty())
						cat.ipv6.remove(ip1);
					updated = true;
					continue;
				}
				return false;
			}
		}
		return true;
	}
	
	/** Add the given address to the black list. */
	public void blacklist(String category, InetAddress address, long delay) {
		Long expiration = Long.valueOf(System.currentTimeMillis() + delay);
		synchronized (this) {
			Category cat = null;
			for (Category c : categories)
				if (c.name.contentEquals(category)) {
					cat = c;
					break;
				}
			if (cat == null) {
				cat = new Category();
				cat.name = category;
				categories.add(cat);
			}
			if (address instanceof Inet4Address) {
				Integer ip = Integer.valueOf(((Inet4Address)address).hashCode());
				Long exp = cat.ipv4.get(ip);
				if (exp == null || exp.longValue() < expiration.longValue()) {
					cat.ipv4.put(ip, expiration);
					updated = true;
					logger.info("IPv4 black listed in category " + category + ": " + address);
				}
			} else if (address instanceof Inet6Address) {
				byte[] ip = address.getAddress();
				Long ip1 = Long.valueOf(DataUtil.Read64.LE.read(ip, 0));
				Long ip2 = Long.valueOf(DataUtil.Read64.LE.read(ip, 8));
				Map<Long,Long> map = cat.ipv6.get(ip1);
				if (map == null) {
					map = new HashMap<>(20);
					cat.ipv6.put(ip1, map);
				}
				Long exp = map.get(ip2);
				if (exp == null || exp.longValue() < expiration.longValue()) {
					map.put(ip2, expiration);
					updated = true;
					logger.info("IPv6 black listed in category " + category + ": " + address);
				}
			} else {
				logger.error("Unknown address type to black list: " + address);
			}
		}
	}

	/** Remove the given address from the black list. */
	public void unblacklist(String category, InetAddress address) {
		synchronized (this) {
			Category cat = null;
			for (Category c : categories)
				if (c.name.contentEquals(category)) {
					cat = c;
					break;
				}
			if (cat == null) return;
			if (address instanceof Inet4Address) {
				Integer ip = Integer.valueOf(((Inet4Address)address).hashCode());
				if (cat.ipv4.remove(ip) != null) {
					updated = true;
					logger.info("IPv4 address removed from black list in category " + category + ": " + address);
				}
				return;
			}
			if (address instanceof Inet6Address) {
				byte[] ip = address.getAddress();
				Long ip1 = Long.valueOf(DataUtil.Read64.LE.read(ip, 0));
				Long ip2 = Long.valueOf(DataUtil.Read64.LE.read(ip, 8));
				Map<Long,Long> map = cat.ipv6.get(ip1);
				if (map == null) return;
				if (map.remove(ip2) != null) {
					if (map.isEmpty())
						cat.ipv6.remove(ip1);
					updated = true;
					logger.info("IPv4 address removed from black list in category " + category + ": " + address);
				}
			} else {
				logger.error("Unknown address type to remove from black list: " + address);
			}
		}
	}
	
	/** Remove all black listed IPs in the given category. */
	public void clearCategory(String category) {
		synchronized (this) {
			for (Category c : categories)
				if (c.name.contentEquals(category)) {
					c.ipv4.clear();
					c.ipv6.clear();
					updated = true;
					logger.info("All IPs removed from black list in category " + category);
					break;
				}
		}
	}
	
	/** Remove all black listed IPs. */
	public void clearAll() {
		synchronized (this) {
			categories.clear();
			updated = true;
			logger.info("All IPs removed from black list");
		}
	}
	
	@Override
	public void clean() {
		synchronized (this) {
			long now = System.currentTimeMillis();
			for (Category cat : categories) {
				for (Iterator<Long> it = cat.ipv4.values().iterator(); it.hasNext(); ) {
					if (it.next().longValue() < now) {
						it.remove();
						updated = true;
					}
				}
				for (Iterator<Map<Long, Long>> it1 = cat.ipv6.values().iterator(); it1.hasNext(); ) {
					Map<Long, Long> e1 = it1.next();
					for (Iterator<Long> it2 = e1.values().iterator(); it2.hasNext(); ) {
						if (it2.next().longValue() < now) {
							it2.remove();
							updated = true;
						}
					}
					if (e1.isEmpty())
						it1.remove();
				}
			}
		}
	}
	
}
