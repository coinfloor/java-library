package uk.co.coinfloor.api;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PushbackReader;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.bouncycastle.crypto.digests.SHA224Digest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.util.encoders.Base64;

/**
 * Provides an interface to the Coinfloor trading API.
 */
public class Coinfloor {

	public static class OrderInfo {

		public final int base, counter;
		public final long quantity, price, time;

		OrderInfo(int base, int counter, long quantity, long price, long time) {
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

	private static class RequestRecord<V> {

		final ResponseInterpreter<V> responseInterpreter;
		final AsyncResult<V> asyncResult;

		RequestRecord(ResponseInterpreter<V> responseInterpreter, AsyncResult<V> asyncResult) {
			this.responseInterpreter = responseInterpreter;
			this.asyncResult = asyncResult;
		}

	}

	private interface ResponseInterpreter<V> {

		V interpret(Map<?, ?> response);

	}

	@SuppressWarnings("rawtypes")
	private static class NullInterpreter implements ResponseInterpreter {

		static final NullInterpreter instance = new NullInterpreter();

		@Override
		public Object interpret(Map response) {
			return null;
		}

		@SuppressWarnings("unchecked")
		static final <V> ResponseInterpreter<V> instance() {
			return instance;
		}

	}

	private static class BalancesInterpreter implements ResponseInterpreter<Map<Integer, Long>> {

		static final BalancesInterpreter instance = new BalancesInterpreter();

		@Override
		public Map<Integer, Long> interpret(Map<?, ?> response) {
			List<?> balances = (List<?>) response.get("balances");
			HashMap<Integer, Long> ret = new HashMap<Integer, Long>((balances.size() + 2) / 3 * 4);
			for (Object balanceObj : balances) {
				Map<?, ?> balance = (Map<?, ?>) balanceObj;
				ret.put(((Number) balance.get("asset")).intValue(), ((Number) balance.get("balance")).longValue());
			}
			return ret;
		}

	}

	private static class OrdersInterpreter implements ResponseInterpreter<Map<Long, OrderInfo>> {

		static final OrdersInterpreter dumbInstance = new OrdersInterpreter(-1, -1);

		final int defaultBase, defaultCounter;

		OrdersInterpreter(int defaultBase, int defaultCounter) {
			this.defaultBase = defaultBase;
			this.defaultCounter = defaultCounter;
		}

		@Override
		public Map<Long, OrderInfo> interpret(Map<?, ?> response) {
			List<?> orders = (List<?>) response.get("orders");
			HashMap<Long, OrderInfo> ret = new HashMap<Long, OrderInfo>((orders.size() + 2) / 3 * 4);
			for (Object orderObj : orders) {
				Map<?, ?> order = (Map<?, ?>) orderObj;
				Object baseObj = order.get("base"), counterObj = order.get("counter");
				ret.put((Long) order.get("id"), new OrderInfo(baseObj == null ? defaultBase : ((Number) baseObj).intValue(), counterObj == null ? defaultCounter : ((Number) counterObj).intValue(), ((Number) order.get("quantity")).longValue(), ((Number) order.get("price")).longValue(), ((Number) order.get("time")).longValue()));
			}
			return ret;
		}

	}

	private static class MarketOrderEstimateInterpreter implements ResponseInterpreter<MarketOrderEstimate> {

		final int defaultBase, defaultCounter;

		MarketOrderEstimateInterpreter(int defaultBase, int defaultCounter) {
			this.defaultBase = defaultBase;
			this.defaultCounter = defaultCounter;
		}

		@Override
		public MarketOrderEstimate interpret(Map<?, ?> response) {
			Object baseObj = response.get("base"), counterObj = response.get("counter");
			return new MarketOrderEstimate(baseObj == null ? defaultBase : ((Number) baseObj).intValue(), counterObj == null ? defaultCounter : ((Number) counterObj).intValue(), ((Number) response.get("quantity")).longValue(), ((Number) response.get("total")).longValue());
		}

	}

	private static class LongInterpreter implements ResponseInterpreter<Long> {

		static final LongInterpreter idInstance = new LongInterpreter("id");
		static final LongInterpreter remainingInstance = new LongInterpreter("remaining");
		static final LongInterpreter volumeInstance = new LongInterpreter("volume");

		final String fieldName;

		LongInterpreter(String fieldName) {
			this.fieldName = fieldName;
		}

		@Override
		public Long interpret(Map<?, ?> response) {
			return (Long) response.get(fieldName);
		}

	}

	private static class OrderInfoInterpreter implements ResponseInterpreter<OrderInfo> {

		static final OrderInfoInterpreter instance = new OrderInfoInterpreter();

		@Override
		public OrderInfo interpret(Map<?, ?> response) {
			Object timeObj = response.get("time");
			return new OrderInfo(((Number) response.get("base")).intValue(), ((Number) response.get("counter")).intValue(), ((Number) response.get("quantity")).longValue(), ((Number) response.get("price")).longValue(), timeObj == null ? -1 : ((Number) timeObj).longValue());
		}

	}

	private class TickerInfoInterpreter implements ResponseInterpreter<TickerInfo> {

		final int defaultBase, defaultCounter;

		TickerInfoInterpreter(int defaultBase, int defaultCounter) {
			this.defaultBase = defaultBase;
			this.defaultCounter = defaultCounter;
		}

		@Override
		public TickerInfo interpret(Map<?, ?> response) {
			return makeTickerInfo(defaultBase, defaultCounter, response);
		}

	}

	public static final URI defaultURI = URI.create("wss://api.coinfloor.co.uk/");

	static final long KEEPALIVE_INTERVAL_NS = 45L * 1000 * 1000 * 1000; // 45 seconds

	private static final ECDomainParameters secp224k1;
	private static final Charset ascii = Charset.forName("US-ASCII"), utf8 = Charset.forName("UTF-8");

	private final Random random = new Random();
	private final HashMap<Integer, RequestRecord<?>> requests = new HashMap<Integer, RequestRecord<?>>();
	private final HashMap<Integer, Ticker> tickers = new HashMap<Integer, Ticker>();

	private WebSocket websocket;
	private byte[] serverNonce;
	private int tagCounter;
	private long lastActivityTime;

	static {
		ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("secp224k1");
		secp224k1 = new ECDomainParameters(spec.getCurve(), spec.getG(), spec.getN(), spec.getH());
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
		websocket = new WebSocket(uri);
		lastActivityTime = System.nanoTime();
		Map<?, ?> welcome = (Map<?, ?>) JSON.parse(new PushbackReader(new InputStreamReader(websocket.getInputStream(), ascii)));
		serverNonce = Base64.decode((String) welcome.get("nonce"));
		new Thread(getClass().getSimpleName() + " Pump") {

			@Override
			public void run() {
				try {
					pump();
				}
				catch (IOException e) {
					disconnect();
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

	public final Future<Void> authenticateAsync(long userID, String cookie, String passphrase) throws IOException {
		byte[] clientNonce = new byte[16];
		random.nextBytes(clientNonce);
		final SHA224Digest sha = new SHA224Digest();
		DataOutputStream dos = new DataOutputStream(new OutputStream() {

			@Override
			public void write(int b) {
				sha.update((byte) b);
			}

			@Override
			public void write(byte[] buf, int off, int len) {
				sha.update(buf, off, len);
			}

		});
		dos.writeLong(userID);
		dos.write(passphrase.getBytes(utf8));
		dos.flush();
		byte[] digest = new byte[28];
		sha.doFinal(digest, 0);
		ECDSASigner signer = new ECDSASigner();
		signer.init(true, new ECPrivateKeyParameters(new BigInteger(1, digest), secp224k1));
		dos.writeLong(userID);
		dos.write(serverNonce);
		dos.write(clientNonce);
		dos.close();
		sha.doFinal(digest, 0);
		BigInteger[] signature = signer.generateSignature(digest);
		HashMap<String, Object> request = new HashMap<String, Object>((6 + 2) / 3 * 4);
		request.put("method", "Authenticate");
		request.put("user_id", userID);
		request.put("cookie", cookie);
		request.put("nonce", Base64.toBase64String(clientNonce));
		request.put("signature", Arrays.asList(bigIntegerToBase64(signature[0]), bigIntegerToBase64(signature[1])));
		return doRequest(request, NullInterpreter.<Void>instance());
	}

	/**
	 * Retrieves all available balances of the authenticated user.
	 */
	public final Map<Integer, Long> getBalances() throws IOException, CoinfloorException {
		return getResult(getBalancesAsync());
	}

	public final Future<Map<Integer, Long>> getBalancesAsync() throws IOException {
		HashMap<String, Object> request = new HashMap<String, Object>((2 + 2) / 3 * 4);
		request.put("method", "GetBalances");
		return doRequest(request, BalancesInterpreter.instance);
	}

	/**
	 * Retrieves all open orders of the authenticated user.
	 */
	public final Map<Long, OrderInfo> getOrders() throws IOException, CoinfloorException {
		return getResult(getOrdersAsync());
	}

	public final Future<Map<Long, OrderInfo>> getOrdersAsync() throws IOException {
		HashMap<String, Object> request = new HashMap<String, Object>((2 + 2) / 3 * 4);
		request.put("method", "GetOrders");
		return doRequest(request, OrdersInterpreter.dumbInstance);
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

	public final Future<MarketOrderEstimate> estimateBaseMarketOrderAsync(int base, int counter, long quantity) throws IOException {
		HashMap<String, Object> request = new HashMap<String, Object>((5 + 2) / 3 * 4);
		request.put("method", "EstimateMarketOrder");
		request.put("base", base);
		request.put("counter", counter);
		request.put("quantity", quantity);
		return doRequest(request, new MarketOrderEstimateInterpreter(base, counter));
	}

	/**
	 * Estimates the quantity (in units of the base asset) for a market order
	 * trading the specified total (in units of the counter asset). The total
	 * should be positive for a buy order or negative for a sell order.
	 */
	public final MarketOrderEstimate estimateCounterMarketOrder(int base, int counter, long total) throws IOException, CoinfloorException {
		return getResult(estimateCounterMarketOrderAsync(base, counter, total));
	}

	public final Future<MarketOrderEstimate> estimateCounterMarketOrderAsync(int base, int counter, long total) throws IOException {
		HashMap<String, Object> request = new HashMap<String, Object>((5 + 2) / 3 * 4);
		request.put("method", "EstimateMarketOrder");
		request.put("base", base);
		request.put("counter", counter);
		request.put("total", total);
		return doRequest(request, new MarketOrderEstimateInterpreter(base, counter));
	}

	/**
	 * Places a limit order to trade the specified quantity (in units of the
	 * base asset) at the specified price or better. The quantity should be
	 * positive for a buy order or negative for a sell order. The price should
	 * be pre-multiplied by 10000.
	 */
	public final long placeLimitOrder(int base, int counter, long quantity, long price) throws IOException, CoinfloorException {
		return getResult(placeLimitOrderAsync(base, counter, quantity, price));
	}

	public final Future<Long> placeLimitOrderAsync(int base, int counter, long quantity, long price) throws IOException {
		HashMap<String, Object> request = new HashMap<String, Object>((6 + 2) / 3 * 4);
		request.put("method", "PlaceOrder");
		request.put("base", base);
		request.put("counter", counter);
		request.put("quantity", quantity);
		request.put("price", price);
		return doRequest(request, LongInterpreter.idInstance);
	}

	/**
	 * Executes a market order to trade up to the specified quantity (in units
	 * of the base asset). The quantity should be positive for a buy order or
	 * negative for a sell order.
	 */
	public final long executeBaseMarketOrder(int base, int counter, long quantity) throws IOException, CoinfloorException {
		return getResult(executeBaseMarketOrderAsync(base, counter, quantity));
	}

	public final Future<Long> executeBaseMarketOrderAsync(int base, int counter, long quantity) throws IOException {
		HashMap<String, Object> request = new HashMap<String, Object>((5 + 2) / 3 * 4);
		request.put("method", "PlaceOrder");
		request.put("base", base);
		request.put("counter", counter);
		request.put("quantity", quantity);
		return doRequest(request, LongInterpreter.remainingInstance);
	}

	/**
	 * Executes a market order to trade up to the specified total (in units of
	 * the counter asset). The total should be positive for a buy order or
	 * negative for a sell order.
	 */
	public final long executeCounterMarketOrder(int base, int counter, long total) throws IOException, CoinfloorException {
		return getResult(executeCounterMarketOrderAsync(base, counter, total));
	}

	public final Future<Long> executeCounterMarketOrderAsync(int base, int counter, long total) throws IOException {
		HashMap<String, Object> request = new HashMap<String, Object>((5 + 2) / 3 * 4);
		request.put("method", "PlaceOrder");
		request.put("base", base);
		request.put("counter", counter);
		request.put("total", total);
		return doRequest(request, LongInterpreter.remainingInstance);
	}

	/**
	 * Cancels the specified open order.
	 */
	public final OrderInfo cancelOrder(long id) throws IOException, CoinfloorException {
		return getResult(cancelOrderAsync(id));
	}

	public final Future<OrderInfo> cancelOrderAsync(long id) throws IOException {
		HashMap<String, Object> request = new HashMap<String, Object>((3 + 2) / 3 * 4);
		request.put("method", "CancelOrder");
		request.put("id", id);
		return doRequest(request, OrderInfoInterpreter.instance);
	}

	/**
	 * Retrieves the trailing 30-day trading volume of the authenticated user
	 * in the specified asset.
	 */
	public final long getTradeVolume(int asset) throws IOException, CoinfloorException {
		return getResult(getTradeVolumeAsync(asset));
	}

	public final Future<Long> getTradeVolumeAsync(int asset) throws IOException {
		HashMap<String, Object> request = new HashMap<String, Object>((3 + 2) / 3 * 4);
		request.put("method", "GetTradeVolume");
		request.put("asset", asset);
		return doRequest(request, LongInterpreter.volumeInstance);
	}

	/**
	 * Subscribes to (or unsubscribes from) the orders feed of the specified
	 * order book. Subscribing to feeds does not require authentication.
	 */
	public final Map<Long, OrderInfo> watchOrders(int base, int counter, boolean watch) throws IOException, CoinfloorException {
		Map<Long, OrderInfo> result = getResult(watchOrdersAsync(base, counter, watch));
		return watch ? result : null;
	}

	public final Future<Map<Long, OrderInfo>> watchOrdersAsync(int base, int counter, boolean watch) throws IOException {
		HashMap<String, Object> request = new HashMap<String, Object>((5 + 2) / 3 * 4);
		request.put("method", "WatchOrders");
		request.put("base", base);
		request.put("counter", counter);
		request.put("watch", watch);
		return doRequest(request, watch ? new OrdersInterpreter(base, counter) : NullInterpreter.<Map<Long, OrderInfo>>instance());
	}

	/**
	 * Subscribes to (or unsubscribes from) the ticker feed of the specified
	 * order book. Subscribing to feeds does not require authentication.
	 */
	public final TickerInfo watchTicker(int base, int counter, boolean watch) throws IOException, CoinfloorException {
		TickerInfo result = getResult(watchTickerAsync(base, counter, watch));
		return watch ? result : null;
	}

	public final Future<TickerInfo> watchTickerAsync(int base, int counter, boolean watch) throws IOException {
		HashMap<String, Object> request = new HashMap<String, Object>((5 + 2) / 3 * 4);
		request.put("method", "WatchTicker");
		request.put("base", base);
		request.put("counter", counter);
		request.put("watch", watch);
		return doRequest(request, watch ? new TickerInfoInterpreter(base, counter) : NullInterpreter.<TickerInfo>instance());
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
	protected void orderOpened(long id, int base, int counter, long quantity, long price, long time) {
	}

	/**
	 * A user-supplied callback that is invoked when two orders are matched
	 * (and thus a trade occurs). Only events pertaining to the authenticated
	 * user's own orders are reported to this callback unless the client is
	 * subscribed to the orders feed of an order book.
	 */
	protected void ordersMatched(long bid, long ask, int base, int counter, long quantity, long price, long total, long bidRem, long askRem, long time, long bidBaseFee, long bidCounterFee, long askBaseFee, long askCounterFee) {
	}

	/**
	 * A user-supplied callback that is invoked when an order is closed. Only
	 * events pertaining to the authenticated user's own orders are reported to
	 * this callback unless the client is subscribed to the orders feed of an
	 * order book.
	 */
	protected void orderClosed(long id, int base, int counter, long quantity, long price) {
	}

	/**
	 * A user-supplied callback that is invoked when a ticker changes. Events
	 * are reported to this callback only if the client is subscribed to the
	 * ticker feed of an order book.
	 */
	protected void tickerChanged(int base, int counter, long last, long bid, long ask, long low, long high, long volume) {
	}

	private synchronized <V> Future<V> doRequest(Map<String, Object> request, ResponseInterpreter<V> interpreter) throws IOException {
		if (websocket == null) {
			throw new IllegalStateException("not connected");
		}
		Integer tag = Integer.valueOf(++tagCounter == 0 ? ++tagCounter : tagCounter);
		request.put("tag", tag);
		OutputStreamWriter writer = new OutputStreamWriter(websocket.getOutputStream(0, WebSocket.OP_TEXT, true), utf8);
		JSON.format(writer, request);
		writer.close();
		lastActivityTime = System.nanoTime();
		AsyncResult<V> asyncResult = new AsyncResult<V>();
		RequestRecord<V> requestRecord = new RequestRecord<V>(interpreter, asyncResult);
		synchronized (requests) {
			requests.put(tag, requestRecord);
		}
		return asyncResult;
	}

	private <V> V getResult(Future<V> future) throws IOException, CoinfloorException {
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

	@SuppressWarnings({ "rawtypes", "unchecked" })
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
			WebSocket.MessageInputStream in = websocket.getInputStream(timeout);
			if (in == null) {
				continue;
			}
			lastActivityTime = System.nanoTime();
			switch (in.getOpcode()) {
				case WebSocket.OP_TEXT: {
					Map<?, ?> message = (Map<?, ?>) JSON.parse(new PushbackReader(new InputStreamReader(in, utf8)));
					Object tagObj = message.get("tag");
					if (tagObj != null) {
						RequestRecord<?> requestRecord;
						synchronized (requests) {
							requestRecord = requests.remove(((Number) tagObj).intValue());
						}
						if (requestRecord != null) {
							Object errorCodeObj = message.get("error_code");
							if (errorCodeObj != null) {
								int errorCode = ((Number) message.get("error_code")).intValue();
								if (errorCode != 0) {
									requestRecord.asyncResult.setException(new CoinfloorException(errorCode, (String) message.get("error_msg")));
									continue;
								}
							}
							((AsyncResult) requestRecord.asyncResult).setResult(requestRecord.responseInterpreter.interpret(message));
						}
						continue;
					}
					Object notice = message.get("notice");
					if (notice != null) {
						if ("BalanceChanged".equals(notice)) {
							balanceChanged(((Number) message.get("asset")).intValue(), ((Number) message.get("balance")).longValue());
						}
						else if ("OrderOpened".equals(notice)) {
							orderOpened(((Number) message.get("id")).longValue(), ((Number) message.get("base")).intValue(), ((Number) message.get("counter")).intValue(), ((Number) message.get("quantity")).longValue(), ((Number) message.get("price")).longValue(), ((Number) message.get("time")).longValue());
						}
						else if ("OrdersMatched".equals(notice)) {
							Number bid = (Number) message.get("bid"), ask = (Number) message.get("ask"), bidRem = (Number) message.get("bid_rem"), askRem = (Number) message.get("ask_rem"), bidBaseFee = (Number) message.get("bid_base_fee"), bidCounterFee = (Number) message.get("bid_counter_fee"), askBaseFee = (Number) message.get("ask_base_fee"), askCounterFee = (Number) message.get("ask_counter_fee");
							ordersMatched(bid == null ? -1 : bid.longValue(), ask == null ? -1 : ask.longValue(), ((Number) message.get("base")).intValue(), ((Number) message.get("counter")).intValue(), ((Number) message.get("quantity")).longValue(), ((Number) message.get("price")).longValue(), ((Number) message.get("total")).longValue(), bidRem == null ? -1 : bidRem.longValue(), askRem == null ? -1 : askRem.longValue(), ((Number) message.get("time")).longValue(), bidBaseFee == null ? -1 : bidBaseFee.longValue(), bidCounterFee == null ? -1 : bidCounterFee.longValue(), askBaseFee == null ? -1 : askBaseFee.longValue(), askCounterFee == null ? -1 : askCounterFee.longValue());
						}
						else if ("OrderClosed".equals(notice)) {
							orderClosed(((Number) message.get("id")).longValue(), ((Number) message.get("base")).intValue(), ((Number) message.get("counter")).intValue(), ((Number) message.get("quantity")).longValue(), ((Number) message.get("price")).longValue());
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

	private static String bigIntegerToBase64(BigInteger bi) {
		byte[] bytes = bi.toByteArray();
		return bytes[0] == 0 ? Base64.toBase64String(bytes, 1, bytes.length - 1) : Base64.toBase64String(bytes);
	}

}
