package com.lkb.ipservice.util;

import java.util.concurrent.atomic.AtomicReference;

public class SpinLock {

	private AtomicReference<Thread> ref = new AtomicReference<Thread>(null);

	public void lock() {
		Thread current_thread = Thread.currentThread();
		while (ref.compareAndSet(null, current_thread))
			;
	}

	public void unLock() {
		Thread current_thread = Thread.currentThread();
		ref.compareAndSet(current_thread, null);
	}
}
