package uk.co.coinfloor.api;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStreamWriter;
import java.io.PushbackReader;
import java.math.BigInteger;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Provider;
import java.security.Security;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Provides an interface to the Coinfloor trading API.
 */
public class Coinfloor {

	public static class OrderInfo {

		public final long tonce;
		public final int base, counter;
		public final long quantity, price, time;

		OrderInfo(long tonce, int base, int counter, long quantity, long price, long time) {
			this.tonce = tonce;
			this.base = base;
			this.counter = counter;
			this.quantity = quantity;
			this.price = price;
			this.time = time;
		}

		@Override
		public String toString() {
			return getClass().getSimpleName() + "[base=0x" + Integer.toHexString(base) + ", counter=0x" + Integer.toHexString(counter) + ", quantity=" + quantity + ", price=" + price + ", time=" + time + ']';
		}

	}

	public static class MarketOrderEstimate {

		public final int base, counter;
		public final long quantity, total;

		MarketOrderEstimate(int base, int counter, long quantity, long total) {
			this.base = base;
			this.counter = counter;
			this.quantity = quantity;
			this.total = total;
		}

		@Override
		public String toString() {
			return getClass().getSimpleName() + "[base=0x" + Integer.toHexString(base) + ", counter=0x" + Integer.toHexString(counter) + ", quantity=" + quantity + ", total=" + total + ']';
		}

	}

	public static class TickerInfo {

		public final int base, counter;
		public final long last, bid, ask, low, high, volume;

		TickerInfo(int base, int counter, long last, long bid, long ask, long low, long high, long volume) {
			this.base = base;
			this.counter = counter;
			this.last = last;
			this.bid = bid;
			this.ask = ask;
			this.low = low;
			this.high = high;
			this.volume = volume;
		}

		@Override
		public String toString() {
			return getClass().getSimpleName() + "[base=0x" + Integer.toHexString(base) + ", counter=0x" + Integer.toHexString(counter) + ", last=" + last + ", bid=" + bid + ", ask=" + ask + ", low=" + low + ", high=" + high + ", volume=" + volume + ']';
		}

	}

	private static class Ticker {

		long last = -1, bid = -1, ask = -1, low = -1, high = -1, volume = -1;

		Ticker() {
		}

	}

	private static abstract class ResultInterpreter<V> implements Callback<Map<?, ?>> {

		final Callback<? super V> callback;

		ResultInterpreter(Callback<? super V> callback) {
			if (callback == null) {
				throw new NullPointerException("callback");
			}
			this.callback = callback;
		}

		@Override
		public void operationCompleted(Map<?, ?> result) {
			callback.operationCompleted(interpret(result));
		}

		@Override
		public void operationFailed(Exception exception) {
			callback.operationFailed(exception);
		}

		abstract V interpret(Map<?, ?> result);

	}

	private static class NullInterpreter<V> extends ResultInterpreter<V> {

		NullInterpreter(Callback<? super V> callback) {
			super(callback);
		}

		@Override
		V interpret(Map<?, ?> result) {
			return null;
		}

	}

	private static class BalancesInterpreter extends ResultInterpreter<Map<Integer, Long>> {

		BalancesInterpreter(Callback<? super Map<Integer, Long>> callback) {
			super(callback);
		}

		@Override
		Map<Integer, Long> interpret(Map<?, ?> result) {
			List<?> balances = (List<?>) result.get("balances");
			HashMap<Integer, Long> ret = new HashMap<Integer, Long>((balances.size() + 2) / 3 * 4);
			for (Object balanceObj : balances) {
				Map<?, ?> balance = (Map<?, ?>) balanceObj;
				ret.put(((Number) balance.get("asset")).intValue(), ((Number) balance.get("balance")).longValue());
			}
			return ret;
		}

	}

	private static class OrdersInterpreter extends ResultInterpreter<Map<Long, OrderInfo>> {

		final int defaultBase, defaultCounter;

		OrdersInterpreter(Callback<? super Map<Long, OrderInfo>> callback, int defaultBase, int defaultCounter) {
			super(callback);
			this.defaultBase = defaultBase;
			this.defaultCounter = defaultCounter;
		}

		@Override
		Map<Long, OrderInfo> interpret(Map<?, ?> result) {
			List<?> orders = (List<?>) result.get("orders");
			HashMap<Long, OrderInfo> ret = new HashMap<Long, OrderInfo>((orders.size() + 2) / 3 * 4);
			for (Object orderObj : orders) {
				Map<?, ?> order = (Map<?, ?>) orderObj;
				Object tonceObj = order.get("tonce"), baseObj = order.get("base"), counterObj = order.get("counter");
				ret.put((Long) order.get("id"), new OrderInfo(tonceObj == null ? 0 : ((Number) tonceObj).longValue(), baseObj == null ? defaultBase : ((Number) baseObj).intValue(), counterObj == null ? defaultCounter : ((Number) counterObj).intValue(), ((Number) order.get("quantity")).longValue(), ((Number) order.get("price")).longValue(), ((Number) order.get("time")).longValue()));
			}
			return ret;
		}

	}

	private static class MarketOrderEstimateInterpreter extends ResultInterpreter<MarketOrderEstimate> {

		final int defaultBase, defaultCounter;

		MarketOrderEstimateInterpreter(Callback<? super MarketOrderEstimate> callback, int defaultBase, int defaultCounter) {
			super(callback);
			this.defaultBase = defaultBase;
			this.defaultCounter = defaultCounter;
		}

		@Override
		MarketOrderEstimate interpret(Map<?, ?> result) {
			Object baseObj = result.get("base"), counterObj = result.get("counter");
			return new MarketOrderEstimate(baseObj == null ? defaultBase : ((Number) baseObj).intValue(), counterObj == null ? defaultCounter : ((Number) counterObj).intValue(), ((Number) result.get("quantity")).longValue(), ((Number) result.get("total")).longValue());
		}

	}

	private static class LongInterpreter extends ResultInterpreter<Long> {

		final String fieldName;

		LongInterpreter(Callback<? super Long> callback, String fieldName) {
			super(callback);
			this.fieldName = fieldName;
		}

		@Override
		Long interpret(Map<?, ?> result) {
			return (Long) result.get(fieldName);
		}

	}

	private static class OrderInfoInterpreter extends ResultInterpreter<OrderInfo> {

		OrderInfoInterpreter(Callback<? super OrderInfo> callback) {
			super(callback);
		}

		@Override
		OrderInfo interpret(Map<?, ?> result) {
			Object tonceObj = result.get("tonce");
			return new OrderInfo(tonceObj == null ? 0 : ((Number) result.get("tonce")).longValue(), ((Number) result.get("base")).intValue(), ((Number) result.get("counter")).intValue(), ((Number) result.get("quantity")).longValue(), ((Number) result.get("price")).longValue(), ((Number) result.get("time")).longValue());
		}

	}

	private class TickerInfoInterpreter extends ResultInterpreter<TickerInfo> {

		final int defaultBase, defaultCounter;

		TickerInfoInterpreter(Callback<? super TickerInfo> callback, int defaultBase, int defaultCounter) {
			super(callback);
			this.defaultBase = defaultBase;
			this.defaultCounter = defaultCounter;
		}

		@Override
		TickerInfo interpret(Map<?, ?> result) {
			return makeTickerInfo(defaultBase, defaultCounter, result);
		}

	}

	public static final URI defaultURI = URI.create("wss://api.coinfloor.co.uk/");

	static final long KEEPALIVE_INTERVAL_NS = 45L * 1000 * 1000 * 1000; // 45 seconds
	static final int INTRA_FRAME_TIMEOUT_MS = 10 * 1000; // 10 seconds
	static final int CONNECTION_TIMEOUT_MS = 10 * 1000; // 10 seconds
	static final int HANDSHAKE_TIMEOUT_MS = 10 * 1000; // 10 seconds

	private static final Provider ecProvider;
	private static final ECParameterSpec secp224k1;
	private static final Charset ascii = Charset.forName("US-ASCII"), utf8 = Charset.forName("UTF-8");

	private final Random random = new Random();
	private final HashMap<Integer, Callback<? super Map<?, ?>>> requests = new HashMap<Integer, Callback<? super Map<?, ?>>>();
	private final HashMap<Integer, Ticker> tickers = new HashMap<Integer, Ticker>();

	private WebSocket websocket;
	private byte[] serverNonce;
	private int tagCounter;
	private long lastActivityTime;

	static {
		try {
			AlgorithmParameters algorithmParameters;
			try {
				MessageDigest.getInstance("SHA-224");
				KeyFactory.getInstance("EC");
				(algorithmParameters = AlgorithmParameters.getInstance("EC")).init(new ECGenParameterSpec("secp224k1"));
				Signature.getInstance("SHA224withECDSA");
			}
			catch (GeneralSecurityException e) {
				Provider provider = (Provider) Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider").newInstance();
				Security.addProvider(provider);
				MessageDigest.getInstance("SHA-224");
				KeyFactory.getInstance("EC", provider);
				(algorithmParameters = AlgorithmParameters.getInstance("EC", provider)).init(new ECGenParameterSpec("secp224k1"));
				Signature.getInstance("SHA224withECDSA", provider);
			}
			ecProvider = algorithmParameters.getProvider();
			secp224k1 = algorithmParameters.getParameterSpec(ECParameterSpec.class);
		}
		catch (Exception e) {
			throw new RuntimeException("Needed cryptographic algorithm support is missing. Try placing the Bouncy Castle cryptography library in your class path, or upgrade to Java 8.", e);
		}
	}

	/**
	 * Initiates a connection to a Coinfloor API server at the default URL.
	 */
	public final void connect() throws IOException {
		connect(defaultURI);
	}

	/**
	 * Initiates a connection to a Coinfloor API server, overriding the default
	 * websocket URL.
	 */
	public final synchronized void connect(URI uri) throws IOException {
		if (websocket != null) {
			throw new IllegalStateException("already connected");
		}
		websocket = new WebSocket(uri, CONNECTION_TIMEOUT_MS, HANDSHAKE_TIMEOUT_MS);
		lastActivityTime = System.nanoTime();
		WebSocket.MessageInputStream in = websocket.getInputStream(HANDSHAKE_TIMEOUT_MS, INTRA_FRAME_TIMEOUT_MS);
		if (in == null) {
			throw new SocketTimeoutException("timed out while waiting for welcome message");
		}
		Map<?, ?> welcome = (Map<?, ?>) JSON.parse(new PushbackReader(new InputStreamReader(in, ascii)));
		in.close();
		serverNonce = Base64.decode((String) welcome.get("nonce"));
		new Thread(getClass().getSimpleName() + " Pump") {

			@Override
			public void run() {
				try {
					pump();
					failRequests(null);
					disconnected(null);
				}
				catch (IOException e) {
					disconnect();
					failRequests(e);
					disconnected(e);
				}
			}

		}.start();
	}

	/**
	 * Disconnects from the Coinfloor API server if connected.
	 */
	public final synchronized void disconnect() {
		if (websocket != null) {
			websocket.close();
			websocket = null;
			notifyAll();
		}
	}

	/**
	 * Authenticates as the specified user with the given authentication cookie
	 * and passphrase.
	 */
	public final void authenticate(long userID, String cookie, String passphrase) throws IOException, CoinfloorException {
		getResult(authenticateAsync(userID, cookie, passphrase));
	}

	/**
	 * @see #authenticate(long, String, String)
	 */
	public final Future<Void> authenticateAsync(long userID, String cookie, String passphrase) throws IOException {
		AsyncResult<Void> asyncResult = new AsyncResult<Void>();
		authenticateAsync(userID, cookie, passphrase, asyncResult);
		return asyncResult;
	}

	/**
	 * @see #authenticate(long, String, String)
	 */
	public final void authenticateAsync(long userID, String cookie, String passphrase, Callback<? super Void> callback) throws IOException {
		byte[] clientNonce = new byte[16];
		random.nextBytes(clientNonce);
		byte[][] signatureComponents;
		try {
			Signature signature = Signature.getInstance("SHA224withECDSA", ecProvider);
			MessageDigest sha = MessageDigest.getInstance("SHA-224");
			ByteBuffer userIDBytes = ByteBuffer.allocate(Long.SIZE / Byte.SIZE);
			userIDBytes.putLong(userID).flip();
			sha.update(userIDBytes);
			sha.update(passphrase.getBytes(utf8));
			signature.initSign(KeyFactory.getInstance("EC", ecProvider).generatePrivate(new ECPrivateKeySpec(new BigInteger(1, sha.digest()), secp224k1)));
			userIDBytes.rewind();
			signature.update(userIDBytes);
			signature.update(serverNonce);
			signature.update(clientNonce);
			signatureComponents = unpackDERSignature(signature.sign(), 28);
		}
		catch (GeneralSecurityException e) {
			throw new RuntimeException(e);
		}
		HashMap<String, Object> request = new HashMap<String, Object>((6 + 2) / 3 * 4);
		request.put("method", "Authenticate");
		request.put("user_id", userID);
		request.put("cookie", cookie);
		request.put("nonce", Base64.encode(clientNonce));
		request.put("signature", Arrays.asList(Base64.encode(signatureComponents[0]), Base64.encode(signatureComponents[1])));
		doRequest(request, new NullInterpreter<Void>(callback));
	}

	/**
	 * Retrieves all available balances of the authenticated user.
	 */
	public final Map<Integer, Long> getBalances() throws IOException, CoinfloorException {
		return getResult(getBalancesAsync());
	}

	/**
	 * @see #getBalances()
	 */
	public final Future<Map<Integer, Long>> getBalancesAsync() throws IOException {
		AsyncResult<Map<Integer, Long>> asyncResult = new AsyncResult<Map<Integer, Long>>();
		getBalancesAsync(asyncResult);
		return asyncResult;
	}

	/**
	 * @see #getBalances()
	 */
	public final void getBalancesAsync(Callback<? super Map<Integer, Long>> callback) throws IOException {
		HashMap<String, Object> request = new HashMap<String, Object>((2 + 2) / 3 * 4);
		request.put("method", "GetBalances");
		doRequest(request, new BalancesInterpreter(callback));
	}

	/**
	 * Retrieves all open orders of the authenticated user.
	 */
	public final Map<Long, OrderInfo> getOrders() throws IOException, CoinfloorException {
		return getResult(getOrdersAsync());
	}

	/**
	 * @see #getOrders()
	 */
	public final Future<Map<Long, OrderInfo>> getOrdersAsync() throws IOException {
		AsyncResult<Map<Long, OrderInfo>> asyncResult = new AsyncResult<Map<Long, OrderInfo>>();
		getOrdersAsync(asyncResult);
		return asyncResult;
	}

	/**
	 * @see #getOrders()
	 */
	public final void getOrdersAsync(Callback<? super Map<Long, OrderInfo>> callback) throws IOException {
		HashMap<String, Object> request = new HashMap<String, Object>((2 + 2) / 3 * 4);
		request.put("method", "GetOrders");
		doRequest(request, new OrdersInterpreter(callback, -1, -1));
	}

	/**
	 * Estimates the total (in units of the counter asset) for a market order
	 * trading the specified quantity (in units of the base asset). The
	 * quantity should be positive for a buy order or negative for a sell
	 * order.
	 */
	public final MarketOrderEstimate estimateBaseMarketOrder(int base, int counter, long quantity) throws IOException, CoinfloorException {
		return getResult(estimateBaseMarketOrderAsync(base, counter, quantity));
	}

	/**
	 * @see #estimateBaseMarketOrder(int, int, long)
	 */
	public final Future<MarketOrderEstimate> estimateBaseMarketOrderAsync(int base, int counter, long quantity) throws IOException {
		AsyncResult<MarketOrderEstimate> asyncResult = new AsyncResult<MarketOrderEstimate>();
		estimateBaseMarketOrderAsync(base, counter, quantity, asyncResult);
		return asyncResult;
	}

	/**
	 * @see #estimateBaseMarketOrder(int, int, long)
	 */
	public final void estimateBaseMarketOrderAsync(int base, int counter, long quantity, Callback<? super MarketOrderEstimate> callback) throws IOException {
		HashMap<String, Object> request = new HashMap<String, Object>((5 + 2) / 3 * 4);
		request.put("method", "EstimateMarketOrder");
		request.put("base", base);
		request.put("counter", counter);
		request.put("quantity", quantity);
		doRequest(request, new MarketOrderEstimateInterpreter(callback, base, counter));
	}

	/**
	 * Estimates the quantity (in units of the base asset) for a market order
	 * trading the specified total (in units of the counter asset). The total
	 * should be positive for a buy order or negative for a sell order.
	 */
	public final MarketOrderEstimate estimateCounterMarketOrder(int base, int counter, long total) throws IOException, CoinfloorException {
		return getResult(estimateCounterMarketOrderAsync(base, counter, total));
	}

	/**
	 * @see #estimateCounterMarketOrder(int, int, long)
	 */
	public final Future<MarketOrderEstimate> estimateCounterMarketOrderAsync(int base, int counter, long total) throws IOException {
		AsyncResult<MarketOrderEstimate> asyncResult = new AsyncResult<MarketOrderEstimate>();
		estimateCounterMarketOrderAsync(base, counter, total, asyncResult);
		return asyncResult;
	}

	/**
	 * @see #estimateCounterMarketOrder(int, int, long)
	 */
	public final void estimateCounterMarketOrderAsync(int base, int counter, long total, Callback<? super MarketOrderEstimate> callback) throws IOException {
		HashMap<String, Object> request = new HashMap<String, Object>((5 + 2) / 3 * 4);
		request.put("method", "EstimateMarketOrder");
		request.put("base", base);
		request.put("counter", counter);
		request.put("total", total);
		doRequest(request, new MarketOrderEstimateInterpreter(callback, base, counter));
	}

	/**
	 * Places a limit order to trade the specified quantity (in units of the
	 * base asset) at the specified price or better. The quantity should be
	 * positive for a buy order or negative for a sell order. The tonce is
	 * optional and may be omitted by specifying 0 or a negative number.
	 */
	public final long placeLimitOrder(int base, int counter, long quantity, long price, long tonce, boolean persist) throws IOException, CoinfloorException {
		return getResult(placeLimitOrderAsync(base, counter, quantity, price, tonce, persist));
	}

	/**
	 * @see #placeLimitOrder(int, int, long, long, long, boolean)
	 */
	public final Future<Long> placeLimitOrderAsync(int base, int counter, long quantity, long price, long tonce, boolean persist) throws IOException {
		AsyncResult<Long> asyncResult = new AsyncResult<Long>();
		placeLimitOrderAsync(base, counter, quantity, price, tonce, persist, asyncResult);
		return asyncResult;
	}

	/**
	 * @see #placeLimitOrder(int, int, long, long, long, boolean)
	 */
	public final void placeLimitOrderAsync(int base, int counter, long quantity, long price, long tonce, boolean persist, Callback<? super Long> callback) throws IOException {
		HashMap<String, Object> request = new HashMap<String, Object>((8 + 2) / 3 * 4);
		request.put("method", "PlaceOrder");
		request.put("base", base);
		request.put("counter", counter);
		request.put("quantity", quantity);
		request.put("price", price);
		if (tonce > 0) {
			request.put("tonce", tonce);
		}
		if (!persist) {
			request.put("persist", persist);
		}
		doRequest(request, new LongInterpreter(callback, "id"));
	}

	@Deprecated
	public final long placeLimitOrder(int base, int counter, long quantity, long price) throws IOException, CoinfloorException {
		return placeLimitOrder(base, counter, quantity, price, 0, true);
	}

	@Deprecated
	public final Future<Long> placeLimitOrderAsync(int base, int counter, long quantity, long price) throws IOException {
		return placeLimitOrderAsync(base, counter, quantity, price, 0, true);
	}

	@Deprecated
	public final void placeLimitOrderAsync(int base, int counter, long quantity, long price, Callback<? super Long> callback) throws IOException {
		placeLimitOrderAsync(base, counter, quantity, price, 0, true, callback);
	}

	/**
	 * Executes a market order to trade up to the specified quantity (in units
	 * of the base asset). The quantity should be positive for a buy order or
	 * negative for a sell order. The tonce is optional and may be omitted by
	 * specifying 0 or a negative number.
	 */
	public final long executeBaseMarketOrder(int base, int counter, long quantity, long tonce) throws IOException, CoinfloorException {
		return getResult(executeBaseMarketOrderAsync(base, counter, quantity, tonce));
	}

	/**
	 * @see #executeBaseMarketOrder(int, int, long, long)
	 */
	public final Future<Long> executeBaseMarketOrderAsync(int base, int counter, long quantity, long tonce) throws IOException {
		AsyncResult<Long> asyncResult = new AsyncResult<Long>();
		executeBaseMarketOrderAsync(base, counter, quantity, tonce, asyncResult);
		return asyncResult;
	}

	/**
	 * @see #executeBaseMarketOrder(int, int, long, long)
	 */
	public final void executeBaseMarketOrderAsync(int base, int counter, long quantity, long tonce, Callback<? super Long> callback) throws IOException {
		HashMap<String, Object> request = new HashMap<String, Object>((6 + 2) / 3 * 4);
		request.put("method", "PlaceOrder");
		request.put("base", base);
		request.put("counter", counter);
		request.put("quantity", quantity);
		if (tonce > 0) {
			request.put("tonce", tonce);
		}
		doRequest(request, new LongInterpreter(callback, "remaining"));
	}

	@Deprecated
	public final long executeBaseMarketOrder(int base, int counter, long quantity) throws IOException, CoinfloorException {
		return executeBaseMarketOrder(base, counter, quantity, 0);
	}

	@Deprecated
	public final Future<Long> executeBaseMarketOrderAsync(int base, int counter, long quantity) throws IOException {
		return executeBaseMarketOrderAsync(base, counter, quantity, 0);
	}

	@Deprecated
	public final void executeBaseMarketOrderAsync(int base, int counter, long quantity, Callback<? super Long> callback) throws IOException {
		executeBaseMarketOrderAsync(base, counter, quantity, 0, callback);
	}

	/**
	 * Executes a market order to trade up to the specified total (in units of
	 * the counter asset). The total should be positive for a buy order or
	 * negative for a sell order. The tonce is optional and may be omitted by
	 * specifying 0 or a negative number.
	 */
	public final long executeCounterMarketOrder(int base, int counter, long total, long tonce) throws IOException, CoinfloorException {
		return getResult(executeCounterMarketOrderAsync(base, counter, total, tonce));
	}

	/**
	 * @see #executeCounterMarketOrder(int, int, long, long)
	 */
	public final Future<Long> executeCounterMarketOrderAsync(int base, int counter, long total, long tonce) throws IOException {
		AsyncResult<Long> asyncResult = new AsyncResult<Long>();
		executeCounterMarketOrderAsync(base, counter, total, tonce, asyncResult);
		return asyncResult;
	}

	/**
	 * @see #executeCounterMarketOrder(int, int, long, long)
	 */
	public final void executeCounterMarketOrderAsync(int base, int counter, long total, long tonce, Callback<? super Long> callback) throws IOException {
		HashMap<String, Object> request = new HashMap<String, Object>((6 + 2) / 3 * 4);
		request.put("method", "PlaceOrder");
		request.put("base", base);
		request.put("counter", counter);
		request.put("total", total);
		if (tonce > 0) {
			request.put("tonce", tonce);
		}
		doRequest(request, new LongInterpreter(callback, "remaining"));
	}

	@Deprecated
	public final long executeCounterMarketOrder(int base, int counter, long total) throws IOException, CoinfloorException {
		return executeCounterMarketOrder(base, counter, total, 0);
	}

	@Deprecated
	public final Future<Long> executeCounterMarketOrderAsync(int base, int counter, long total) throws IOException {
		return executeCounterMarketOrderAsync(base, counter, total, 0);
	}

	@Deprecated
	public final void executeCounterMarketOrderAsync(int base, int counter, long total, Callback<? super Long> callback) throws IOException {
		executeCounterMarketOrderAsync(base, counter, total, 0, callback);
	}

	/**
	 * Cancels the specified open order.
	 */
	public final OrderInfo cancelOrder(long id) throws IOException, CoinfloorException {
		return getResult(cancelOrderAsync(id));
	}

	/**
	 * @see #cancelOrder(long)
	 */
	public final Future<OrderInfo> cancelOrderAsync(long id) throws IOException {
		AsyncResult<OrderInfo> asyncResult = new AsyncResult<OrderInfo>();
		cancelOrderAsync(id, asyncResult);
		return asyncResult;
	}

	/**
	 * @see #cancelOrder(long)
	 */
	public final void cancelOrderAsync(long id, Callback<? super OrderInfo> callback) throws IOException {
		HashMap<String, Object> request = new HashMap<String, Object>((3 + 2) / 3 * 4);
		request.put("method", "CancelOrder");
		request.put("id", id);
		doRequest(request, new OrderInfoInterpreter(callback));
	}

	/**
	 * Cancels the open order that was placed with the specified tonce.
	 */
	public final OrderInfo cancelOrderByTonce(long tonce) throws IOException, CoinfloorException {
		return getResult(cancelOrderByTonceAsync(tonce));
	}

	/**
	 * @see #cancelOrderByTonce(long)
	 */
	public final Future<OrderInfo> cancelOrderByTonceAsync(long tonce) throws IOException {
		AsyncResult<OrderInfo> asyncResult = new AsyncResult<OrderInfo>();
		cancelOrderByTonceAsync(tonce, asyncResult);
		return asyncResult;
	}

	/**
	 * @see #cancelOrderByTonce(long)
	 */
	public final void cancelOrderByTonceAsync(long tonce, Callback<? super OrderInfo> callback) throws IOException {
		HashMap<String, Object> request = new HashMap<String, Object>((3 + 2) / 3 * 4);
		request.put("method", "CancelOrder");
		request.put("tonce", tonce);
		doRequest(request, new OrderInfoInterpreter(callback));
	}

	/**
	 * Cancels all open orders belonging to the authenticated user.
	 */
	public final Map<Long, OrderInfo> cancelAllOrders() throws IOException, CoinfloorException {
		return getResult(cancelAllOrdersAsync());
	}

	/**
	 * @see #cancelAllOrders()
	 */
	public final Future<Map<Long, OrderInfo>> cancelAllOrdersAsync() throws IOException {
		AsyncResult<Map<Long, OrderInfo>> asyncResult = new AsyncResult<Map<Long, OrderInfo>>();
		cancelAllOrdersAsync(asyncResult);
		return asyncResult;
	}

	/**
	 * @see #cancelAllOrders()
	 */
	public final void cancelAllOrdersAsync(Callback<? super Map<Long, OrderInfo>> callback) throws IOException {
		HashMap<String, Object> request = new HashMap<String, Object>((2 + 2) / 3 * 4);
		request.put("method", "CancelAllOrders");
		doRequest(request, new OrdersInterpreter(callback, -1, -1));
	}

	/**
	 * Retrieves the trailing 30-day trading volume of the authenticated user
	 * in the specified asset.
	 */
	public final long getTradeVolume(int asset) throws IOException, CoinfloorException {
		return getResult(getTradeVolumeAsync(asset));
	}

	/**
	 * @see #getTradeVolume(int)
	 */
	public final Future<Long> getTradeVolumeAsync(int asset) throws IOException {
		AsyncResult<Long> asyncResult = new AsyncResult<Long>();
		getTradeVolumeAsync(asset, asyncResult);
		return asyncResult;
	}

	/**
	 * @see #getTradeVolume(int)
	 */
	public final void getTradeVolumeAsync(int asset, Callback<? super Long> callback) throws IOException {
		HashMap<String, Object> request = new HashMap<String, Object>((3 + 2) / 3 * 4);
		request.put("method", "GetTradeVolume");
		request.put("asset", asset);
		doRequest(request, new LongInterpreter(callback, "volume"));
	}

	/**
	 * Subscribes to (or unsubscribes from) the orders feed of the specified
	 * order book. Subscribing to feeds does not require authentication.
	 */
	public final Map<Long, OrderInfo> watchOrders(int base, int counter, boolean watch) throws IOException, CoinfloorException {
		Map<Long, OrderInfo> result = getResult(watchOrdersAsync(base, counter, watch));
		return watch ? result : null;
	}

	/**
	 * @see #watchOrders(int, int, boolean)
	 */
	public final Future<Map<Long, OrderInfo>> watchOrdersAsync(int base, int counter, boolean watch) throws IOException {
		AsyncResult<Map<Long, OrderInfo>> asyncResult = new AsyncResult<Map<Long, OrderInfo>>();
		watchOrdersAsync(base, counter, watch, asyncResult);
		return asyncResult;
	}

	/**
	 * @see #watchOrders(int, int, boolean)
	 */
	public final void watchOrdersAsync(int base, int counter, boolean watch, Callback<? super Map<Long, OrderInfo>> callback) throws IOException {
		HashMap<String, Object> request = new HashMap<String, Object>((5 + 2) / 3 * 4);
		request.put("method", "WatchOrders");
		request.put("base", base);
		request.put("counter", counter);
		request.put("watch", watch);
		doRequest(request, watch ? new OrdersInterpreter(callback, base, counter) : new NullInterpreter<Map<Long, OrderInfo>>(callback));
	}

	/**
	 * Subscribes to (or unsubscribes from) the ticker feed of the specified
	 * order book. Subscribing to feeds does not require authentication.
	 */
	public final TickerInfo watchTicker(int base, int counter, boolean watch) throws IOException, CoinfloorException {
		TickerInfo result = getResult(watchTickerAsync(base, counter, watch));
		return watch ? result : null;
	}

	/**
	 * @see #watchTicker(int, int, boolean)
	 */
	public final Future<TickerInfo> watchTickerAsync(int base, int counter, boolean watch) throws IOException {
		AsyncResult<TickerInfo> asyncResult = new AsyncResult<TickerInfo>();
		watchTickerAsync(base, counter, watch, asyncResult);
		return asyncResult;
	}

	/**
	 * @see #watchTicker(int, int, boolean)
	 */
	public final void watchTickerAsync(int base, int counter, boolean watch, Callback<? super TickerInfo> callback) throws IOException {
		HashMap<String, Object> request = new HashMap<String, Object>((5 + 2) / 3 * 4);
		request.put("method", "WatchTicker");
		request.put("base", base);
		request.put("counter", counter);
		request.put("watch", watch);
		doRequest(request, watch ? new TickerInfoInterpreter(callback, base, counter) : new NullInterpreter<TickerInfo>(callback));
	}

	/**
	 * A user-supplied callback that is invoked when an available balance of
	 * the authenticated user has changed.
	 */
	protected void balanceChanged(int asset, long balance) {
	}

	/**
	 * A user-supplied callback that is invoked when an order is opened. Only
	 * events pertaining to the authenticated user's own orders are reported to
	 * this callback unless the client is subscribed to the orders feed of an
	 * order book.
	 */
	protected void orderOpened(long id, long tonce, int base, int counter, long quantity, long price, long time, boolean own) {
		orderOpened(id, tonce, base, counter, quantity, price, time);
	}

	@Deprecated
	protected void orderOpened(long id, long tonce, int base, int counter, long quantity, long price, long time) {
		orderOpened(id, base, counter, quantity, price, time);
	}

	@Deprecated
	protected void orderOpened(long id, int base, int counter, long quantity, long price, long time) {
	}

	/**
	 * A user-supplied callback that is invoked when two orders are matched
	 * (and thus a trade occurs). Only events pertaining to the authenticated
	 * user's own orders are reported to this callback unless the client is
	 * subscribed to the orders feed of an order book.
	 */
	protected void ordersMatched(long bid, long bidTonce, long ask, long askTonce, int base, int counter, long quantity, long price, long total, long bidRem, long askRem, long time, long bidBaseFee, long bidCounterFee, long askBaseFee, long askCounterFee) {
		ordersMatched(bid, ask, base, counter, quantity, price, total, bidRem, askRem, time, bidBaseFee, bidCounterFee, askBaseFee, askCounterFee);
	}

	@Deprecated
	protected void ordersMatched(long bid, long ask, int base, int counter, long quantity, long price, long total, long bidRem, long askRem, long time, long bidBaseFee, long bidCounterFee, long askBaseFee, long askCounterFee) {
	}

	/**
	 * A user-supplied callback that is invoked when an order is closed. Only
	 * events pertaining to the authenticated user's own orders are reported to
	 * this callback unless the client is subscribed to the orders feed of an
	 * order book.
	 */
	protected void orderClosed(long id, long tonce, int base, int counter, long quantity, long price, boolean own) {
		orderClosed(id, tonce, base, counter, quantity, price);
	}

	@Deprecated
	protected void orderClosed(long id, long tonce, int base, int counter, long quantity, long price) {
		orderClosed(id, base, counter, quantity, price);
	}

	@Deprecated
	protected void orderClosed(long id, int base, int counter, long quantity, long price) {
	}

	/**
	 * A user-supplied callback that is invoked when a ticker changes. Events
	 * are reported to this callback only if the client is subscribed to the
	 * ticker feed of an order book.
	 */
	protected void tickerChanged(int base, int counter, long last, long bid, long ask, long low, long high, long volume) {
	}

	/**
	 * A user-supplied callback that is invoked if the connection to the server
	 * is terminated, either by {@link #disconnect()} or spuriously.
	 */
	protected void disconnected(IOException e) {
	}

	private synchronized void doRequest(Map<String, Object> request, Callback<? super Map<?, ?>> callback) throws IOException {
		if (websocket == null) {
			throw new IllegalStateException("not connected");
		}
		Integer tag = Integer.valueOf(++tagCounter == 0 ? ++tagCounter : tagCounter);
		request.put("tag", tag);
		synchronized (requests) {
			requests.put(tag, callback);
		}
		OutputStreamWriter writer = new OutputStreamWriter(websocket.getOutputStream(0, WebSocket.OP_TEXT, true), utf8);
		JSON.format(writer, request);
		writer.close();
		lastActivityTime = System.nanoTime();
	}

	private static <V> V getResult(Future<V> future) throws IOException, CoinfloorException {
		try {
			return future.get();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			InterruptedIOException iioe = new InterruptedIOException();
			iioe.initCause(e);
			throw iioe;
		}
		catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof CoinfloorException) {
				CoinfloorException ce = (CoinfloorException) cause, ce1 = new CoinfloorException(ce.getErrorCode(), ce.getErrorMessage());
				ce1.initCause(ce);
				throw ce1;
			}
			throw new IOException(cause);
		}
	}

	final void pump() throws IOException {
		for (;;) {
			WebSocket websocket;
			int timeout;
			synchronized (this) {
				websocket = this.websocket;
				timeout = (int) TimeUnit.NANOSECONDS.toMillis(lastActivityTime + KEEPALIVE_INTERVAL_NS - System.nanoTime());
			}
			if (websocket == null) {
				break;
			}
			if (timeout <= 0) {
				websocket.getOutputStream(0, WebSocket.OP_PING, true).close();
				timeout = (int) TimeUnit.NANOSECONDS.toMillis(KEEPALIVE_INTERVAL_NS);
			}
			WebSocket.MessageInputStream in = websocket.getInputStream(timeout, INTRA_FRAME_TIMEOUT_MS);
			if (in == null) {
				continue;
			}
			lastActivityTime = System.nanoTime();
			switch (in.getOpcode()) {
				case WebSocket.OP_TEXT: {
					Map<?, ?> message = (Map<?, ?>) JSON.parse(new PushbackReader(new InputStreamReader(in, utf8)));
					Object tagObj = message.get("tag");
					if (tagObj != null) {
						Callback<? super Map<?, ?>> callback;
						synchronized (requests) {
							callback = requests.remove(((Number) tagObj).intValue());
						}
						if (callback != null) {
							Object errorCodeObj = message.get("error_code");
							if (errorCodeObj != null) {
								int errorCode = ((Number) message.get("error_code")).intValue();
								if (errorCode != 0) {
									callback.operationFailed(new CoinfloorException(errorCode, (String) message.get("error_msg")));
									continue;
								}
							}
							callback.operationCompleted(message);
						}
						continue;
					}
					Object notice = message.get("notice");
					if (notice != null) {
						if ("BalanceChanged".equals(notice)) {
							balanceChanged(((Number) message.get("asset")).intValue(), ((Number) message.get("balance")).longValue());
						}
						else if ("OrderOpened".equals(notice)) {
							Object tonceObj = message.get("tonce");
							orderOpened(((Number) message.get("id")).longValue(), tonceObj == null ? 0 : ((Number) tonceObj).longValue(), ((Number) message.get("base")).intValue(), ((Number) message.get("counter")).intValue(), ((Number) message.get("quantity")).longValue(), ((Number) message.get("price")).longValue(), ((Number) message.get("time")).longValue(), tonceObj != null || message.containsKey("tonce"));
						}
						else if ("OrdersMatched".equals(notice)) {
                                                    Object bidObj = message.get("bid"), askObj = message.get("ask"), bidRemObj = message.get("bid_rem"), askRemObj = message.get("ask_rem"), bidBaseFeeObj = message.get("bid_base_fee"), bidCounterFeeObj = message.get("bid_counter_fee"), askBaseFeeObj = message.get("ask_base_fee"), askCounterFeeObj = message.get("ask_counter_fee");
                                                    ordersMatched(bidObj == null ? -1 : ((Number) bidObj).longValue(), getBidTonce(message), askObj == null ? -1 : ((Number) askObj).longValue(), getAskTonce(message), ((Number) message.get("base")).intValue(), ((Number) message.get("counter")).intValue(), ((Number) message.get("quantity")).longValue(), ((Number) message.get("price")).longValue(), ((Number) message.get("total")).longValue(), bidRemObj == null ? -1 : ((Number) bidRemObj).longValue(), askRemObj == null ? -1 : ((Number) askRemObj).longValue(), ((Number) message.get("time")).longValue(), bidBaseFeeObj == null ? -1 : ((Number) bidBaseFeeObj).longValue(), bidCounterFeeObj == null ? -1 : ((Number) bidCounterFeeObj).longValue(), askBaseFeeObj == null ? -1 : ((Number) askBaseFeeObj).longValue(), askCounterFeeObj == null ? -1 : ((Number) askCounterFeeObj).longValue());
                                                }
						else if ("OrderClosed".equals(notice)) {
							Object tonceObj = message.get("tonce");
							orderClosed(((Number) message.get("id")).longValue(), tonceObj == null ? 0 : ((Number) tonceObj).longValue(), ((Number) message.get("base")).intValue(), ((Number) message.get("counter")).intValue(), ((Number) message.get("quantity")).longValue(), ((Number) message.get("price")).longValue(), tonceObj != null || message.containsKey("tonce"));
						}
						else if ("TickerChanged".equals(notice)) {
							TickerInfo tickerInfo = makeTickerInfo(-1, -1, message);
							tickerChanged(tickerInfo.base, tickerInfo.counter, tickerInfo.last, tickerInfo.bid, tickerInfo.ask, tickerInfo.low, tickerInfo.high, tickerInfo.volume);
						}
					}
                                    break;
				}
				case WebSocket.OP_PING: {
					WebSocket.MessageOutputStream out = websocket.getOutputStream(0, WebSocket.OP_PONG, true);
					for (int c; (c = in.read()) >= 0;) {
						out.write(c);
					}
					out.close();
					lastActivityTime = System.nanoTime();
					break;
				}
			}
		}
	}

	final void failRequests(Exception exception) {
		synchronized (requests) {
			if (!requests.isEmpty()) {
				if (exception == null) {
					exception = new IOException("disconnected");
				}
				for (Callback<?> callback : requests.values()) {
					callback.operationFailed(exception);
				}
				requests.clear();
			}
		}
	}

	final TickerInfo makeTickerInfo(int defaultBase, int defaultCounter, Map<?, ?> response) {
		Object baseObj = response.get("base"), counterObj = response.get("counter"), lastObj = response.get("last"), bidObj = response.get("bid"), askObj = response.get("ask"), lowObj = response.get("low"), highObj = response.get("high"), volumeObj = response.get("volume");
		int base = baseObj == null ? defaultBase : ((Number) baseObj).intValue(), counter = counterObj == null ? defaultCounter : ((Number) counterObj).intValue();
		boolean lastPresent = lastObj != null || response.containsKey("last"), bidPresent = bidObj != null || response.containsKey("bid"), askPresent = askObj != null || response.containsKey("ask"), lowPresent = lowObj != null || response.containsKey("low"), highPresent = highObj != null || response.containsKey("high"), volumePresent = volumeObj != null || response.containsKey("volume");
		synchronized (tickers) {
			Ticker ticker;
			if ((ticker = tickers.get(base << 16 | counter)) == null) {
				tickers.put(base << 16 | counter, ticker = new Ticker());
			}
			return new TickerInfo(base, counter, lastPresent ? (ticker.last = lastObj == null ? -1 : ((Number) lastObj).longValue()) : ticker.last, bidPresent ? (ticker.bid = bidObj == null ? -1 : ((Number) bidObj).longValue()) : ticker.bid, askPresent ? (ticker.ask = askObj == null ? -1 : ((Number) askObj).longValue()) : ticker.ask, lowPresent ? (ticker.low = lowObj == null ? -1 : ((Number) lowObj).longValue()) : ticker.low, highPresent ? (ticker.high = highObj == null ? -1 : ((Number) highObj).longValue()) : ticker.high, volumePresent ? (ticker.volume = volumeObj == null ? -1 : ((Number) volumeObj).longValue()) : ticker.volume);
		}
	}

	// see ITU-T Rec. X.690 (07/2002)
	private static byte[][] unpackDERSignature(byte[] derSignature, int length) throws SignatureException {
		byte[][] ret = new byte[2][length];
		try {
			DataInputStream dis = new DataInputStream(new ByteArrayInputStream(derSignature));
			int sequenceLength;
			if (dis.readByte() != 0x30 || // require SEQUENCE tag
					(sequenceLength = dis.readByte()) < 0) { // require definite length, short form (8.1.3.4)
				throw new SignatureException("framework returned an unparseable signature");
			}
			for (int i = 0; i < 2; ++i) {
				int integerLength;
				if (dis.readByte() != 0x02 || // require INTEGER tag
						(integerLength = dis.readByte()) < 0) { // require definite length, short form (8.1.3.4)
					throw new SignatureException("framework returned an unparseable signature");
				}
				sequenceLength -= 2 + integerLength;
				while (integerLength > length) {
					if (dis.readByte() != 0) { // require bytes in excess of length to be padding
						throw new IllegalArgumentException("integer is too large");
					}
					--integerLength;
				}
				dis.readFully(ret[i], length - integerLength, integerLength);
			}
			if (sequenceLength != 0 || dis.read() >= 0) { // require end of encoding
				throw new SignatureException("framework returned an unparseable signature");
			}
		}
		catch (IOException impossible) {
			throw new RuntimeException(impossible);
		}
		return ret;
	}

        private long getBidTonce(Map<?, ?> message) {
            long bidTonce;
            if (!message.containsKey("bid_tonce")) {
                bidTonce = -1;
            } else {
                Object tonceObj = message.get("bid_tonce");
                bidTonce = (tonceObj == null) ? 0 : ((Number) tonceObj).longValue();
            }
            return bidTonce;
        }

        private long getAskTonce(Map<?, ?> message) {
            long askTonce;
            if (!message.containsKey("ask_tonce")) {
                askTonce = -1;
            } else {
                Object tonceObj = message.get("ask_tonce");
                askTonce = (tonceObj == null) ? 0 : ((Number) tonceObj).longValue();
            }
            return askTonce;
        }
        
}
