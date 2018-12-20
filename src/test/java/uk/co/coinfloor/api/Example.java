package uk.co.coinfloor.api;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.Future;

public class Example {

	public static final int XBT = 0xF800, GBP = 0xFA20;

	public static void main(String[] args) throws Exception {
		if (args.length != 3) {
			System.err.println("arguments: <user-id> <cookie> <passphrase>");
			System.exit(-1);
		}
		long userID = Long.valueOf(args[0]);
		String cookie = args[1];
		String passphrase = args[2];
		Coinfloor coinfloor = new Coinfloor() {

			@Override
			protected void balanceChanged(int asset, long balance) {
                            System.out.println("balanceChanged(asset: 0x" + Integer.toHexString(asset) + ", balance: " + balance + ')');
			}

			@Override
			protected void orderOpened(long id, long tonce, int base, int counter, long quantity, long price, long time, boolean own) {
                            System.out.println("orderOpened(id: " + id + ", tonce: " + tonce + ", base: 0x" + Integer.toHexString(base) + ", counter: 0x" + Integer.toHexString(counter) + ", quantity: " + quantity + ", price: " + price + ", time: " + time + ", own: " + own + ')');
			}

			@Override
			protected void ordersMatched(long bid, long bidTonce, long ask, long askTonce, int base, int counter, long quantity, long price, long total, long bidRem, long askRem, long time, long bidBaseFee, long bidCounterFee, long askBaseFee, long askCounterFee) {
                            if (bidTonce > -1) {
                                System.out.println("BOUGHT - (bid: " + bid + ", bidTonce: " + bidTonce + ", ask: " + ask + ", askTonce: " + askTonce + ", base: 0x" + Integer.toHexString(base) + ", counter: 0x" + Integer.toHexString(counter) + ", quantity: " + quantity + ", price: " + price + ", total: " + total + ", bidRem: " + bidRem + ", askRem: " + askRem + ", time: " + time + ", bidBaseFee: " + bidBaseFee + ", bidCounterFee: " + bidCounterFee + ", askBaseFee: " + askBaseFee + ", askCounterFee: " + askCounterFee + ')');
                            }
                            else if(askTonce > -1) {
                                System.out.println("SOLD - (bid: " + bid + ", bidTonce: " + bidTonce + ", ask: " + ask + ", askTonce: " + askTonce + ", base: 0x" + Integer.toHexString(base) + ", counter: 0x" + Integer.toHexString(counter) + ", quantity: " + quantity + ", price: " + price + ", total: " + total + ", bidRem: " + bidRem + ", askRem: " + askRem + ", time: " + time + ", bidBaseFee: " + bidBaseFee + ", bidCounterFee: " + bidCounterFee + ", askBaseFee: " + askBaseFee + ", askCounterFee: " + askCounterFee + ')');
                            }
                            else {
                                System.out.println("ordersMatched (bid: " + bid + ", bidTonce: " + bidTonce + ", ask: " + ask + ", askTonce: " + askTonce + ", base: 0x" + Integer.toHexString(base) + ", counter: 0x" + Integer.toHexString(counter) + ", quantity: " + quantity + ", price: " + price + ", total: " + total + ", bidRem: " + bidRem + ", askRem: " + askRem + ", time: " + time + ", bidBaseFee: " + bidBaseFee + ", bidCounterFee: " + bidCounterFee + ", askBaseFee: " + askBaseFee + ", askCounterFee: " + askCounterFee + ')');
                            }
                        }

			@Override
			protected void orderClosed(long id, long tonce, int base, int counter, long quantity, long price, boolean own) {
                            System.out.println("orderClosed(id: " + id + ", tonce: " + tonce + ", base: 0x" + Integer.toHexString(base) + ", counter: 0x" + Integer.toHexString(counter) + ", quantity: " + quantity + ", price: " + price + ", own: " + own + ')');
			}

			@Override
			protected void tickerChanged(int base, int counter, long last, long bid, long ask, long low, long high, long volume) {
                            System.out.println("tickerChanged(base: 0x" + Integer.toHexString(base) + ", counter: 0x" + Integer.toHexString(counter) + ", last: " + last + ", bid: " + bid + ", ask: " + ask + ", low: " + low + ", high: " + high + ", volume: " + volume + ')');
			}

		};
		coinfloor.connect(URI.create("wss://api.coinfloor.co.uk/"));
		Future<Void> authenticateResult = coinfloor.authenticateAsync(userID, cookie, passphrase);
		Future<Map<Integer, Long>> balancesResult = coinfloor.getBalancesAsync();
		Future<Map<Long, Coinfloor.OrderInfo>> ordersResult = coinfloor.getOrdersAsync();
		Future<Long> tradeVolumeXBTResult = coinfloor.getTradeVolumeAsync(XBT);
		Future<Long> tradeVolumeGBPResult = coinfloor.getTradeVolumeAsync(GBP);
		Future<Map<Long, Coinfloor.OrderInfo>> watchOrdersResult = coinfloor.watchOrdersAsync(XBT, GBP, true);
		Future<Coinfloor.TickerInfo> watchTickerResult = coinfloor.watchTickerAsync(XBT, GBP, true);
		authenticateResult.get();
		System.out.println("coinfloor.getBalances() => " + balancesResult.get());
		System.out.println("coinfloor.getOrders() => " + ordersResult.get());
		System.out.println("coinfloor.getTradeVolume(XBT) => " + tradeVolumeXBTResult.get());
		System.out.println("coinfloor.getTradeVolume(GBP) => " + tradeVolumeGBPResult.get());
		System.out.println("coinfloor.watchOrders(XBT, GBP, true) => " + watchOrdersResult.get());
		System.out.println("coinfloor.watchTicker(XBT, GBP, true) => " + watchTickerResult.get());
		coinfloor.disconnect();
	}

}
