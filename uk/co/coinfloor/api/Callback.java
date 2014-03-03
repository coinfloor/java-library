/*
 * Created on Mar 3, 2014
 */
package uk.co.coinfloor.api;

public interface Callback<V> {

	public void operationCompleted(V result);

	public void operationFailed(Exception exception);

}
