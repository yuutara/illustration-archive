package com.yuutara.illustrationarchive.service;

public record IllustrationBatchImportItemResult(
		String filename,
		boolean success,
		Long illustrationId,
		String errorCode,
		String message,
		String status
) {

	public static final String SUCCESS = "SUCCESS";
	public static final String DUPLICATE = "DUPLICATE";
	public static final String FAILED = "FAILED";

	public IllustrationBatchImportItemResult(
			String filename,
			boolean success,
			Long illustrationId,
			String errorCode,
			String message
	) {
		this(filename, success, illustrationId, errorCode, message, defaultStatus(success, errorCode));
	}

	private static String defaultStatus(boolean success, String errorCode) {
		if (success) {
			return SUCCESS;
		}
		return DuplicateIllustrationException.ERROR_CODE.equals(errorCode) ? DUPLICATE : FAILED;
	}
}
