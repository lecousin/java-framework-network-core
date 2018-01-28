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

/** Black list of IP addresses. */
public class IPBlackList {

	public static final String CATEGORY_BRUTE_FORCE_ATTACK = "Brute Force";

	static class Mapping {
	
		private ArrayList<Category> categories = new ArrayList<>();
	
		private static class Category {
			public String name;
			public Map<Integer,Long> ipv4 = new HashMap<>(50);
			public Map<Long,Map<Long,Long>> ipv6 = new HashMap<>(50);
		}
		
		Mapping(Config cfg) {
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
							Long ip1 = Long.valueOf(DataUtil.readLongLittleEndian(ip.ip, 0));
							Long ip2 = Long.valueOf(DataUtil.readLongLittleEndian(ip.ip, 8));
							Map<Long,Long> m = cat.ipv6.get(ip1);
							if (m == null) {
								m = new HashMap<>(20);
								cat.ipv6.put(ip1, m);
							}
							m.put(ip2, Long.valueOf(ip.expiration));
						}
				}
		}
		
		public boolean acceptAddress(InetAddress address) {
			if (address instanceof Inet4Address)
				return acceptAddress((Inet4Address)address);
			if (address instanceof Inet6Address)
				return acceptAddress((Inet6Address)address);
			return true;
		}
		
		public boolean acceptAddress(Inet4Address address) {
			Integer ip = Integer.valueOf(address.hashCode());
			synchronized (this) {
				for (Category cat : categories) {
					Long exp = cat.ipv4.get(ip);
					if (exp == null)
						continue;
					if (exp.longValue() < System.currentTimeMillis()) {
						cat.ipv4.remove(ip);
						NetworkSecurity.updated = true;
						continue;
					}
					return false;
				}
			}
			return true;
		}
		
		public boolean acceptAddress(Inet6Address address) {
			byte[] ip = address.getAddress();
			Long ip1 = Long.valueOf(DataUtil.readLongLittleEndian(ip, 0));
			Long ip2 = Long.valueOf(DataUtil.readLongLittleEndian(ip, 8));
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
						NetworkSecurity.updated = true;
						continue;
					}
					return false;
				}
			}
			return true;
		}
		
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
						NetworkSecurity.updated = true;
						return;
					}
				} else if (address instanceof Inet6Address) {
					byte[] ip = address.getAddress();
					Long ip1 = Long.valueOf(DataUtil.readLongLittleEndian(ip, 0));
					Long ip2 = Long.valueOf(DataUtil.readLongLittleEndian(ip, 8));
					Map<Long,Long> map = cat.ipv6.get(ip1);
					if (map == null) {
						map = new HashMap<>(20);
						cat.ipv6.put(ip1, map);
					}
					Long exp = map.get(ip2);
					if (exp == null || exp.longValue() < expiration.longValue()) {
						map.put(ip2, expiration);
						NetworkSecurity.updated = true;
						return;
					}
				}
			}
		}

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
					if (cat.ipv4.remove(ip) != null)
						NetworkSecurity.updated = true;
					return;
				}
				if (address instanceof Inet6Address) {
					byte[] ip = address.getAddress();
					Long ip1 = Long.valueOf(DataUtil.readLongLittleEndian(ip, 0));
					Long ip2 = Long.valueOf(DataUtil.readLongLittleEndian(ip, 8));
					Map<Long,Long> map = cat.ipv6.get(ip1);
					if (map == null) return;
					if (map.remove(ip2) != null) {
						if (map.isEmpty())
							cat.ipv6.remove(ip1);
						NetworkSecurity.updated = true;
						return;
					}
				}
			}
		}
		
		public void clean() {
			synchronized (this) {
				long now = System.currentTimeMillis();
				for (Category cat : categories) {
					for (Iterator<Long> it = cat.ipv4.values().iterator(); it.hasNext(); ) {
						if (it.next().longValue() < now) {
							it.remove();
							NetworkSecurity.updated = true;
						}
					}
					for (Iterator<Map<Long, Long>> it1 = cat.ipv6.values().iterator(); it1.hasNext(); ) {
						Map<Long, Long> e1 = it1.next();
						for (Iterator<Long> it2 = e1.values().iterator(); it2.hasNext(); ) {
							if (it2.next().longValue() < now) {
								it2.remove();
								NetworkSecurity.updated = true;
							}
						}
						if (e1.isEmpty())
							it1.remove();
					}
				}
			}
		}
		
	}
	
	/** Configuration. */
	public static class Config {
		
		public ArrayList<Category> category;
		
		/** Category of black list. */
		public static class Category {
			public String name;
			
			public ArrayList<IP> ipv4 = new ArrayList<>();
			public ArrayList<IP> ipv6 = new ArrayList<>();
		}
		
		/** IP address. */
		public static class IP {
			@TypeSerializer(NetUtil.IPSerializer.class)
			public byte[] ip;
			public long expiration;
		}
		
		/** Constructor. */
		public Config() {}
		
		Config(Mapping m) {
			category = new ArrayList<>();
			for (Mapping.Category mcat : m.categories) {
				Category cat = new Category();
				category.add(cat);
				cat.name = mcat.name;
				for (Map.Entry<Integer,Long> e : mcat.ipv4.entrySet()) {
					IP ip = new IP();
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
						IP ip = new IP();
				        ip.ip = new byte[16];
						DataUtil.writeLongLittleEndian(ip.ip, 0, e1.getKey().longValue());
						DataUtil.writeLongLittleEndian(ip.ip, 8, e2.getKey().longValue());
						ip.expiration = e2.getValue().longValue();
						cat.ipv6.add(ip);
					}
				}
			}
		}
		
	}
	
}
