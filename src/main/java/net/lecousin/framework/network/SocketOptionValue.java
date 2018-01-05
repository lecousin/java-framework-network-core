package net.lecousin.framework.network;

import java.net.SocketOption;

public class SocketOptionValue<T> {

	public SocketOptionValue(SocketOption<T> option, T value) {
		this.option = option;
		this.value = value;
	}
	
	private SocketOption<T> option;
	private T value;
	
	public SocketOption<T> getOption() { return option; }
	
	public T getValue() { return value; }
	
	public void setValue(T value) {
		this.value = value;
	}
	
}
