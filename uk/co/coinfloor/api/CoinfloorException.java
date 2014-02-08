package uk.co.coinfloor.api;

public class CoinfloorException extends Exception {

	private static final long serialVersionUID = 0L;

	private final int errorCode;
	private final String errorMessage;

	public CoinfloorException(int errorCode, String errorMessage) {
		super(errorMessage);
		this.errorCode = errorCode;
		this.errorMessage = errorMessage;
	}

	public int getErrorCode() {
		return errorCode;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	@Override
	public String getMessage() {
		return errorMessage == null ? '<' + String.valueOf(errorCode) + '>' : '<' + String.valueOf(errorCode) + "> " + errorMessage;
	}

}
