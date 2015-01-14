package uk.co.coinfloor.api;

public class CoinfloorException extends Exception {

	private static final long serialVersionUID = 0L;

	private final int errorCode;

	public CoinfloorException(int errorCode, String errorMessage) {
		super(errorMessage);
		this.errorCode = errorCode;
	}

	public int getErrorCode() {
		return errorCode;
	}

	public String getErrorMessage() {
		return super.getMessage();
	}

	@Override
	public String getMessage() {
		String errorMessage = super.getMessage();
		return errorMessage == null ? '<' + String.valueOf(errorCode) + '>' : '<' + String.valueOf(errorCode) + "> " + errorMessage;
	}

}
