package org.achfrag.crypto.bitfinex;

import java.net.URI;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;

import org.achfrag.crypto.Const;
import org.achfrag.crypto.bitfinex.commands.AbstractAPICommand;
import org.achfrag.crypto.bitfinex.commands.SubscribeTicker;
import org.achfrag.crypto.bitfinex.misc.APIException;
import org.achfrag.crypto.bitfinex.misc.CurrencyPair;
import org.achfrag.crypto.bitfinex.misc.WebsocketClientEndpoint;
import org.achfrag.crypto.bitfinex.misc.WebsocketCloseHandler;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BaseTick;
import org.ta4j.core.Tick;

public class BitfinexApiBroker implements WebsocketCloseHandler {

	/**
	 * The bitfinex api
	 */
	public final static String BITFINEX_URI = "wss://api.bitfinex.com/ws/2";
	
	/**
	 * The API callback
	 */
	final Consumer<String> apiCallback = ((c) -> websocketCallback(c));
	
	/**
	 * The websocket endpoint
	 */
	WebsocketClientEndpoint websocketEndpoint;
	
	/**
	 * The channel map
	 */
	private final Map<Integer, String> channelIdSymbolMap;
	
	/**
	 * The channel callbacks
	 */
	private final Map<String, List<BiConsumer<String, Tick>>> channelCallbacks;
	
	/**
	 * The last heartbeat value
	 */
	protected final AtomicLong lastHeatbeat;
	
	/**
	 * The websocket auto reconnect flag
	 */
	protected volatile boolean autoReconnectEnabled = true;

	/**
	 * The heartbeat thread
	 */
	private Thread heartbeatThread;
	
	/**
	 * The Logger
	 */
	final static Logger logger = LoggerFactory.getLogger(BitfinexApiBroker.class);

	public BitfinexApiBroker() {
		this.channelIdSymbolMap = new HashMap<>();
		this.channelCallbacks = new HashMap<>();
		this.lastHeatbeat = new AtomicLong();
	}
	
	public void connect() throws APIException {
		try {
			final URI bitfinexURI = new URI(BITFINEX_URI);
			websocketEndpoint = new WebsocketClientEndpoint(bitfinexURI);
			websocketEndpoint.addConsumer(apiCallback);
			websocketEndpoint.addCloseHandler(this);
			websocketEndpoint.connect();
			lastHeatbeat.set(System.currentTimeMillis());
			
			heartbeatThread = new Thread(new HeartbeatThread(this));
			heartbeatThread.start();
		} catch (Exception e) {
			throw new APIException(e);
		}
	}
	
	public void disconnect() {
		
		if(heartbeatThread != null) {
			heartbeatThread.interrupt();
			heartbeatThread = null;
		}
		
		if(websocketEndpoint != null) {
			websocketEndpoint.removeConsumer(apiCallback);
			websocketEndpoint.close();
			websocketEndpoint = null;
		}
	}

	public void sendCommand(final AbstractAPICommand apiCommand) {
		websocketEndpoint.sendMessage(apiCommand.getCommand(this));
	}
	
	public void websocketCallback(final String message) {
		logger.debug("Got message: {}", message);
		
		if(message.startsWith("{")) {
			handleAPICallback(message);
		} else if(message.startsWith("[")) {
			handleChannelCallback(message);
		} else {
			logger.error("Got unknown callback: {}", message);
		}
	}

	protected void handleAPICallback(final String message) {
		// JSON callback
		final JSONTokener tokener = new JSONTokener(message);
		final JSONObject jsonObject = new JSONObject(tokener);
		
		final String eventType = jsonObject.getString("event");
		
		switch(eventType) {
			case "info":
				break;
			case "subscribed":			
				final String channel = jsonObject.getString("channel");
			
			if(channel.equals("ticker")) {
					final int channelId = jsonObject.getInt("chanId");
					final String symbol = jsonObject.getString("symbol");
					logger.info("Registering symbol {} on channel {}", symbol, channelId);
					channelIdSymbolMap.put(channelId, symbol);
				} else if(channel.equals("candles")) {
					final int channelId = jsonObject.getInt("chanId");
					final String key = jsonObject.getString("key");
					logger.info("Registering key {} on channel {}", key, channelId);
					channelIdSymbolMap.put(channelId, key);
				} else {
					logger.error("Unknown subscribed callback {}", message);
				}

				break;
			case "pong":
				lastHeatbeat.set(System.currentTimeMillis());
				break;
			case "unsubscribed":
				final int channelId = jsonObject.getInt("chanId");
				final String symbol = channelIdSymbolMap.get(channelId);
				logger.info("Channel {} ({}) is unsubscribed", channelId, symbol);
				channelCallbacks.remove(symbol);
				channelIdSymbolMap.remove(channelId);
				break;
			default:
				logger.error("Unknown event: {}", message);
		}
	}

	protected void handleChannelCallback(final String message) {
		// Channel callback
		logger.debug("Channel callback");
		lastHeatbeat.set(System.currentTimeMillis());

		final Matcher matcher = BitfinexApiHelper.CHANNEL_PATTERN.matcher(message);
		
		if(! matcher.matches()) {
			if(message.contains("\"hb\"")) {
				// Ignore channel heartbeat values
			} else {
				logger.error("No match found for message {}", message);
			}
		} else {
			final int channel = Integer.parseInt(matcher.group(1));
			final String content = matcher.group(2);
			
			final String channelSymbol = channelIdSymbolMap.get(channel);
			
			if(channelSymbol.contains("trade")) {
				handleCandlestickCallback(channel, channelSymbol, content);
			} else {
				handleTickCallback(channel, content);
			}
		}
	}

	/**
	 * Handle a candlestick callback
	 * @param channel
	 * @param content
	 */
	private void handleCandlestickCallback(final int channel, final String channelSymbol, final String content) {
		// remove [[..], [...]] -> [..], [..]
		final String ticks = content.substring(1, content.length()-1);
		
		// channel symbol trade:1m:tLTCUSD
		final String symbol = (channelSymbol.split(":"))[2];
		
		final Matcher contentMatcher = BitfinexApiHelper.CHANNEL_ELEMENT_PATTERN.matcher(ticks);
		
		final List<Tick> ticksBuffer = new ArrayList<>();
		while (contentMatcher.find()) {
			final String element = contentMatcher.group(1);
			final String[] parts = element.split(",");
			
			// 0 = Timestamp
			// 1 = Open
			// 2 = Close
			// 3 = High 
			// 4 = Low
			// 5 = Volume
			final Instant i = Instant.ofEpochMilli(Long.parseLong(parts[0]));
			final ZonedDateTime withTimezone = ZonedDateTime.ofInstant(i, Const.BITFINEX_TIMEZONE);
			
			final Tick tick = new BaseTick(withTimezone, 
					Double.parseDouble(parts[1]), 
					Double.parseDouble(parts[2]), 
					Double.parseDouble(parts[3]), 
					Double.parseDouble(parts[4]), 
					Double.parseDouble(parts[5]));

			ticksBuffer.add(tick);
		}
		
		ticksBuffer.sort((t1, t2) -> t1.getEndTime().compareTo(t2.getEndTime()));

		final List<BiConsumer<String, Tick>> callbacks = channelCallbacks.get(channelSymbol);

		if(callbacks != null) {
			for(final Tick tick : ticksBuffer) {
				callbacks.forEach(c -> c.accept(symbol, tick));
			}
		}
	}

	/**
	 * Handle a tick callback
	 * @param channel
	 * @param content
	 */
	protected void handleTickCallback(final int channel, final String content) {
		final Matcher contentMatcher = BitfinexApiHelper.CHANNEL_ELEMENT_PATTERN.matcher(content);
		
		while (contentMatcher.find()) {
			final String element = contentMatcher.group(1);
			handleTickElement(channel, element);
		}
	}

	protected void handleTickElement(final int channel, final String element) {
		final String[] elements = element.split(",");
		// 0 = BID
		// 2 = ASK
		// 6 = Price
		final double price = Double.parseDouble(elements[6]);
		final Tick tick = new BaseTick(ZonedDateTime.now(Const.BITFINEX_TIMEZONE), price, price, price, price, price);

		final String symbol = channelIdSymbolMap.get(channel);
		
		final List<BiConsumer<String, Tick>> callbacks = channelCallbacks.get(symbol);

		if(callbacks != null) {
			callbacks.forEach(c -> c.accept(symbol, tick));
		}
	}

	
	public void registerTickCallback(final String symbol, final BiConsumer<String, Tick> callback) throws APIException {
		
		if(! channelCallbacks.containsKey(symbol)) {
			channelCallbacks.put(symbol, new ArrayList<>());
		}
		
		channelCallbacks.get(symbol).add(callback);	
	}
	
	public boolean removeTickCallback(final String symbol, final BiConsumer<String, Tick> callback) throws APIException {
		
		if(! channelCallbacks.containsKey(symbol)) {
			throw new APIException("Unknown ticker string: " + symbol);
		}
		
		return channelCallbacks.get(symbol).remove(callback);
	}
	
	public boolean isTickerActive(final CurrencyPair currencyPair) {
		final String currencyString = currencyPair.toBitfinexString();
		
		return getChannelForSymbol(currencyString) != -1;
	}

	public Integer getChannelForSymbol(final String currencyString) {
		return channelIdSymbolMap.entrySet()
		.stream()
		.filter((v) -> v.getValue().equals(currencyString))
		.map((v) -> v.getKey())
		.findAny().orElse(-1);
	}
	
	/* (non-Javadoc)
	 * @see org.achfrag.crypto.bitfinex.ReconnectHandler#handleReconnect()
	 */
	@Override
	public void handleWebsocketClose() {
		
		if(autoReconnectEnabled == false) {
			return;
		}
		
		reconnect(); 
	}

	protected synchronized boolean reconnect() {
		try {
			logger.info("Performing reconnect");
			
			websocketEndpoint.close();
			
			final Map<Integer, String> oldChannelIdSymbolMap = new HashMap<>();
			oldChannelIdSymbolMap.putAll(channelIdSymbolMap);
			channelIdSymbolMap.clear();
			
			websocketEndpoint.connect();
			
			oldChannelIdSymbolMap.entrySet().forEach((e) -> sendCommand(new SubscribeTicker(e.getValue())));
			
			logger.info("Waiting for ticker to resubscribe");
			while(channelIdSymbolMap.size() != oldChannelIdSymbolMap.size()) {
				Thread.sleep(100);
			}

			lastHeatbeat.set(System.currentTimeMillis());
			
			return true;
		} catch (Exception e) {
			logger.error("Got exception while reconnect", e);
			return false;
		}
	}

	public boolean isAutoReconnectEnabled() {
		return autoReconnectEnabled;
	}

	public void setAutoReconnectEnabled(boolean autoReconnectEnabled) {
		this.autoReconnectEnabled = autoReconnectEnabled;
	}
	
	public Map<Integer, String> getChannelMap() {
		return channelIdSymbolMap;
	}
	
	/**
	 * Get the last heartbeat value
	 * @return
	 */
	public AtomicLong getLastHeatbeat() {
		return lastHeatbeat;
	}
}
