package net.lecousin.framework.network.cache;

import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.application.Version;
import net.lecousin.framework.util.AbstractAttributesContainer;

public class HostProtocol extends AbstractAttributesContainer {

	private String name;
	private String alpn;
	private List<Version> versions = new LinkedList<>();
	private String implementation;
	
	public HostProtocol(String name, String alpn, List<Version> versions, String implementation) {
		this.name = name;
		this.alpn = alpn;
		if (versions != null)
			this.versions.addAll(versions);
		this.implementation = implementation;
	}
	
	public HostProtocol(String name) {
		this(name, null, null, null);
	}
	
	public String getName() {
		return name;
	}
	
	public String getAlpn() {
		return alpn;
	}

	public void setAlpn(String alpn) {
		this.alpn = alpn;
	}
	
	public List<Version> getVersions() {
		return versions;
	}
	
	public void addVersion(Version v) {
		versions.add(v);
	}

	public String getImplementation() {
		return implementation;
	}

	public void setImplementation(String implementation) {
		this.implementation = implementation;
	}
	
}
