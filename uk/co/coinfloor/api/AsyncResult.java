package uk.co.coinfloor.api;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class AsyncResult<V> implements Future<V>, Callback<V> {

	private int state;
	private Object result;

	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		return false;
	}

	@Override
	public boolean isCancelled() {
		return false;
	}

	@Override
	public synchronized boolean isDone() {
		return state != 0;
	}

	@Override
	@SuppressWarnings("unchecked")
	public synchronized V get() throws InterruptedException, ExecutionException {
		while (state == 0) {
			wait();
		}
		if (state < 0) {
			throw new ExecutionException((Throwable) result);
		}
		return (V) result;
	}

	@Override
	@SuppressWarnings("unchecked")
	public synchronized V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
		if (state == 0) {
			for (long deadline = System.nanoTime() + (timeout = unit.toNanos(timeout));;) {
				TimeUnit.NANOSECONDS.timedWait(this, timeout);
				if (state != 0) {
					break;
				}
				if ((timeout = deadline - System.nanoTime()) <= 0) {
					throw new TimeoutException();
				}
			}
		}
		if (state < 0) {
			throw new ExecutionException((Throwable) result);
		}
		return (V) result;
	}

	@Override
	public synchronized void operationCompleted(V result) {
		state = 1;
		this.result = result;
		notifyAll();
	}

	@Override
	public synchronized void operationFailed(Exception exception) {
		state = -1;
		result = exception;
		notifyAll();
	}

}
