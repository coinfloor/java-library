package uk.co.coinfloor.api;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PushbackReader;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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

	}

	public static final URI defaultURI = URI.create("wss://api.coinfloor.co.uk/");

	static final long KEEPALIVE_INTERVAL_NS = 45L * 1000 * 1000 * 1000; // 45 seconds

	private static final ECDomainParameters secp224k1;
	private static final Charset ascii = Charset.forName("US-ASCII"), utf8 = Charset.forName("UTF-8");

	private final Random random = new Random();
	private final HashMap<Integer, Ticker> tickers = new HashMap<Integer, Ticker>();

	private WebSocket websocket;
	private byte[] serverNonce;
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
		new Thread("Coinfloor WebSocket Keep-alive") {

			{
				setDaemon(true);
			}

			@Override
			public void run() {
				synchronized (Coinfloor.this) {
					try {
						while (websocket != null) {
							long delay = lastActivityTime + KEEPALIVE_INTERVAL_NS - System.nanoTime();
							if (delay > 0) {
								TimeUnit.NANOSECONDS.timedWait(Coinfloor.this, delay);
							}
							else {
								websocket.getOutputStream(0, WebSocket.OP_PING, true).close();
								lastActivityTime = System.nanoTime();
							}
						}
					}
					catch (Exception e) {
						return;
					}
				}
			}

		}.start();
		Map<?, ?> welcome = (Map<?, ?>) JSON.parse(new PushbackReader(new InputStreamReader(websocket.getInputStream(), ascii)));
		serverNonce = Base64.decode((String) welcome.get("nonce"));
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
		dos.flush();
		sha.doFinal(digest, 0);
		BigInteger[] signature = signer.generateSignature(digest);
		HashMap<String, Object> request = new HashMap<String, Object>((5 + 2) / 3 * 4);
		request.put("method", "Authenticate");
		request.put("user_id", userID);
		request.put("cookie", cookie);
		request.put("nonce", Base64.toBase64String(clientNonce));
		request.put("signature", Arrays.asList(bigIntegerToBase64(signature[0]), bigIntegerToBase64(signature[1])));
		doRequest(request);
	}

	/**
	 * Retrieves all available balances of the authenticated user.
	 */
	public final Map<Integer, Long> getBalances() throws IOException, CoinfloorException {
		List<?> balances = (List<?>) doRequest(Collections.singletonMap("method", "GetBalances")).get("balances");
		HashMap<Integer, Long> ret = new HashMap<Integer, Long>((balances.size() + 2) / 3 * 4);
		for (Object balanceObj : balances) {
			Map<?, ?> balance = (Map<?, ?>) balanceObj;
			ret.put(((Number) balance.get("asset")).intValue(), ((Number) balance.get("balance")).longValue());
		}
		return ret;
	}

	/**
	 * Retrieves all open orders of the authenticated user.
	 */
	public final Map<Long, OrderInfo> getOrders() throws IOException, CoinfloorException {
		List<?> orders = (List<?>) doRequest(Collections.singletonMap("method", "GetOrders")).get("orders");
		HashMap<Long, OrderInfo> ret = new HashMap<Long, OrderInfo>((orders.size() + 2) / 3 * 4);
		for (Object orderObj : orders) {
			Map<?, ?> order = (Map<?, ?>) orderObj;
			ret.put(((Number) order.get("id")).longValue(), new OrderInfo(((Number) order.get("base")).intValue(), ((Number) order.get("counter")).intValue(), ((Number) order.get("quantity")).longValue(), ((Number) order.get("price")).longValue(), ((Number) order.get("time")).longValue()));
		}
		return ret;
	}

	/**
	 * Estimates the total (in units of the counter asset) for a market order
	 * trading the specified quantity (in units of the base asset). The
	 * quantity should be positive for a buy order or negative for a sell
	 * order.
	 */
	public final MarketOrderEstimate estimateBaseMarketOrder(int base, int counter, long quantity) throws IOException, CoinfloorException{
		HashMap<String, Object> request = new HashMap<String, Object>((4 + 2) / 3 * 4);
		request.put("method", "EstimateMarketOrder");
		request.put("base", base);
		request.put("counter", counter);
		request.put("quantity", quantity);
		Map<?, ?> response = doRequest(request);
		return new MarketOrderEstimate(base, counter, ((Number) response.get("quantity")).longValue(), ((Number) response.get("total")).longValue());
	}

	/**
	 * Estimates the quantity (in units of the base asset) for a market order
	 * trading the specified total (in units of the counter asset). The total
	 * should be positive for a buy order or negative for a sell order.
	 */
	public final MarketOrderEstimate estimateCounterMarketOrder(int base, int counter, long total) throws IOException, CoinfloorException {
		HashMap<String, Object> request = new HashMap<String, Object>((4 + 2) / 3 * 4);
		request.put("method", "EstimateMarketOrder");
		request.put("base", base);
		request.put("counter", counter);
		request.put("total", total);
		Map<?, ?> response = doRequest(request);
		return new MarketOrderEstimate(base, counter, ((Number) response.get("quantity")).longValue(), ((Number) response.get("total")).longValue());
	}

	/**
	 * Places a limit order to trade the specified quantity (in units of the
	 * base asset) at the specified price or better. The quantity should be
	 * positive for a buy order or negative for a sell order. The price should
	 * be pre-multiplied by 10000.
	 */
	public final long placeLimitOrder(int base, int counter, long quantity, long price) throws IOException, CoinfloorException {
		HashMap<String, Object> request = new HashMap<String, Object>((5 + 2) / 3 * 4);
		request.put("method", "PlaceOrder");
		request.put("base", base);
		request.put("counter", counter);
		request.put("quantity", quantity);
		request.put("price", price);
		return ((Number) doRequest(request).get("id")).longValue();
	}

	/**
	 * Executes a market order to trade up to the specified quantity (in units
	 * of the base asset). The quantity should be positive for a buy order or
	 * negative for a sell order.
	 */
	public final long executeBaseMarketOrder(int base, int counter, long quantity) throws IOException, CoinfloorException {
		HashMap<String, Object> request = new HashMap<String, Object>((4 + 2) / 3 * 4);
		request.put("method", "PlaceOrder");
		request.put("base", base);
		request.put("counter", counter);
		request.put("quantity", quantity);
		return ((Number) doRequest(request).get("remaining")).longValue();
	}

	/**
	 * Executes a market order to trade up to the specified total (in units of
	 * the counter asset). The total should be positive for a buy order or
	 * negative for a sell order.
	 */
	public final long executeCounterMarketOrder(int base, int counter, long total) throws IOException, CoinfloorException {
		HashMap<String, Object> request = new HashMap<String, Object>((4 + 2) / 3 * 4);
		request.put("method", "PlaceOrder");
		request.put("base", base);
		request.put("counter", counter);
		request.put("total", total);
		return ((Number) doRequest(request).get("remaining")).longValue();
	}

	/**
	 * Cancels the specified open order.
	 */
	public final OrderInfo cancelOrder(long id) throws IOException, CoinfloorException {
		HashMap<String, Object> request = new HashMap<String, Object>((2 + 2) / 3 * 4);
		request.put("method", "CancelOrder");
		request.put("id", id);
		Map<?, ?> response = doRequest(request);
		return new OrderInfo(((Number) response.get("base")).intValue(), ((Number) response.get("counter")).intValue(), ((Number) response.get("quantity")).longValue(), ((Number) response.get("price")).longValue(), -1);
	}

	/**
	 * Retrieves the trailing 30-day trading volume of the authenticated user
	 * in the specified asset.
	 */
	public final long getTradeVolume(int asset) throws IOException, CoinfloorException {
		HashMap<String, Object> request = new HashMap<String, Object>((2 + 2) / 3 * 4);
		request.put("method", "GetTradeVolume");
		request.put("asset", asset);
		return ((Number) doRequest(request).get("volume")).longValue();
	}

	/**
	 * Subscribes to (or unsubscribes from) the orders feed of the specified
	 * order book. Subscribing to feeds does not require authentication.
	 */
	public final Map<Long, OrderInfo> watchOrders(int base, int counter, boolean watch) throws IOException, CoinfloorException {
		HashMap<String, Object> request = new HashMap<String, Object>((4 + 2) / 3 * 4);
		request.put("method", "WatchOrders");
		request.put("base", base);
		request.put("counter", counter);
		request.put("watch", watch);
		List<?> orders = (List<?>) doRequest(request).get("orders");
		if (!watch) {
			return null;
		}
		HashMap<Long, OrderInfo> ret = new HashMap<Long, OrderInfo>((orders.size() + 2) / 3 * 4);
		for (Object orderObj : orders) {
			Map<?, ?> order = (Map<?, ?>) orderObj;
			ret.put(((Number) order.get("id")).longValue(), new OrderInfo(base, counter, ((Number) order.get("quantity")).longValue(), ((Number) order.get("price")).longValue(), ((Number) order.get("time")).longValue()));
		}
		return ret;
	}

	/**
	 * Subscribes to (or unsubscribes from) the ticker feed of the specified
	 * order book. Subscribing to feeds does not require authentication.
	 */
	public final TickerInfo watchTicker(int base, int counter, boolean watch) throws IOException, CoinfloorException {
		HashMap<String, Object> request = new HashMap<String, Object>((4 + 2) / 3 * 4);
		request.put("method", "WatchTicker");
		request.put("base", base);
		request.put("counter", counter);
		request.put("watch", watch);
		Map<?, ?> response = doRequest(request);
		if (!watch) {
			return null;
		}
		Ticker ticker = getTicker(base, counter);
		Number last = (Number) response.get("last"), bid = (Number) response.get("bid"), ask = (Number) response.get("ask"), low = (Number) response.get("low"), high = (Number) response.get("high"), volume = (Number) response.get("volume");
		return new TickerInfo(base, counter, ticker.last = last == null ? -1 : last.longValue(), ticker.bid = bid == null ? -1 : bid.longValue(), ticker.ask = ask == null ? -1 : ask.longValue(), ticker.low = low == null ? -1 : low.longValue(), ticker.high = high == null ? -1 : high.longValue(), ticker.volume = volume.longValue());
	}

	public final void pump() throws IOException {
		if (websocket == null) {
			throw new IllegalStateException("not connected");
		}
		try {
			for (;;) {
				getResponse();
			}
		}
		catch (CoinfloorException e) {
			throw new IOException(e);
		}
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

	private Map<?, ?> doRequest(Map<?, ?> request) throws IOException, CoinfloorException {
		if (websocket == null) {
			throw new IllegalStateException("not connected");
		}
		OutputStreamWriter writer = new OutputStreamWriter(websocket.getOutputStream(0, WebSocket.OP_TEXT, true), utf8);
		JSON.format(writer, request);
		writer.close();
		lastActivityTime = System.nanoTime();
		return getResponse();
	}

	private Map<?, ?> getResponse() throws IOException, CoinfloorException {
		for (;;) {
			WebSocket.MessageInputStream in = websocket.getInputStream();
			lastActivityTime = System.nanoTime();
			switch (in.getOpcode()) {
				case WebSocket.OP_TEXT: {
					Map<?, ?> message = (Map<?, ?>) JSON.parse(new PushbackReader(new InputStreamReader(in, utf8)));
					Object errorCodeObj = message.get("error_code");
					if (errorCodeObj != null) {
						int errorCode = ((Number) message.get("error_code")).intValue();
						if (errorCode == 0) {
							return message;
						}
						throw new CoinfloorException(errorCode, (String) message.get("error_msg"));
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
							int base = ((Number) message.get("base")).intValue(), counter = ((Number) message.get("counter")).intValue();
							Ticker ticker = getTicker(base, counter);
							Number last = (Number) message.get("last"), bid = (Number) message.get("bid"), ask = (Number) message.get("ask"), low = (Number) message.get("low"), high = (Number) message.get("high"), volume = (Number) message.get("volume");
							tickerChanged(base, counter, last == null ? message.containsKey("last") ? (ticker.last = -1) : ticker.last : (ticker.last = last.longValue()), bid == null ? message.containsKey("bid") ? (ticker.bid = -1) : ticker.bid : (ticker.bid = bid.longValue()), ask == null ? message.containsKey("ask") ? (ticker.ask = -1) : ticker.ask : (ticker.ask = ask.longValue()), low == null ? message.containsKey("low") ? (ticker.low = -1) : ticker.low : (ticker.low = low.longValue()), high == null ? message.containsKey("high") ? (ticker.high = -1) : ticker.high : (ticker.high = high.longValue()), volume == null ? ticker.volume : (ticker.volume = volume.longValue()));
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

	private Ticker getTicker(int base, int counter) {
		synchronized (tickers) {
			Ticker ticker;
			if ((ticker = tickers.get(base << 16 | counter)) == null) {
				tickers.put(base << 16 | counter, ticker = new Ticker());
			}
			return ticker;
		}
	}

	private static String bigIntegerToBase64(BigInteger bi) {
		byte[] bytes = bi.toByteArray();
		return bytes[0] == 0 ? Base64.toBase64String(bytes, 1, bytes.length - 1) : Base64.toBase64String(bytes);
	}

}
